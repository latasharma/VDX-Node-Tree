package app.nodetree.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import app.nodetree.phone.Contact
import app.nodetree.phone.ContactNumber
import app.nodetree.phone.ContactsResult
import app.nodetree.phone.ContactsSource
import app.nodetree.phone.DialOutcome
import app.nodetree.phone.Dialer
import java.util.concurrent.Executors

/**
 * Contacts via ContactsContract. Returns everyone whose name contains the
 * spoken words; the shared ContactMatcher decides who was meant.
 */
class AndroidContacts(context: Context) : ContactsSource {
    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor()

    override fun search(name: String, callback: (ContactsResult) -> Unit) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            callback(ContactsResult(emptyList(), permissionDenied = true, error = null))
            return
        }
        io.execute {
            val result = try {
                ContactsResult(query(name), permissionDenied = false, error = null)
            } catch (e: SecurityException) {
                ContactsResult(emptyList(), permissionDenied = true, error = null)
            } catch (e: Exception) {
                ContactsResult(emptyList(), permissionDenied = false, error = e.javaClass.simpleName)
            }
            callback(result)
        }
    }

    private fun query(name: String): List<Contact> {
        // The last spoken word ("Rao" in "Dr Rao") narrows the query; shared code picks the match.
        val word = name.trim().split(' ').lastOrNull { it.isNotBlank() && !it.equals("my", ignoreCase = true) } ?: return emptyList()
        val byContact = linkedMapOf<Long, Pair<String, MutableList<ContactNumber>>>()
        appContext.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE),
            "${Phone.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$word%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val display = c.getString(1) ?: continue
                val number = c.getString(2) ?: continue
                val type = c.getInt(3)
                val label = if (type == Phone.TYPE_MOBILE) "mobile" else "other"
                byContact.getOrPut(id) { display to mutableListOf() }.second +=
                    ContactNumber(label, number, isMobile = type == Phone.TYPE_MOBILE)
            }
        }
        return byContact.values.map { (display, numbers) -> Contact(display, numbers.distinctBy { it.number }) }
    }
}

/** ACTION_DIAL only: the dialer opens with the number and the user taps Call. Never ACTION_CALL. */
class AndroidDialer(context: Context) : Dialer {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun openDialer(number: String, callback: (DialOutcome) -> Unit) {
        main.post {
            val outcome = try {
                appContext.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                DialOutcome(opened = true, error = null)
            } catch (e: ActivityNotFoundException) {
                DialOutcome(opened = false, error = "no dialer app")
            }
            callback(outcome)
        }
    }
}
