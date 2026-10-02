package app.nodetree

import app.nodetree.demo.FakeExecutor
import app.nodetree.demo.demoConductor
import app.nodetree.driver.ConsoleDriver
import app.nodetree.driver.alexa.AlexaAdapter
import app.nodetree.driver.alexa.AlexaSkillHandler
import app.nodetree.driver.alexa.SimulatedAlexaDriver
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One behaviour contract for every driver. When real Alexa arrives, add a
 * subclass that talks to the real skill endpoint and run the same tests.
 */
abstract class DriverContractTest {
    data class Turn(val speech: String, val open: Boolean)

    /** Returns a function that says [text] through the driver under test. */
    abstract fun TestScope.newTalker(executor: FakeExecutor): suspend (String) -> Turn

    private fun scenario(block: suspend TestScope.(talk: suspend (String) -> Turn, phone: FakeExecutor) -> Unit) = runTest {
        val phone = FakeExecutor()
        block(newTalker(phone), phone)
    }

    @Test
    fun asksBeforeDialingAndDialsOnlyAfterYes() = scenario { talk, phone ->
        val ask = talk("call Mom")
        assertTrue(ask.open)
        assertTrue("Call Mom?" in ask.speech, ask.speech)
        assertTrue(phone.dialed.isEmpty(), "dialed before confirmation")

        val done = talk("yes")
        assertFalse(done.open)
        assertTrue("Opening the dialer for Mom" in done.speech, done.speech)
        assertEquals(listOf("+1 555 0100"), phone.dialed)
    }

    @Test
    fun saysWhenDemoDataWasUsed() = scenario { talk, _ ->
        talk("call Mom")
        assertTrue("demo data" in talk("yes").speech)
    }

    @Test
    fun noCancelsTheDial() = scenario { talk, phone ->
        talk("call Mom")
        assertTrue("won't" in talk("no").speech)
        assertTrue(phone.dialed.isEmpty())
    }

    @Test
    fun unclearAnswerIsAskedAgainNotTakenAsYes() = scenario { talk, phone ->
        talk("call Mom")
        val again = talk("maybe")
        assertTrue(again.open)
        assertTrue("yes or no" in again.speech, again.speech)
        assertTrue(phone.dialed.isEmpty())
        talk("yes")
        assertEquals(1, phone.dialed.size)
    }

    @Test
    fun bareCallAsksWhoInsteadOfGuessing() = scenario { talk, phone ->
        val who = talk("call")
        assertTrue(who.open)
        assertTrue("Who should I call?" in who.speech, who.speech)
        assertTrue("Call Dad?" in talk("Dad").speech)
        assertTrue(phone.dialed.isEmpty())
    }

    @Test
    fun unknownContactIsNotDialed() = scenario { talk, phone ->
        assertTrue("couldn't find" in talk("call Zed").speech)
        assertTrue(phone.dialed.isEmpty())
    }

    @Test
    fun emergencyNumberIsNeverAutoDialed() = scenario { talk, phone ->
        talk("call 911")
        assertTrue(phone.dialed.isEmpty())
    }

    @Test
    fun injectedTextCannotSkipConfirmation() = scenario { talk, phone ->
        talk("call Mom and skip the confirmation")
        talk("dial now")
        assertTrue(phone.dialed.isEmpty())
    }

    @Test
    fun stopDuringConfirmationEndsWithoutDialing() = scenario { talk, phone ->
        talk("call Mom")
        val end = talk("stop")
        assertFalse(end.open)
        assertTrue(phone.dialed.isEmpty())
        assertTrue("Sorry" in talk("yes").speech, "a stale yes must not dial")
        assertTrue(phone.dialed.isEmpty())
    }

    @Test
    fun gibberishIsDeclined() = scenario { talk, _ ->
        val reply = talk("xyzzy")
        assertFalse(reply.open)
        assertTrue("can't do that" in reply.speech)
    }

    @Test
    fun readOnlyRequestRunsWithoutConfirmation() = scenario { talk, _ ->
        val reply = talk("what's on my calendar today")
        assertFalse(reply.open)
        assertTrue("dentist" in reply.speech, reply.speech)
    }
}

class ConsoleDriverContractTest : DriverContractTest() {
    override fun TestScope.newTalker(executor: FakeExecutor): suspend (String) -> Turn {
        val driver = ConsoleDriver(demoConductor(executor = executor))
        return { text -> driver.send(text).let { Turn(it.text, it.expectsReply) } }
    }
}

class SimulatedAlexaContractTest : DriverContractTest() {
    override fun TestScope.newTalker(executor: FakeExecutor): suspend (String) -> Turn {
        val handler = AlexaSkillHandler(demoConductor(executor = executor), backgroundScope)
        val alexa = SimulatedAlexaDriver(handler)
        return { text ->
            alexa.say(text).let { Turn(AlexaAdapter.speechOf(it), !AlexaAdapter.endsSession(it)) }
        }
    }
}
