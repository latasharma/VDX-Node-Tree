package app.nodetree.driver

import app.nodetree.demo.demoConductor
import app.nodetree.model.Platform
import app.nodetree.narrate.Outline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** What a native screen needs after each turn. */
data class ReplyView(
    val text: String,
    val expectsReply: Boolean,
    val isConfirmation: Boolean,
    /** Outline lines, already indented two spaces per depth. */
    val outline: List<String>,
    val accessibilityLabels: List<String>,
)

/**
 * A callback API over [Conductor] for Swift, which can't call Kotlin suspend
 * functions as conveniently as Kotlin can. Android can use it too, or call
 * [Conductor.handle] from a coroutine directly.
 */
class CallbackConductor(
    private val conductor: Conductor,
    private val scope: CoroutineScope,
    private val driverId: String,
    private val sessionId: String,
) {
    fun send(text: String, onReply: (ReplyView) -> Unit) {
        scope.launch {
            val reply = conductor.handle(DriverRequest(text, driverId, sessionId))
            val lines = conductor.latestTree.value?.let { Outline.ofRun(it) }.orEmpty()
            onReply(
                ReplyView(
                    text = reply.text,
                    expectsReply = reply.expectsReply,
                    isConfirmation = reply is DriverReply.AskConfirm,
                    outline = lines.map { "  ".repeat(it.depth) + it.text },
                    accessibilityLabels = lines.map { it.accessibilityLabel },
                ),
            )
        }
    }
}

/** Demo wiring for the native apps until their real executors land (slice 1). */
fun createDemoCallbackConductor(platform: Platform, driverId: String): CallbackConductor =
    CallbackConductor(demoConductor(platform), MainScope(), driverId, sessionId = driverId)
