package app.nodetree.run

import app.nodetree.model.CapabilityNode
import app.nodetree.plan.Intent
import app.nodetree.plan.PlanSource

enum class StepState { WAITING, RUNNING, NEEDS_CONFIRMATION, DONE, FAILED, SKIPPED, CANCELLED }

enum class RunStatus { RUNNING, WAITING_FOR_CONFIRMATION, COMPLETED, FAILED, CANCELLED }

data class StepNode(
    val stepId: String,
    val action: String,
    val capability: CapabilityNode,
    val dependsOn: List<String>,
    val state: StepState = StepState.WAITING,
    val args: Map<String, String> = emptyMap(),
    val output: Map<String, String> = emptyMap(),
    val evidence: String? = null,
    val error: String? = null,
    /** True when the executor answered from fake or demo data. Views must say so. */
    val usedDemoData: Boolean = false,
)

/**
 * The run tree: Request -> Plan -> Steps -> Capability. Immutable; every
 * change produces a new tree so views can diff and narrate it.
 */
data class RunTree(
    val runId: String,
    val requestText: String,
    val intent: Intent,
    val planId: String,
    val planVersion: String,
    val planSource: PlanSource,
    val steps: List<StepNode>,
    val status: RunStatus = RunStatus.RUNNING,
) {
    fun step(id: String): StepNode = steps.first { it.stepId == id }

    fun updateStep(id: String, change: (StepNode) -> StepNode): RunTree =
        copy(steps = steps.map { if (it.stepId == id) change(it) else it })

    val usedDemoData: Boolean get() = steps.any { it.usedDemoData }
}

/** Run events, named like vedaxi-elastic's telemetry so evidence lines up. */
sealed class TreeEvent(val name: String) {
    abstract val runId: String

    data class RecipeStarted(override val runId: String, val recipeId: String) : TreeEvent("recipe.started")
    data class StepStarted(override val runId: String, val stepId: String) : TreeEvent("recipe.step.started")
    data class ToolCalled(override val runId: String, val stepId: String, val capabilityId: String) : TreeEvent("tool.called")
    data class ToolCompleted(override val runId: String, val stepId: String, val capabilityId: String) : TreeEvent("tool.completed")
    data class ConfirmationRequested(override val runId: String, val stepId: String) : TreeEvent("confirmation.requested")
    data class ExecutionFailed(override val runId: String, val stepId: String?, val reason: String) : TreeEvent("execution.failed")
    data class Cancelled(override val runId: String, val stepId: String?) : TreeEvent("execution.cancelled")
    data class RecipeCompleted(override val runId: String, val recipeId: String) : TreeEvent("recipe.completed")
}
