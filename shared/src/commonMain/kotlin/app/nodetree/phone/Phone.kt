package app.nodetree.phone

import app.nodetree.demo.DemoCatalog
import app.nodetree.demo.FakeExecutor
import app.nodetree.driver.Conductor
import app.nodetree.model.CapabilityNode
import app.nodetree.model.Platform
import app.nodetree.plan.RuleBasedIntentParser
import app.nodetree.run.ExecResult
import app.nodetree.run.Executor
import app.nodetree.run.RoutingExecutor
import app.nodetree.run.TreeEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ContactNumber(val label: String, val number: String, val isMobile: Boolean)

data class Contact(val displayName: String, val numbers: List<ContactNumber>)

/** What a platform contacts lookup returned. A plain class so Swift can build it. */
data class ContactsResult(
    val contacts: List<Contact>,
    val permissionDenied: Boolean,
    val error: String?,
)

data class DialOutcome(val opened: Boolean, val error: String?)

/**
 * The phone's address book. Android implements it with ContactsContract, iOS
 * with CNContactStore. Callback-based so Swift can implement it directly;
 * [PhoneExecutor] adapts it to coroutines. Return candidates loosely (e.g. a
 * name "contains" query); [ContactMatcher] decides who was meant.
 */
interface ContactsSource {
    fun search(name: String, callback: (ContactsResult) -> Unit)
}

/**
 * Opens the system dialer with a number filled in. It must never place the
 * call itself: Android uses ACTION_DIAL, iOS a tel: URL (iOS asks first).
 */
interface Dialer {
    fun openDialer(number: String, callback: (DialOutcome) -> Unit)
}

sealed interface MatchResult {
    data class One(val contact: Contact) : MatchResult
    data class Ambiguous(val candidates: List<Contact>) : MatchResult
    data object None : MatchResult
}

/**
 * Decides which contact a spoken name means. Tiers, best first: exact full
 * name, exact first name, full name starting with the words said. A match
 * found only by "contains" is never used: a wrong call is worse than asking.
 */
object ContactMatcher {
    fun match(spoken: String, candidates: List<Contact>): MatchResult {
        val wanted = normalize(spoken)
        if (wanted.isEmpty()) return MatchResult.None
        val withNumbers = candidates.filter { it.numbers.isNotEmpty() }
        val tiers = listOf<(String) -> Boolean>(
            { it == wanted },
            { it.substringBefore(' ') == wanted },
            { it.startsWith("$wanted ") },
        )
        for (tier in tiers) {
            val hits = withNumbers.filter { tier(normalize(it.displayName)) }.distinctBy { it.displayName }
            when (hits.size) {
                0 -> continue
                1 -> return MatchResult.One(hits.single())
                else -> return MatchResult.Ambiguous(hits)
            }
        }
        return MatchResult.None
    }

    /** Mobile first, then the first number listed. */
    fun bestNumber(contact: Contact): ContactNumber =
        contact.numbers.firstOrNull { it.isMobile } ?: contact.numbers.first()

    internal fun normalize(name: String): String =
        name.lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .dropWhile { it == "my" }
            .joinToString(" ")
}

/** Real phone capabilities: `contacts.lookup` and `phone.dial`. */
class PhoneExecutor(
    private val contacts: ContactsSource,
    private val dialer: Dialer,
) : Executor {
    override suspend fun execute(capability: CapabilityNode, args: Map<String, String>): ExecResult =
        when (capability.id) {
            "contacts.lookup" -> lookup(args["name"].orEmpty())
            "phone.dial" -> dial(args.getValue("number"), args["name"].orEmpty())
            else -> ExecResult.Failure("phone_executor_cannot_run_${capability.id}", "I can't do that yet.")
        }

    private suspend fun lookup(name: String): ExecResult {
        val result = suspendCancellableCoroutine { cont -> contacts.search(name) { cont.resume(it) } }
        if (result.permissionDenied) {
            return ExecResult.Failure(
                "contacts_permission_denied",
                "I need permission to see your contacts before I can call someone. You can allow it in Settings.",
            )
        }
        result.error?.let { return ExecResult.Failure("contacts_error: $it", "I couldn't read your contacts just now.") }

        return when (val match = ContactMatcher.match(name, result.contacts)) {
            MatchResult.None -> ExecResult.Failure("contact_not_found", "I couldn't find $name in your contacts.")
            is MatchResult.Ambiguous -> {
                val names = match.candidates.take(3).map { it.displayName }
                ExecResult.Failure(
                    "contact_ambiguous",
                    "I found ${match.candidates.size} people called $name: ${names.joinToString(", ")}. Please say the full name.",
                )
            }
            is MatchResult.One -> {
                val number = ContactMatcher.bestNumber(match.contact)
                ExecResult.Success(
                    output = mapOf("displayName" to match.contact.displayName, "number" to number.number),
                    evidence = "Matched contact ${match.contact.displayName} (${number.label})",
                )
            }
        }
    }

    private suspend fun dial(number: String, name: String): ExecResult {
        val outcome = suspendCancellableCoroutine { cont -> dialer.openDialer(number) { cont.resume(it) } }
        if (!outcome.opened) {
            return ExecResult.Failure("dialer_error: ${outcome.error}", "I couldn't open the dialer.")
        }
        return ExecResult.Success(
            output = mapOf("spoken" to "Opening the dialer for $name. Tap Call when you're ready."),
            evidence = "Dialer opened with $number",
        )
    }
}

/**
 * The app's conductor: real phone (contacts, dialer) through [PhoneExecutor],
 * calendar still demo until a real MCP server is connected in slice 2.
 */
fun phoneConductor(
    platform: Platform,
    contacts: ContactsSource,
    dialer: Dialer,
    onEvent: (TreeEvent) -> Unit = {},
): Conductor = Conductor(
    catalog = DemoCatalog.catalog(),
    recipes = DemoCatalog.recipeStore(),
    parser = RuleBasedIntentParser(),
    executor = RoutingExecutor(
        phone = PhoneExecutor(contacts, dialer),
        cloud = FakeExecutor(),
    ),
    platform = platform,
    onEvent = onEvent,
)
