package app.nodetree.driver.alexa

import app.nodetree.driver.Conductor
import app.nodetree.driver.Driver
import app.nodetree.driver.DriverCapabilities
import app.nodetree.driver.DriverReply
import app.nodetree.driver.DriverRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Limits the simulator enforces so code written against it behaves on real
 * Alexa. PLACEHOLDER VALUES: check both against Amazon's current custom-skill
 * documentation on the day we get access, and update them here.
 */
data class AlexaRules(
    val maxSpeechChars: Int = 600,
    val responseBudgetMs: Long = 8_000,
)

/** Alexa-side request, after Alexa's own speech recognition and intent matching. */
sealed interface AlexaInbound {
    val sessionId: String

    data class Utterance(val request: DriverRequest) : AlexaInbound {
        override val sessionId get() = request.sessionId
    }

    data class Launch(override val sessionId: String) : AlexaInbound
    data class SessionEnded(override val sessionId: String) : AlexaInbound
    data class Unsupported(override val sessionId: String, val type: String) : AlexaInbound
}

/**
 * Converts between Alexa custom-skill JSON and the driver contract. Shared by
 * the simulator and the real skill endpoint, so tests of one cover the other.
 *
 * The skill uses one catch-all intent with a single free-text slot so the
 * user's whole sentence reaches our parser; Alexa's built-in yes/no/stop/help
 * intents map to plain words.
 */
object AlexaAdapter {
    const val DRIVER_ID = "alexa"
    const val CATCH_ALL_INTENT = "CatchAllIntent"
    const val QUERY_SLOT = "query"

    fun parse(envelope: JsonObject): AlexaInbound {
        val sessionId = envelope["session"]?.jsonObject?.get("sessionId")?.jsonPrimitive?.content ?: "alexa-unknown"
        val request = envelope["request"]?.jsonObject ?: return AlexaInbound.Unsupported(sessionId, "missing")
        val type = request["type"]?.jsonPrimitive?.content ?: "missing"
        val locale = request["locale"]?.jsonPrimitive?.content ?: "en-US"
        return when (type) {
            "LaunchRequest" -> AlexaInbound.Launch(sessionId)
            "SessionEndedRequest" -> AlexaInbound.SessionEnded(sessionId)
            "IntentRequest" -> {
                val intent = request["intent"]?.jsonObject
                val text = when (intent?.get("name")?.jsonPrimitive?.content) {
                    "AMAZON.YesIntent" -> "yes"
                    "AMAZON.NoIntent" -> "no"
                    "AMAZON.StopIntent", "AMAZON.CancelIntent" -> "stop"
                    "AMAZON.HelpIntent" -> "help"
                    CATCH_ALL_INTENT -> intent["slots"]?.jsonObject?.get(QUERY_SLOT)?.jsonObject
                        ?.get("value")?.jsonPrimitive?.content.orEmpty()
                    else -> ""
                }
                AlexaInbound.Utterance(DriverRequest(text, DRIVER_ID, sessionId, locale))
            }
            else -> AlexaInbound.Unsupported(sessionId, type)
        }
    }

    fun respond(reply: DriverReply, rules: AlexaRules): JsonObject {
        val speech = fitSpeech(reply.text, rules.maxSpeechChars)
        return buildJsonObject {
            put("version", "1.0")
            putJsonObject("response") {
                if (speech.isNotEmpty()) {
                    putJsonObject("outputSpeech") {
                        put("type", "PlainText")
                        put("text", speech)
                    }
                }
                if (reply.expectsReply) {
                    putJsonObject("reprompt") {
                        putJsonObject("outputSpeech") {
                            put("type", "PlainText")
                            put("text", repromptFor(reply, speech))
                        }
                    }
                }
                put("shouldEndSession", !reply.expectsReply)
            }
        }
    }

    /** The spoken text of a response built by [respond], for tests and the console. */
    fun speechOf(response: JsonObject): String =
        response["response"]?.jsonObject?.get("outputSpeech")?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()

    fun endsSession(response: JsonObject): Boolean =
        response["response"]?.jsonObject?.get("shouldEndSession")?.jsonPrimitive?.content == "true"

    private fun repromptFor(reply: DriverReply, speech: String): String = when (reply) {
        is DriverReply.AskConfirm -> "Please say yes or no. $speech"
        is DriverReply.Progress -> "Say anything to hear the result."
        else -> speech
    }

    /** Cuts at a word boundary rather than mid-word when over the limit. */
    internal fun fitSpeech(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max - 1).substringBeforeLast(' ', text.take(max - 1))
        return "$cut…"
    }
}

