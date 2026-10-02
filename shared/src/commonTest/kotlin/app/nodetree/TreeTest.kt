package app.nodetree

import app.nodetree.demo.DemoCatalog
import app.nodetree.demo.FakeExecutor
import app.nodetree.demo.demoConductor
import app.nodetree.driver.CallbackConductor
import app.nodetree.driver.ReplyView
import app.nodetree.model.AppNode
import app.nodetree.model.AuthState
import app.nodetree.model.Catalog
import app.nodetree.model.Maturity
import app.nodetree.model.Platform
import app.nodetree.model.Recipe
import app.nodetree.model.RecipeStep
import app.nodetree.model.RecipeStore
import app.nodetree.model.Runs
import app.nodetree.narrate.Outline
import app.nodetree.plan.Intent
import app.nodetree.plan.ParseResult
import app.nodetree.plan.PlanResult
import app.nodetree.plan.PlanSource
import app.nodetree.plan.Planner
import app.nodetree.plan.RuleBasedIntentParser
import app.nodetree.run.RunOutcome
import app.nodetree.run.RunSession
import app.nodetree.run.RunStatus
import app.nodetree.run.StepState
import app.nodetree.run.TreeEvent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecipeTest {
    @Test
    fun stepsRunInDependencyOrder() {
        val recipe = Recipe(
            "r", "f",
            listOf(
                RecipeStep("c", "a", "x", dependsOn = listOf("b")),
                RecipeStep("a", "a", "x"),
                RecipeStep("b", "a", "x", dependsOn = listOf("a")),
            ),
        )
        assertEquals(listOf("a", "b", "c"), recipe.topologicalOrder().map { it.stepId })
    }

    @Test
    fun cyclesAndUnknownDependenciesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            Recipe("r", "f", listOf(RecipeStep("a", "a", "x", dependsOn = listOf("b")), RecipeStep("b", "a", "x", dependsOn = listOf("a"))))
        }
        assertFailsWith<IllegalArgumentException> {
            Recipe("r", "f", listOf(RecipeStep("a", "a", "x", dependsOn = listOf("missing"))))
        }
    }
}

class ParserTest {
    private val parser = RuleBasedIntentParser()

    @Test
    fun parsesCallsAndAgenda() {
        val call = assertIs<ParseResult.Parsed>(parser.parse("Please call Mom."))
        assertEquals("Mom", call.intent.slots["person"])
        assertIs<ParseResult.Parsed>(parser.parse("What's on my calendar today?"))
        assertIs<ParseResult.NeedsSlot>(parser.parse("call"))
        assertEquals(ParseResult.Unknown, parser.parse("um"))
        assertEquals(ParseResult.Unknown, parser.parse("   "))
    }
}

class PlannerTest {
    private val intent = Intent(RuleBasedIntentParser.AGENDA_TODAY, emptyMap(), "agenda", 1.0)

    @Test
    fun knownRequestUsesItsRecipe() {
        val plan = Planner(DemoCatalog.catalog(), DemoCatalog.recipeStore(), Platform.ANDROID).plan(intent)
        assertEquals(PlanSource.RECIPE, assertIs<PlanResult.Planned>(plan).source)
    }

    @Test
    fun disconnectedAppIsReportedNotRun() {
        val apps = DemoCatalog.apps.map { if (it.id == "calendar") it.copy(auth = AuthState.NEEDS_LINK) else it }
        val plan = Planner(Catalog(apps, DemoCatalog.capabilities), DemoCatalog.recipeStore(), Platform.IOS).plan(intent)
        assertTrue("isn't connected" in assertIs<PlanResult.Unavailable>(plan).reason)
    }

    @Test
    fun capabilityMissingOnThisPlatformIsReported() {
        val caps = DemoCatalog.capabilities.map { if (it.id == "calendar.list_today") it.copy(platforms = setOf(Platform.ANDROID)) else it }
        val plan = Planner(Catalog(DemoCatalog.apps, caps), DemoCatalog.recipeStore(), Platform.IOS).plan(intent)
        assertIs<PlanResult.Unavailable>(plan)
    }

    @Test
    fun unknownFamilyHasNoPlan() {
        val plan = Planner(DemoCatalog.catalog(), RecipeStore(emptyList()), Platform.JVM).plan(intent)
        assertEquals(PlanResult.NoPlan, plan)
    }

    @Test
    fun catalogRejectsCapabilitiesOfUnknownApps() {
        assertFailsWith<IllegalArgumentException> {
            Catalog(listOf(AppNode("a", "A", Runs.PHONE, Maturity.LIVE)), DemoCatalog.capabilities)
        }
    }
}

