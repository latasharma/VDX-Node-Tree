package app.nodetree.plan

/**
 * A parsed request. A slimmed-down vedaxi `IntentIR`: [family] plays the role
 * of `goal`, [slots] of `constraints`.
 */
data class Intent(
    val family: String,
    val slots: Map<String, String>,
    val sourceText: String,
    val confidence: Double,
)

sealed interface ParseResult {
    data class Parsed(val intent: Intent) : ParseResult

    /** The request is understood but a required slot is missing ("call" with no name). */
    data class NeedsSlot(val family: String, val slot: String, val question: String) : ParseResult

    data object Unknown : ParseResult
}

fun interface IntentParser {
    fun parse(text: String): ParseResult
}

/**
 * Deterministic parser for the skeleton's two intents. Known phrasings never
 * need a model call (brief principle 5). An LLM parser can replace this behind
 * the same interface.
 */
class RuleBasedIntentParser : IntentParser {
    private val callVerbs = listOf("call", "phone", "ring", "dial")
    private val agendaPhrases = listOf(
        "what's on my calendar", "whats on my calendar", "what is on my calendar",
        "my calendar today", "what do i have today", "my schedule today",
    )

    override fun parse(text: String): ParseResult {
        val cleaned = text.trim().trimEnd('.', '!', '?').trim()
        val lower = cleaned.lowercase().removePrefix("please ").trim()
        if (lower.isEmpty() || lower in FILLER) return ParseResult.Unknown

        val verb = callVerbs.firstOrNull { lower == it || lower.startsWith("$it ") }
        if (verb != null) {
            val person = cleaned.substring(cleaned.lowercase().indexOf(verb) + verb.length).trim()
            if (person.isEmpty()) {
                return ParseResult.NeedsSlot(CALL_PERSON, "person", "Who should I call?")
            }
            return ParseResult.Parsed(Intent(CALL_PERSON, mapOf("person" to person), text, 0.95))
        }

        if (agendaPhrases.any { lower.startsWith(it) }) {
            return ParseResult.Parsed(Intent(AGENDA_TODAY, emptyMap(), text, 0.9))
        }
        return ParseResult.Unknown
    }

    companion object {
        const val CALL_PERSON = "call_person"
        const val AGENDA_TODAY = "agenda_today"
        private val FILLER = setOf("um", "uh", "hmm", "er", "ah")
    }
}
