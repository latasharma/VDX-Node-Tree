package app.nodetree.driver

/**
 * The swap point for voice front ends. A driver turns its own input (typed
 * text, on-device speech, an Alexa JSON envelope) into a [DriverRequest] and
 * renders the [DriverReply]. Drivers never plan or call apps; the
 * [Conductor] does. Replacing the simulated Alexa with the real one changes
 * only the transport, not this contract.
 */
data class DriverRequest(
    val text: String,
    val driverId: String,
    val sessionId: String,
    val locale: String = "en-US",
)

sealed interface DriverReply {
    val text: String

    /** Whether the conversation stays open for the user's next words. */
    val expectsReply: Boolean

    /** A final answer. */
    data class Say(override val text: String) : DriverReply {
        override val expectsReply get() = false
    }

    /** A question needing a free answer ("Who should I call?"). */
    data class Ask(override val text: String) : DriverReply {
        override val expectsReply get() = true
    }

    /** A yes/no question guarding exactly one step that changes something. */
    data class AskConfirm(override val text: String) : DriverReply {
        override val expectsReply get() = true
    }

    /** Still working; the result comes on the user's next turn. */
    data class Progress(override val text: String) : DriverReply {
        override val expectsReply get() = true
    }

    /** The user stopped; close the conversation. */
    data class End(override val text: String) : DriverReply {
        override val expectsReply get() = false
    }
}

data class DriverCapabilities(
    val canListen: Boolean,
    val canSpeak: Boolean,
    val hasScreen: Boolean,
    val canReplyLater: Boolean,
    /** Longest reply the driver can speak; null = no limit. */
    val maxSpeechChars: Int? = null,
)

interface Driver {
    val id: String
    val capabilities: DriverCapabilities
}

/** Typed text in, reply out. Used by tests, CI and the developer console. */
class ConsoleDriver(
    private val conductor: Conductor,
    private val sessionId: String = "console",
) : Driver {
    override val id = "console"
    override val capabilities = DriverCapabilities(canListen = false, canSpeak = false, hasScreen = true, canReplyLater = false)

    suspend fun send(text: String): DriverReply = conductor.handle(DriverRequest(text, id, sessionId))
}
