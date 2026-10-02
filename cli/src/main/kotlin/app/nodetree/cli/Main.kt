package app.nodetree.cli

import app.nodetree.demo.DemoCatalog
import app.nodetree.demo.demoConductor
import app.nodetree.driver.ConsoleDriver
import app.nodetree.driver.alexa.AlexaAdapter
import app.nodetree.driver.alexa.AlexaSkillHandler
import app.nodetree.driver.alexa.SimulatedAlexaDriver
import app.nodetree.model.Platform
import app.nodetree.narrate.Outline
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Developer console. Talk to the node tree by typing.
 *
 *   ./gradlew :cli:run -q --console=plain                     typed driver
 *   ./gradlew :cli:run -q --console=plain --args="--alexa"    simulated Alexa, prints the JSON
 */
fun main(args: Array<String>) = runBlocking {
    val alexaMode = "--alexa" in args
    val events = "--events" in args
    val conductor = demoConductor(Platform.JVM, onEvent = { if (events) println("    event: ${it.name}") })

    println("Node Tree console (${if (alexaMode) "Simulated Alexa" else "Console"} driver). Try: call Mom, what's on my calendar today, help. Empty line quits.")
    println("Catalog:")
    Outline.ofCatalog(DemoCatalog.catalog(), Platform.JVM).forEach { println("  " + "  ".repeat(it.depth) + it.text) }

    coroutineScope {
        val console = ConsoleDriver(conductor)
        val alexa = SimulatedAlexaDriver(AlexaSkillHandler(conductor, this))
        val pretty = Json { prettyPrint = true }

        while (true) {
            print("\nyou> ")
            val line = readlnOrNull()?.takeIf { it.isNotBlank() } ?: break
            if (alexaMode) {
                val response: JsonObject = alexa.say(line)
                println(pretty.encodeToString(JsonObject.serializer(), response))
                println("alexa> ${AlexaAdapter.speechOf(response)}")
            } else {
                println("app> ${console.send(line).text}")
            }
            conductor.latestTree.value?.let { tree ->
                println("tree:")
                Outline.ofRun(tree).forEach { println("  " + "  ".repeat(it.depth) + it.text) }
            }
        }
        coroutineContext.cancelChildren()
    }
}

