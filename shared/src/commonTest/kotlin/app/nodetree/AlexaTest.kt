package app.nodetree

import app.nodetree.demo.FakeExecutor
import app.nodetree.demo.demoConductor
import app.nodetree.driver.DriverReply
import app.nodetree.driver.alexa.AlexaAdapter
import app.nodetree.driver.alexa.AlexaInbound
import app.nodetree.driver.alexa.AlexaRules
import app.nodetree.driver.alexa.AlexaSkillHandler
import app.nodetree.driver.alexa.SimulatedAlexaDriver
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlexaAdapterTest {
    private fun envelope(request: String) = Json.parseToJsonElement(
        """{"version":"1.0","session":{"new":false,"sessionId":"s1","user":{"userId":"u1"}},"request":$request}""",
    ).jsonObject

    @Test
    fun catchAllSlotBecomesTheUtterance() {
        val inbound = AlexaAdapter.parse(
            envelope(
                """{"type":"IntentRequest","requestId":"r1","locale":"en-IN",
                   "intent":{"name":"CatchAllIntent","slots":{"query":{"name":"query","value":"call Mom"}}}}""",
            ),
        )
        assertIs<AlexaInbound.Utterance>(inbound)
        assertEquals("call Mom", inbound.request.text)
        assertEquals("s1", inbound.request.sessionId)
        assertEquals("en-IN", inbound.request.locale)
    }

    @Test
    fun builtInIntentsMapToPlainWords() {
        fun textOf(name: String) =
            (AlexaAdapter.parse(envelope("""{"type":"IntentRequest","intent":{"name":"$name"}}""")) as AlexaInbound.Utterance).request.text
        assertEquals("yes", textOf("AMAZON.YesIntent"))
        assertEquals("no", textOf("AMAZON.NoIntent"))
        assertEquals("stop", textOf("AMAZON.StopIntent"))
        assertEquals("stop", textOf("AMAZON.CancelIntent"))
        assertEquals("help", textOf("AMAZON.HelpIntent"))
    }

    @Test
    fun launchAndSessionEndAreRecognised() {
        assertIs<AlexaInbound.Launch>(AlexaAdapter.parse(envelope("""{"type":"LaunchRequest"}""")))
        assertIs<AlexaInbound.SessionEnded>(AlexaAdapter.parse(envelope("""{"type":"SessionEndedRequest"}""")))
    }

    @Test
    fun questionsKeepTheSessionOpenWithAReprompt() {
        val response = AlexaAdapter.respond(DriverReply.AskConfirm("Call Mom?"), AlexaRules())
        val body = response["response"]!!.jsonObject
        assertEquals("Call Mom?", AlexaAdapter.speechOf(response))
        assertFalse(AlexaAdapter.endsSession(response))
        val reprompt = body["reprompt"]!!.jsonObject["outputSpeech"]!!.jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("yes or no" in reprompt)
    }

    @Test
    fun finalAnswersEndTheSession() {
        val response = AlexaAdapter.respond(DriverReply.Say("Done."), AlexaRules())
        assertTrue(AlexaAdapter.endsSession(response))
        assertEquals(null, response["response"]!!.jsonObject["reprompt"])
    }

    @Test
    fun longSpeechIsCutAtAWordBoundary() {
        val response = AlexaAdapter.respond(DriverReply.Say("word ".repeat(50)), AlexaRules(maxSpeechChars = 30))
        val speech = AlexaAdapter.speechOf(response)
        assertTrue(speech.length <= 30, speech)
        assertTrue(speech.endsWith("word…"), speech)
    }
}

class AlexaSkillHandlerTest {
    @Test
    fun slowRunGetsAProgressReplyThenTheResultOnTheNextTurn() = runTest {
        val slowPhone = FakeExecutor(delayMs = 10_000)
        val handler = AlexaSkillHandler(demoConductor(executor = slowPhone), backgroundScope, AlexaRules(responseBudgetMs = 8_000))
        val alexa = SimulatedAlexaDriver(handler)

        val first = alexa.say("call Mom")
        assertEquals(AlexaSkillHandler.STILL_WORKING, AlexaAdapter.speechOf(first))
        assertFalse(AlexaAdapter.endsSession(first))

        advanceTimeBy(5_000)
        val second = alexa.say("is it done")
        assertTrue("Call Mom?" in AlexaAdapter.speechOf(second), AlexaAdapter.speechOf(second))
        assertTrue(slowPhone.dialed.isEmpty())
    }

    @Test
    fun launchGreetsAndSessionEndCancelsAPendingWrite() = runTest {
        val phone = FakeExecutor()
        val alexa = SimulatedAlexaDriver(AlexaSkillHandler(demoConductor(executor = phone), backgroundScope))
        assertEquals(AlexaSkillHandler.WELCOME, AlexaAdapter.speechOf(alexa.launch()))
        alexa.say("call Mom")
        alexa.endSession()
        alexa.say("yes")
        assertTrue(phone.dialed.isEmpty())
    }
}
