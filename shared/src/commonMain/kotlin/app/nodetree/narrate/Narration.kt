package app.nodetree.narrate

import app.nodetree.model.Catalog
import app.nodetree.model.Maturity
import app.nodetree.model.Platform
import app.nodetree.model.Runs
import app.nodetree.run.RunTree
import app.nodetree.run.StepNode
import app.nodetree.run.StepState

/** Spoken words for a run. Every view of the tree reads from here or [Outline]. */
object Narrator {
    const val DEMO_NOTE = "I used demo data for this."

    fun completed(tree: RunTree): String {
        val spoken = tree.steps.lastOrNull { it.state == StepState.DONE }?.output?.get("spoken") ?: "Done."
        return if (tree.usedDemoData) "$spoken $DEMO_NOTE" else spoken
    }

    fun stepLine(step: StepNode, index: Int, total: Int): String =
        "Step ${index + 1} of $total, ${step.capability.name}: ${stateWords(step.state)}."

    fun stateWords(state: StepState): String = when (state) {
        StepState.WAITING -> "waiting"
        StepState.RUNNING -> "running"
        StepState.NEEDS_CONFIRMATION -> "needs your OK"
        StepState.DONE -> "done"
        StepState.FAILED -> "failed"
        StepState.SKIPPED -> "skipped"
        StepState.CANCELLED -> "cancelled"
    }

    fun whereWords(runs: Runs): String = if (runs == Runs.PHONE) "on your phone" else "in the cloud"

    fun maturityWords(maturity: Maturity): String = when (maturity) {
        Maturity.LIVE -> "live"
        Maturity.DEMO -> "demo"
        Maturity.STUB -> "not built yet"
    }
}

/**
 * One line of the accessible outline view. Both apps render these as a
 * nested list (depth = indent level) and give [accessibilityLabel] to
 * VoiceOver / TalkBack.
 */
data class OutlineLine(
    val depth: Int,
    val nodeId: String,
    val text: String,
    val accessibilityLabel: String,
)

object Outline {
    /** Request -> Plan -> Steps -> evidence. */
    fun ofRun(tree: RunTree): List<OutlineLine> = buildList {
        add(OutlineLine(0, "request", "“${tree.requestText}”", "Your request: ${tree.requestText}"))
        add(
            OutlineLine(
                1, "plan:${tree.planId}", "Plan: ${tree.planId} v${tree.planVersion}",
                "Plan ${tree.planId}, from a ${tree.planSource.name.lowercase()}, ${tree.steps.size} steps",
            ),
        )
        tree.steps.forEachIndexed { i, step ->
            val cap = step.capability
            val demo = when {
                cap.maturity != Maturity.LIVE -> " · ${Narrator.maturityWords(cap.maturity)}"
                step.usedDemoData -> " · demo data"
                else -> ""
            }
            add(
                OutlineLine(
                    2, "step:${step.stepId}",
                    "${i + 1}. ${cap.name} — ${Narrator.stateWords(step.state)}$demo",
                    Narrator.stepLine(step, i, tree.steps.size) +
                        " Runs ${Narrator.whereWords(cap.runs)}, ${Narrator.maturityWords(cap.maturity)}." +
                        if (step.usedDemoData) " Used demo data." else "",
                ),
            )
            step.evidence?.let { add(OutlineLine(3, "evidence:${step.stepId}", it, "Evidence: $it")) }
            step.error?.let { add(OutlineLine(3, "error:${step.stepId}", "Error: $it", "Error: $it")) }
        }
    }

    /** Phone -> Apps -> Capabilities, only what this platform can run. */
    fun ofCatalog(catalog: Catalog, platform: Platform): List<OutlineLine> = buildList {
        catalog.apps.values.forEach { app ->
            val caps = catalog.capabilitiesOf(app.id).filter { platform in it.platforms }
            if (caps.isEmpty()) return@forEach
            add(
                OutlineLine(
                    0, "app:${app.id}", "${app.name} · ${Narrator.maturityWords(app.maturity)}",
                    "${app.name}, runs ${Narrator.whereWords(app.runs)}, ${Narrator.maturityWords(app.maturity)}",
                ),
            )
            caps.forEach { cap ->
                val confirm = if (cap.needsConfirmation) ", asks before acting" else ""
                add(OutlineLine(1, "cap:${cap.id}", cap.name, "${cap.name}: ${cap.description}$confirm"))
            }
        }
    }
}