/**
 * The skill's request handler. The simulator calls it in-process; the real
 * skill endpoint (hosted next to Elastic Core) will call the same function
 * from its HTTPS route.
 *
 * Slow runs: if the conductor misses [AlexaRules.responseBudgetMs], the user
 * hears a progress reply and gets the result on their next turn. [scope]
 * owns the background work and must outlive single requests.
 */
class AlexaSkillHandler(
    private val conductor: Conductor,
    private val scope: CoroutineScope,
    private val rules: AlexaRules = AlexaRules(),
) {
    private val inFlight = mutableMapOf<String, Deferred<DriverReply>>()

    suspend fun handle(envelope: JsonObject): JsonObject {
        val reply = when (val inbound = AlexaAdapter.parse(envelope)) {
            is AlexaInbound.Launch -> DriverReply.Ask(WELCOME)
            is AlexaInbound.SessionEnded -> {
                inFlight.remove(inbound.sessionId)
                conductor.endSession(inbound.sessionId)
                DriverReply.End("")
            }
            is AlexaInbound.Unsupported -> DriverReply.Say(Conductor.SORRY)
            is AlexaInbound.Utterance -> answer(inbound.request)
        }
        return AlexaAdapter.respond(reply, rules)
    }

    private suspend fun answer(request: DriverRequest): DriverReply {
        inFlight[request.sessionId]?.let { pending ->
            if (!pending.isCompleted) return DriverReply.Progress(STILL_WORKING)
            inFlight.remove(request.sessionId)
            return pending.await()
        }
        val work = scope.async { conductor.handle(request) }
        withTimeoutOrNull(rules.responseBudgetMs) { work.await() }?.let { return it }
        inFlight[request.sessionId] = work
        return DriverReply.Progress(STILL_WORKING)
    }

    companion object {
        const val WELCOME = "Hi. What would you like to do?"
        const val STILL_WORKING = "I'm still working on that. Say anything in a moment to hear the result."
    }
}

/**
 * Stand-in for Alexa until we have access. It builds the JSON envelopes Alexa
 * would send after its own speech recognition, runs them through
 * [AlexaSkillHandler] exactly as the real skill would, and returns Alexa
 * response JSON. Swap for real Alexa = point the skill endpoint at the same
 * handler; nothing above the transport changes.
 */
class SimulatedAlexaDriver(
    private val handler: AlexaSkillHandler,
    private val sessionId: String = "amzn1.echo-api.session.simulated",
    private val userId: String = "amzn1.ask.account.simulated",
) : Driver {
    override val id = AlexaAdapter.DRIVER_ID
    override val capabilities = DriverCapabilities(
        canListen = true, canSpeak = true, hasScreen = false, canReplyLater = true, maxSpeechChars = AlexaRules().maxSpeechChars,
    )

    private var requestCount = 0
    private var started = false

    /** What the user says, routed like Alexa would: yes/no/stop/help to built-ins, the rest to the catch-all. */
    suspend fun say(text: String): JsonObject = send(
        when (text.trim().lowercase().trimEnd('.', '!', '?')) {
            "yes" -> intentRequest("AMAZON.YesIntent")
            "no" -> intentRequest("AMAZON.NoIntent")
            "stop" -> intentRequest("AMAZON.StopIntent")
            "cancel" -> intentRequest("AMAZON.CancelIntent")
            "help" -> intentRequest("AMAZON.HelpIntent")
            else -> intentRequest(AlexaAdapter.CATCH_ALL_INTENT, query = text)
        },
    )

    suspend fun launch(): JsonObject = send(request("LaunchRequest"))

    suspend fun endSession(): JsonObject = send(request("SessionEndedRequest"))

    suspend fun send(envelope: JsonObject): JsonObject = handler.handle(envelope)

    private fun intentRequest(intentName: String, query: String? = null): JsonObject =
        request("IntentRequest") {
            putJsonObject("intent") {
                put("name", intentName)
                put("confirmationStatus", "NONE")
                if (query != null) {
                    putJsonObject("slots") {
                        putJsonObject(AlexaAdapter.QUERY_SLOT) {
                            put("name", AlexaAdapter.QUERY_SLOT)
                            put("value", query)
                        }
                    }
                }
            }
        }

    private fun request(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val isNew = !started
        started = true
        return buildJsonObject {
            put("version", "1.0")
            putJsonObject("session") {
                put("new", isNew)
                put("sessionId", sessionId)
                putJsonObject("user") { put("userId", userId) }
            }
            putJsonObject("request") {
                put("type", type)
                put("requestId", "amzn1.echo-api.request.simulated-${++requestCount}")
                put("locale", "en-US")
                body()
            }
        }
    }
}
