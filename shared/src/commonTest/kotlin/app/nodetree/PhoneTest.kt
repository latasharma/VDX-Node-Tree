package app.nodetree

import app.nodetree.demo.DemoCatalog
import app.nodetree.driver.ConsoleDriver
import app.nodetree.model.Platform
import app.nodetree.phone.Contact
import app.nodetree.phone.ContactMatcher
import app.nodetree.phone.ContactNumber
import app.nodetree.phone.ContactsResult
import app.nodetree.phone.ContactsSource
import app.nodetree.phone.DialOutcome
import app.nodetree.phone.Dialer
import app.nodetree.phone.MatchResult
import app.nodetree.phone.PhoneExecutor
import app.nodetree.phone.phoneConductor
import app.nodetree.run.ExecResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun person(name: String, vararg numbers: ContactNumber) = Contact(name, numbers.toList())
private fun mobile(n: String) = ContactNumber("mobile", n, isMobile = true)
private fun home(n: String) = ContactNumber("home", n, isMobile = false)

/** Stands in for ContactsContract / CNContactStore: returns everything, like a loose platform query. */
private class FakeContacts(
    private val book: List<Contact>,
    private val denied: Boolean = false,
    private val error: String? = null,
) : ContactsSource {
    val queries = mutableListOf<String>()
    override fun search(name: String, callback: (ContactsResult) -> Unit) {
        queries += name
        callback(ContactsResult(if (denied || error != null) emptyList() else book, denied, error))
    }
}

private class FakeDialer(private val works: Boolean = true) : Dialer {
    val opened = mutableListOf<String>()
    override fun openDialer(number: String, callback: (DialOutcome) -> Unit) {
        if (works) opened += number
        callback(DialOutcome(works, if (works) null else "no dialer app"))
    }
}

private val book = listOf(
    person("Mom", home("+1 555 0100"), mobile("+1 555 0199")),
    person("Sam Lee", mobile("+1 555 0102")),
    person("Sam Patel", mobile("+1 555 0103")),
    person("Dr. Rao", mobile("+1 555 0104")),
    person("Samantha Ortiz", mobile("+1 555 0105")),
    person("Grandpa", ),
)

class ContactMatcherTest {
    @Test
    fun exactNameWinsAndMyIsIgnored() {
        assertEquals("Mom", (ContactMatcher.match("my mom", book) as MatchResult.One).contact.displayName)
        assertEquals("Dr. Rao", (ContactMatcher.match("Dr Rao", book) as MatchResult.One).contact.displayName)
    }

    @Test
    fun sharedFirstNameIsAmbiguousNotGuessed() {
        val result = assertIs<MatchResult.Ambiguous>(ContactMatcher.match("Sam", book))
        assertEquals(setOf("Sam Lee", "Sam Patel"), result.candidates.map { it.displayName }.toSet())
    }

    @Test
    fun containsOnlyMatchIsRejected() {
        assertEquals(MatchResult.None, ContactMatcher.match("Ortiz", book))
        assertEquals(MatchResult.None, ContactMatcher.match("am", book))
    }

    @Test
    fun contactWithoutANumberIsSkipped() {
        assertEquals(MatchResult.None, ContactMatcher.match("Grandpa", book))
    }

    @Test
    fun mobileNumberIsPreferred() {
        assertEquals("+1 555 0199", ContactMatcher.bestNumber(book.first()).number)
    }
}

class PhoneExecutorTest {
    private val lookup = DemoCatalog.capabilities.first { it.id == "contacts.lookup" }
    private val dial = DemoCatalog.capabilities.first { it.id == "phone.dial" }

    @Test
    fun lookupReturnsTheMobileNumberAndIsNotDemoData() = runTest {
        val result = assertIs<ExecResult.Success>(PhoneExecutor(FakeContacts(book), FakeDialer()).execute(lookup, mapOf("name" to "Mom")))
        assertEquals("+1 555 0199", result.output["number"])
        assertFalse(result.usedDemoData)
    }

    @Test
    fun ambiguousNameAsksForTheFullName() = runTest {
        val result = assertIs<ExecResult.Failure>(PhoneExecutor(FakeContacts(book), FakeDialer()).execute(lookup, mapOf("name" to "Sam")))
        assertTrue("say the full name" in result.userMessage, result.userMessage)
    }

    @Test
    fun deniedPermissionIsExplained() = runTest {
        val result = assertIs<ExecResult.Failure>(PhoneExecutor(FakeContacts(book, denied = true), FakeDialer()).execute(lookup, mapOf("name" to "Mom")))
        assertTrue("permission" in result.userMessage)
    }

    @Test
    fun dialerFailureIsReported() = runTest {
        val result = PhoneExecutor(FakeContacts(book), FakeDialer(works = false)).execute(dial, mapOf("number" to "1", "name" to "Mom"))
        assertIs<ExecResult.Failure>(result)
    }
}

class PhoneConductorTest {
    @Test
    fun callMomEndToEndOpensTheRealDialerOnlyAfterYes() = runTest {
        val dialer = FakeDialer()
        val app = ConsoleDriver(phoneConductor(Platform.ANDROID, FakeContacts(book), dialer))
        assertEquals("Call Mom?", app.send("call my mom").text)
        assertTrue(dialer.opened.isEmpty())
        val done = app.send("yes").text
        assertEquals(listOf("+1 555 0199"), dialer.opened)
        assertFalse("demo data" in done, "real phone results must not be labelled demo")
    }

    @Test
    fun ambiguousNameNeverDials() = runTest {
        val dialer = FakeDialer()
        val app = ConsoleDriver(phoneConductor(Platform.IOS, FakeContacts(book), dialer))
        assertTrue("Sam Lee" in app.send("call Sam").text)
        app.send("yes")
        assertTrue(dialer.opened.isEmpty())
    }

    @Test
    fun calendarIsStillLabelledDemo() = runTest {
        val app = ConsoleDriver(phoneConductor(Platform.ANDROID, FakeContacts(book), FakeDialer()))
        assertTrue("demo data" in app.send("what's on my calendar today").text)
    }
}
