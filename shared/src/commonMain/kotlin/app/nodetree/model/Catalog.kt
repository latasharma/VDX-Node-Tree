package app.nodetree.model

/** Where a capability executes. Cloud nodes go through Elastic Core. */
enum class Runs { PHONE, CLOUD }

/** Whether a node is backed by a real integration. Views must show this. */
enum class Maturity { LIVE, DEMO, STUB }

/** What a capability does to the world. Anything but READ is confirmed first. */
enum class Effect { READ, WRITE, IRREVERSIBLE }

/** Where a capability has an implementation. JVM = the developer console. */
enum class Platform { ANDROID, IOS, JVM }

enum class AuthState { NOT_NEEDED, CONNECTED, NEEDS_LINK, EXPIRED }

/** A source of capabilities: an MCP server, a website, or the phone itself. */
data class AppNode(
    val id: String,
    val name: String,
    val runs: Runs,
    val maturity: Maturity,
    val auth: AuthState = AuthState.NOT_NEEDED,
)

/**
 * One operation an app can perform. Mirrors vedaxi-elastic's `Capability`
 * (id, name, description, inputs, outputs, provider) and adds the signals the
 * node tree shows on every node.
 *
 * [inputs] and [outputs] map a parameter name to its semantic type
 * (PersonName, PhoneNumber, Event...). Slice 3 derives "can feed" edges from
 * matching types.
 */
data class CapabilityNode(
    val id: String,
    val appId: String,
    val name: String,
    val description: String,
    val inputs: Map<String, String> = emptyMap(),
    val outputs: Map<String, String> = emptyMap(),
    val effect: Effect,
    val runs: Runs,
    val maturity: Maturity,
    val platforms: Set<Platform>,
    /** Spoken question before a non-read step, e.g. "Call {{name}}?" over the step's args. */
    val confirmPrompt: String? = null,
    val historicalSuccess: Double? = null,
) {
    val needsConfirmation: Boolean get() = effect != Effect.READ
}

/** The catalog tree: Phone -> Apps -> Capabilities. */
class Catalog(appList: List<AppNode>, capabilityList: List<CapabilityNode>) {
    val apps: Map<String, AppNode> = appList.associateBy { it.id }
    val capabilities: Map<String, CapabilityNode> = capabilityList.associateBy { it.id }

    init {
        require(apps.size == appList.size) { "Duplicate app id" }
        require(capabilities.size == capabilityList.size) { "Duplicate capability id" }
        capabilityList.forEach { cap ->
            require(cap.appId in apps) { "Capability ${cap.id} names unknown app ${cap.appId}" }
        }
    }

    fun capability(id: String): CapabilityNode? = capabilities[id]

    fun app(id: String): AppNode? = apps[id]

    /** The "contains" edges: an app's capabilities, in catalog order. */
    fun capabilitiesOf(appId: String): List<CapabilityNode> =
        capabilities.values.filter { it.appId == appId }

    /** Why [cap] can't run here, or null if it can. */
    fun unavailableReason(cap: CapabilityNode, platform: Platform): String? {
        val app = apps.getValue(cap.appId)
        return when {
            platform !in cap.platforms -> "${cap.name} isn't available on this phone yet."
            app.auth == AuthState.NEEDS_LINK -> "${app.name} isn't connected yet."
            app.auth == AuthState.EXPIRED -> "${app.name} needs you to sign in again."
            else -> null
        }
    }
}
