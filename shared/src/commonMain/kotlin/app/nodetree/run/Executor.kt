package app.nodetree.run

import app.nodetree.model.CapabilityNode
import app.nodetree.model.Runs

sealed interface ExecResult {
    /**
     * [output] feeds later steps; the key `spoken` (if present) is what the
     * driver says when this is the last step.
     */
    data class Success(
        val output: Map<String, String>,
        val evidence: String,
        val usedDemoData: Boolean = false,
    ) : ExecResult

    /** [userMessage] is safe to speak; [reason] is for logs and evidence. */
    data class Failure(val reason: String, val userMessage: String) : ExecResult
}

/** Carries out one capability. Phone executors are per platform; tests use fakes. */
fun interface Executor {
    suspend fun execute(capability: CapabilityNode, args: Map<String, String>): ExecResult
}

/** Sends phone capabilities to the phone executor and cloud ones to Elastic Core. */
class RoutingExecutor(private val phone: Executor, private val cloud: Executor) : Executor {
    override suspend fun execute(capability: CapabilityNode, args: Map<String, String>): ExecResult =
        when (capability.runs) {
            Runs.PHONE -> phone.execute(capability, args)
            Runs.CLOUD -> cloud.execute(capability, args)
        }
}
