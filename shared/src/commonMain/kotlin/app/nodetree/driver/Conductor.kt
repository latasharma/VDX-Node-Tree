package app.nodetree.driver

import app.nodetree.model.Catalog
import app.nodetree.model.Platform
import app.nodetree.model.RecipeStore
import app.nodetree.narrate.Narrator
import app.nodetree.plan.Intent
import app.nodetree.plan.IntentParser
import app.nodetree.plan.ParseResult
import app.nodetree.plan.PlanResult
import app.nodetree.plan.Planner
import app.nodetree.run.Executor
import app.nodetree.run.RunOutcome
import app.nodetree.run.RunSession
import app.nodetree.run.RunTree
import app.nodetree.run.TreeEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place a request becomes a run. Every driver calls [handle]; the
 * conductor parses, plans (recipe first), runs, holds the conversation state
 * per session (a pending yes/no, a missing slot), and publishes the run tree
 * for the views.
 */
class Conductor(
    private val catalog: Catalog,
    recipes: RecipeStore,
    private val parser: IntentParser,
    private val executor: Executor,
    platform: Platform,
    private val onEvent: (TreeEvent) -> Unit = {},
) {
    private val planner = Planner(catalog, recipes, platform)
    private val mutex = Mutex()
    private val confirming = mutableMapOf<String, PendingConfirmation>()
    private val awaitingSlot = mutableMapOf<String, AwaitingSlot>()
    private var runCounter = 0

    private val latest = MutableStateFlow<RunTree?>(null)

    /** The most recent run tree, updated on every step change. Views observe this. */
    val latestTree: StateFlow<RunTree?> get() = latest

    suspend fun handle(request: DriverRequest): DriverReply = mutex.withLock { handleLocked(request) }

    /** The driver's conversation ended (e.g. Alexa closed the session). A pending write is cancelled. */
    suspend fun endSession(sessionId: String) = mutex.withLock {
        awaitingSlot.remove(sessionId)
        confirming.remove(sessionId)?.session?.confirm(approved = false)
        Unit
    }

    private suspend fun handleLocked(request: DriverRequest): DriverReply {
        val sid = request.sessionId
        val text = request.text.trim()
        val lower = text.lowercase().trimEnd('.', '!', '?').trim()

        if (lower in STOP_WORDS) {
            awaitingSlot.remove(sid)
            confirming.remove(sid)?.session?.confirm(approved = false)
            return DriverReply.End("Okay.")
        }
        if (lower == "help") return DriverReply.Say(HELP)

        confirming[sid]?.let { pending ->
            val answer = yesNo(lower) ?: return DriverReply.AskConfirm("Please say yes or no. ${pending.question}")
            confirming.remove(sid)
            return toReply(sid, pending.session, pending.session.confirm(answer))
        }

        awaitingSlot.remove(sid)?.let { waiting ->
            val need = waiting.need
            if (lower.isEmpty()) {
                awaitingSlot[sid] = waiting
                return DriverReply.Ask(need.question)
            }
            val fullText = "${waiting.originalText} $text"
            return start(sid, Intent(need.family, mapOf(need.slot to text), fullText, confidence = 0.9))
        }

        return when (val parsed = parser.parse(text)) {
            is ParseResult.Parsed -> start(sid, parsed.intent)
            is ParseResult.NeedsSlot -> {
                awaitingSlot[sid] = AwaitingSlot(parsed, text)
                DriverReply.Ask(parsed.question)
            }
            ParseResult.Unknown -> DriverReply.Say(SORRY)
        }
    }

    private suspend fun start(sid: String, intent: Intent): DriverReply =
        when (val plan = planner.plan(intent)) {
            PlanResult.NoPlan -> DriverReply.Say(SORRY)
            is PlanResult.Unavailable -> DriverReply.Say(plan.reason)
            is PlanResult.Planned -> {
                val session = RunSession(
                    catalog = catalog,
                    recipe = plan.recipe,
                    intent = intent,
                    runId = "run-${++runCounter}",
                    source = plan.source,
                    executor = executor,
                    onEvent = onEvent,
                    onTree = { latest.value = it },
                )
                toReply(sid, session, session.start())
            }
        }

    private fun toReply(sid: String, session: RunSession, outcome: RunOutcome): DriverReply = when (outcome) {
        is RunOutcome.Completed -> DriverReply.Say(Narrator.completed(outcome.tree))
        is RunOutcome.NeedsConfirmation -> {
            confirming[sid] = PendingConfirmation(session, outcome.question)
            DriverReply.AskConfirm(outcome.question)
        }
        is RunOutcome.Failed -> DriverReply.Say(outcome.userMessage)
        is RunOutcome.Cancelled -> DriverReply.Say("Okay, I won't do that.")
    }

    private class PendingConfirmation(val session: RunSession, val question: String)

    private class AwaitingSlot(val need: ParseResult.NeedsSlot, val originalText: String)

    companion object {
        const val SORRY = "Sorry, I can't do that yet."
        const val HELP = "You can say things like: call Mom, or what's on my calendar today."

        private val STOP_WORDS = setOf("stop", "cancel", "never mind", "nevermind", "quit")
        private val YES = setOf("yes", "yeah", "yep", "sure", "ok", "okay", "go ahead", "do it", "confirm")
        private val NO = setOf("no", "nope", "don't", "do not", "no thanks")

        /** Fail closed: anything not clearly yes or no is asked again. */
        fun yesNo(lower: String): Boolean? = when (lower) {
            in YES -> true
            in NO -> false
            else -> null
        }
    }
}
