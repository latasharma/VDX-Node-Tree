package app.nodetree.plan

import app.nodetree.model.Catalog
import app.nodetree.model.Platform
import app.nodetree.model.Recipe
import app.nodetree.model.RecipeStore

enum class PlanSource { RECIPE, SEARCH }

sealed interface PlanResult {
    data class Planned(val recipe: Recipe, val source: PlanSource) : PlanResult

    /** A plan exists but can't run here (not on this platform, app not connected). */
    data class Unavailable(val reason: String) : PlanResult

    /** No recipe covers the request. Slice 3 adds graph search before giving up. */
    data object NoPlan : PlanResult
}

/** Recipe first: a known recipe runs with no model call (brief principle 5). */
class Planner(
    private val catalog: Catalog,
    private val recipes: RecipeStore,
    private val platform: Platform,
) {
    fun plan(intent: Intent): PlanResult {
        val recipe = recipes.forIntent(intent.family) ?: return PlanResult.NoPlan
        for (step in recipe.steps) {
            val cap = catalog.capability(step.capabilityId)
                ?: return PlanResult.Unavailable("I don't know how to do part of that yet.")
            catalog.unavailableReason(cap, platform)?.let { return PlanResult.Unavailable(it) }
        }
        return PlanResult.Planned(recipe, PlanSource.RECIPE)
    }
}
