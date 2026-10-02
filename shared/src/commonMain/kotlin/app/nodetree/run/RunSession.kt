package app.nodetree.run

import app.nodetree.model.Catalog
import app.nodetree.model.Recipe
import app.nodetree.plan.Intent
import app.nodetree.plan.PlanSource

sealed interface RunOutcome {
    val tree: RunTree

    data class Completed(override val tree: RunTree) : RunOutcome
    data class NeedsConfirmation(override val tree: RunTree, val stepId: String, val question: String) : RunOutcome
    data class Failed(override val tree: RunTree, val userMessage: String) : RunOutcome
    data class Cancelled(override val tree: RunTree) : RunOutcome
}

/**
 * Runs one plan step by step. Every step whose capability is not read-only
 * stops at [RunOutcome.NeedsConfirmation] and executes only after
 * [confirm] with `approved = true`. Nothing else can execute a write.
 */
class RunSession(
    catalog: Catalog,
    private val recipe: Recipe,
    intent: Intent,
    private val runId: String,
    source: PlanSource,
    private val executor: Executor,
    private val onEvent: (TreeEvent) -> Unit = {},
    private val onTree: (RunTree) -> Unit = {},
) {
    var tree: RunTree = RunTree(
        runId = runId,
        requestText = intent.sourceText,
        intent = intent,
        planId = recipe.recipeId,
        planVersion = recipe.version,
        planSource = source,
        steps = recipe.topologicalOrder().map { step ->
            StepNode(
                stepId = step.stepId,
                action = step.action,
                capability = catalog.capability(step.capabilityId)
                    ?: throw IllegalArgumentException("Unknown capability ${step.capabilityId}"),
                dependsOn = step.dependsOn,
            )
        },
    )
        private set(value) {
            field = value
            onTree(value)
        }

    suspend fun start(): RunOutcome {
        onEvent(TreeEvent.RecipeStarted(runId, recipe.recipeId))
        onTree(tree)
        return advance()
    }

    suspend fun confirm(approved: Boolean): RunOutcome {
        val pending = tree.steps.firstOrNull { it.state == StepState.NEEDS_CONFIRMATION }
            ?: throw IllegalStateException("No step is waiting for confirmation")
        if (!approved) {
            tree = tree.copy(
                steps = tree.steps.map {
                    when {
                        it.stepId == pending.stepId -> it.copy(state = StepState.CANCELLED)
                        it.state == StepState.WAITING -> it.copy(state = StepState.SKIPPED)
                        else -> it
                    }
                },
                status = RunStatus.CANCELLED,
            )
            onEvent(TreeEvent.Cancelled(runId, pending.stepId))
            return RunOutcome.Cancelled(tree)
        }
        tree = tree.copy(status = RunStatus.RUNNING)
        execute(pending.stepId, pending.args)?.let { return it }
        return advance()
    }

    private suspend fun advance(): RunOutcome {
        while (true) {
            val next = tree.steps.firstOrNull { it.state == StepState.WAITING } ?: break
            onEvent(TreeEvent.StepStarted(runId, next.stepId))
            val recipeStep = recipe.steps.first { it.stepId == next.stepId }
            val args = try {
                recipeStep.args.mapValues { (_, value) -> resolve(value) }
            } catch (e: IllegalStateException) {
                return fail(next.stepId, e.message ?: "unresolved argument", "I'm missing something I need for that.")
            }
            if (next.capability.needsConfirmation) {
                tree = tree.updateStep(next.stepId) { it.copy(state = StepState.NEEDS_CONFIRMATION, args = args) }
                    .copy(status = RunStatus.WAITING_FOR_CONFIRMATION)
                onEvent(TreeEvent.ConfirmationRequested(runId, next.stepId))
                return RunOutcome.NeedsConfirmation(tree, next.stepId, confirmQuestion(next, args))
            }
            execute(next.stepId, args)?.let { return it }
        }
        tree = tree.copy(status = RunStatus.COMPLETED)
        onEvent(TreeEvent.RecipeCompleted(runId, recipe.recipeId))
        return RunOutcome.Completed(tree)
    }

    /** Runs one step. Returns a terminal outcome on failure, null on success. */
    private suspend fun execute(stepId: String, args: Map<String, String>): RunOutcome? {
        val cap = tree.step(stepId).capability
        tree = tree.updateStep(stepId) { it.copy(state = StepState.RUNNING, args = args) }
        onEvent(TreeEvent.ToolCalled(runId, stepId, cap.id))
        return when (val result = executor.execute(cap, args)) {
            is ExecResult.Success -> {
                tree = tree.updateStep(stepId) {
                    it.copy(
                        state = StepState.DONE,
                        output = result.output,
                        evidence = result.evidence,
                        usedDemoData = result.usedDemoData,
                    )
                }
                onEvent(TreeEvent.ToolCompleted(runId, stepId, cap.id))
                null
            }
            is ExecResult.Failure -> fail(stepId, result.reason, result.userMessage)
        }
    }

    private fun fail(stepId: String, reason: String, userMessage: String): RunOutcome.Failed {
        tree = tree.copy(
            steps = tree.steps.map {
                when {
                    it.stepId == stepId -> it.copy(state = StepState.FAILED, error = reason)
                    it.state == StepState.WAITING -> it.copy(state = StepState.SKIPPED)
                    else -> it
                }
            },
            status = RunStatus.FAILED,
        )
        onEvent(TreeEvent.ExecutionFailed(runId, stepId, reason))
        return RunOutcome.Failed(tree, userMessage)
    }

    private fun confirmQuestion(step: StepNode, args: Map<String, String>): String =
        step.capability.confirmPrompt?.let { fill(it) { key -> args[key] } }
            ?: "Should I ${step.capability.name.lowercase()}?"

    /** Resolves `{{intent.x}}` and `{{steps.id.key}}` against the run so far. */
    private fun resolve(value: String): String = fill(value) { path ->
        val parts = path.split('.')
        when {
            parts.size == 2 && parts[0] == "intent" -> tree.intent.slots[parts[1]]
            parts.size == 3 && parts[0] == "steps" -> tree.steps.firstOrNull { it.stepId == parts[1] }?.output?.get(parts[2])
            else -> null
        }
    }

    private companion object {
        val TEMPLATE = Regex("""\{\{\s*([A-Za-z0-9_.]+)\s*\}\}""")

        fun fill(template: String, lookup: (String) -> String?): String =
            TEMPLATE.replace(template) { match ->
                val key = match.groupValues[1]
                lookup(key) ?: throw IllegalStateException("No value for {{$key}}")
            }
    }
}