class RunSessionTest {
    private fun session(phone: FakeExecutor, events: MutableList<TreeEvent>) = RunSession(
        catalog = DemoCatalog.catalog(),
        recipe = DemoCatalog.recipes.first { it.recipeId == "call_person" },
        intent = Intent(RuleBasedIntentParser.CALL_PERSON, mapOf("person" to "mom"), "call mom", 1.0),
        runId = "run-1",
        source = PlanSource.RECIPE,
        executor = phone,
        onEvent = { events += it },
    )

    @Test
    fun treeStopsAtTheWriteStepWithArgumentsFilledIn() = runTest {
        val events = mutableListOf<TreeEvent>()
        val run = session(FakeExecutor(), events)
        val outcome = assertIs<RunOutcome.NeedsConfirmation>(run.start())
        assertEquals("Call Mom?", outcome.question)
        assertEquals(RunStatus.WAITING_FOR_CONFIRMATION, outcome.tree.status)
        assertEquals(StepState.DONE, outcome.tree.step("lookup").state)
        val dial = outcome.tree.step("dial")
        assertEquals(StepState.NEEDS_CONFIRMATION, dial.state)
        assertEquals("+1 555 0100", dial.args["number"])
        assertEquals(
            listOf("recipe.started", "recipe.step.started", "tool.called", "tool.completed", "recipe.step.started", "confirmation.requested"),
            events.map { it.name },
        )
    }

    @Test
    fun approvalCompletesAndRefusalSkips() = runTest {
        val phone = FakeExecutor()
        val approved = session(phone, mutableListOf())
        approved.start()
        val done = assertIs<RunOutcome.Completed>(approved.confirm(true))
        assertEquals(StepState.DONE, done.tree.step("dial").state)
        assertNotNull(done.tree.step("dial").evidence)

        val refused = session(phone, mutableListOf())
        refused.start()
        val cancelled = assertIs<RunOutcome.Cancelled>(refused.confirm(false))
        assertEquals(StepState.CANCELLED, cancelled.tree.step("dial").state)
        assertEquals(1, phone.dialed.size)
    }

    @Test
    fun failedLookupSkipsTheDial() = runTest {
        val phone = FakeExecutor(contacts = emptyMap())
        val failed = assertIs<RunOutcome.Failed>(session(phone, mutableListOf()).start())
        assertEquals(StepState.FAILED, failed.tree.step("lookup").state)
        assertEquals(StepState.SKIPPED, failed.tree.step("dial").state)
        assertTrue(phone.dialed.isEmpty())
    }
}

class OutlineTest {
    @Test
    fun runOutlineNestsRequestPlanStepsAndEvidence() = runTest {
        val conductor = demoConductor()
        conductor.handle(app.nodetree.driver.DriverRequest("call Mom", "console", "s"))
        val lines = Outline.ofRun(assertNotNull(conductor.latestTree.value))
        assertEquals(listOf(0, 1, 2, 3, 2), lines.map { it.depth })
        assertTrue("needs your OK" in lines[4].text, lines[4].text)
        assertTrue("Used demo data" in lines[2].accessibilityLabel, lines[2].accessibilityLabel)
        assertTrue(lines[2].text.endsWith("done · demo data"), lines[2].text)
    }

    @Test
    fun followUpAnswerKeepsTheOriginalRequest() = runTest {
        val conductor = demoConductor()
        conductor.handle(app.nodetree.driver.DriverRequest("call", "console", "s"))
        conductor.handle(app.nodetree.driver.DriverRequest("Dad", "console", "s"))
        assertEquals("call Dad", assertNotNull(conductor.latestTree.value).requestText)
    }

    @Test
    fun catalogOutlineLabelsDemoAppsAndConfirmations() {
        val lines = Outline.ofCatalog(DemoCatalog.catalog(), Platform.IOS)
        assertTrue(lines.any { it.depth == 0 && "Calendar · demo" == it.text })
        assertTrue(lines.first { it.nodeId == "cap:phone.dial" }.accessibilityLabel.endsWith("asks before acting"))
    }
}

class CallbackConductorTest {
    @Test
    fun deliversReplyAndOutlineToTheCallback() = runTest {
        val bridge = CallbackConductor(demoConductor(), this, "ios-voice", "s")
        var view: ReplyView? = null
        bridge.send("call Mom") { view = it }
        advanceUntilIdle()
        val got = assertNotNull(view)
        assertTrue(got.isConfirmation)
        assertTrue(got.outline.first().startsWith("“"))
    }
}
