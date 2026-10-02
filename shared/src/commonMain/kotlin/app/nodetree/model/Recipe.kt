package app.nodetree.model

enum class RecipeStatus { DRAFT, ACTIVE, DEPRECATED, RETIRED }

/**
 * One step of a recipe. Mirrors vedaxi-elastic's `RecipeStep`.
 *
 * [args] values may hold templates: `{{intent.<slot>}}` reads a slot of the
 * parsed request, `{{steps.<stepId>.<key>}}` reads an earlier step's output.
 */
data class RecipeStep(
    val stepId: String,
    val action: String,
    val capabilityId: String,
    val args: Map<String, String> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
)

/**
 * A known way to reach an outcome: steps forming an acyclic dependency graph.
 * Mirrors vedaxi-elastic's `Recipe` (the fields the app needs today).
 */
data class Recipe(
    val recipeId: String,
    val intentFamily: String,
    val steps: List<RecipeStep>,
    val version: String = "1.0.0",
    val status: RecipeStatus = RecipeStatus.ACTIVE,
) {
    init {
        val ids = steps.map { it.stepId }
        require(ids.toSet().size == ids.size) { "Recipe $recipeId has duplicate step ids" }
        steps.forEach { step ->
            step.dependsOn.forEach { dep ->
                require(dep in ids) { "Step ${step.stepId} depends on unknown step $dep" }
            }
        }
        topologicalOrder() // throws on a cycle
    }

    /** Steps in dependency order (Kahn's algorithm), ties kept in declaration order. */
    fun topologicalOrder(): List<RecipeStep> {
        val remaining = steps.associate { it.stepId to it.dependsOn.toMutableSet() }.toMutableMap()
        val ordered = mutableListOf<RecipeStep>()
        while (remaining.isNotEmpty()) {
            val ready = steps.firstOrNull { it.stepId in remaining && remaining.getValue(it.stepId).isEmpty() }
                ?: throw IllegalArgumentException("Recipe $recipeId has a dependency cycle")
            ordered += ready
            remaining.remove(ready.stepId)
            remaining.values.forEach { it.remove(ready.stepId) }
        }
        return ordered
    }
}

class RecipeStore(private val recipes: List<Recipe>) {
    /** The newest active recipe for an intent family, or null. */
    fun forIntent(family: String): Recipe? =
        recipes.lastOrNull { it.intentFamily == family && it.status == RecipeStatus.ACTIVE }

    val all: List<Recipe> get() = recipes
}
