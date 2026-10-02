package app.nodetree.demo

import app.nodetree.driver.Conductor
import app.nodetree.model.AppNode
import app.nodetree.model.CapabilityNode
import app.nodetree.model.Catalog
import app.nodetree.model.Effect
import app.nodetree.model.Maturity
import app.nodetree.model.Platform
import app.nodetree.model.Recipe
import app.nodetree.model.RecipeStep
import app.nodetree.model.RecipeStore
import app.nodetree.model.Runs
import app.nodetree.plan.RuleBasedIntentParser
import app.nodetree.run.ExecResult
import app.nodetree.run.Executor
import app.nodetree.run.TreeEvent
import kotlinx.coroutines.delay

/**
 * The skeleton's catalog: the phone (contacts, dialer) and a demo calendar.
 * Phone capabilities are LIVE once the platform executors land in slice 1;
 * the calendar stays DEMO until a real MCP server is connected in slice 2.
 */
object DemoCatalog {
    private val everywhere = setOf(Platform.ANDROID, Platform.IOS, Platform.JVM)

    val apps = listOf(
        AppNode("phone", "Phone", Runs.PHONE, Maturity.LIVE),
        AppNode("calendar", "Calendar", Runs.CLOUD, Maturity.DEMO),
    )

    val capabilities = listOf(
        CapabilityNode(
            id = "contacts.lookup", appId = "phone", name = "Find contact",
            description = "Finds a person in your contacts",
            inputs = mapOf("name" to "PersonName"),
            outputs = mapOf("displayName" to "PersonName", "number" to "PhoneNumber"),
            effect = Effect.READ, runs = Runs.PHONE, maturity = Maturity.LIVE, platforms = everywhere,
        ),
        CapabilityNode(
            id = "phone.dial", appId = "phone", name = "Open dialer",
            description = "Opens the dialer with the number filled in; you tap Call",
            inputs = mapOf("number" to "PhoneNumber", "name" to "PersonName"),
            effect = Effect.WRITE, runs = Runs.PHONE, maturity = Maturity.LIVE, platforms = everywhere,
            confirmPrompt = "Call {{name}}?",
        ),
        CapabilityNode(
            id = "calendar.list_today", appId = "calendar", name = "Read today's calendar",
            description = "Lists today's events",
            outputs = mapOf("events" to "Event[]"),
            effect = Effect.READ, runs = Runs.CLOUD, maturity = Maturity.DEMO, platforms = everywhere,
        ),
    )

    val recipes = listOf(
        Recipe(
            recipeId = "call_person", intentFamily = RuleBasedIntentParser.CALL_PERSON,
            steps = listOf(
                RecipeStep("lookup", "find", "contacts.lookup", mapOf("name" to "{{intent.person}}")),
                RecipeStep(
                    "dial", "dial", "phone.dial",
                    mapOf("number" to "{{steps.lookup.number}}", "name" to "{{steps.lookup.displayName}}"),
                    dependsOn = listOf("lookup"),
                ),
            ),
        ),
        Recipe(
            recipeId = "agenda_today", intentFamily = RuleBasedIntentParser.AGENDA_TODAY,
            steps = listOf(RecipeStep("list", "read", "calendar.list_today")),
        ),
    )

    fun catalog() = Catalog(apps, capabilities)

    fun recipeStore() = RecipeStore(recipes)
}

/**
 * Fake phone and calendar for the console, tests and early app builds.
 * Every result is flagged as demo data so the views and narration say so.
 */
class FakeExecutor(
    private val contacts: Map<String, String> = DEFAULT_CONTACTS,
    private val delayMs: Long = 0,
) : Executor {
    /** Numbers the fake dialer opened, in order. Tests assert on this. */
    val dialed = mutableListOf<String>()

    override suspend fun execute(capability: CapabilityNode, args: Map<String, String>): ExecResult {
        if (delayMs > 0) delay(delayMs)
        return when (capability.id) {
            "contacts.lookup" -> {
                val wanted = args["name"].orEmpty()
                val match = contacts.entries.firstOrNull { it.key.equals(wanted, ignoreCase = true) }
                if (match == null) {
                    ExecResult.Failure("contact_not_found", "I couldn't find $wanted in your contacts.")
                } else {
                    ExecResult.Success(
                        output = mapOf("displayName" to match.key, "number" to match.value),
                        evidence = "Matched contact ${match.key} (fake contacts)",
                        usedDemoData = true,
                    )
                }
            }
            "phone.dial" -> {
                val number = args.getValue("number")
                dialed += number
                ExecResult.Success(
                    output = mapOf("spoken" to "Opening the dialer for ${args["name"]}. Tap Call when you're ready."),
                    evidence = "Dialer opened with $number (fake dialer, not a real call)",
                    usedDemoData = true,
                )
            }
            "calendar.list_today" -> ExecResult.Success(
                output = mapOf("spoken" to "You have two things today: the dentist at 10 AM, and a call with Sam at 3 PM."),
                evidence = "2 events from the demo calendar",
                usedDemoData = true,
            )
            else -> ExecResult.Failure("no_fake_for_${capability.id}", "I can't do that yet.")
        }
    }

    companion object {
        val DEFAULT_CONTACTS = mapOf("Mom" to "+1 555 0100", "Dad" to "+1 555 0101", "Sam" to "+1 555 0102")
    }
}

/** A conductor wired to the demo catalog and fake executor. */
fun demoConductor(
    platform: Platform = Platform.JVM,
    executor: Executor = FakeExecutor(),
    onEvent: (TreeEvent) -> Unit = {},
): Conductor = Conductor(
    catalog = DemoCatalog.catalog(),
    recipes = DemoCatalog.recipeStore(),
    parser = RuleBasedIntentParser(),
    executor = executor,
    platform = platform,
    onEvent = onEvent,
)
