package `in`.wishkaro.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns MainActivity's permission callback into a suspending call, the way
 * [PermissionBridge] already does for notifications — but answering a three-way question
 * rather than a boolean.
 */
object ContactsPermissionBridge {
    @Volatile
    var requester: (suspend () -> PermissionOutcome)? = null

    suspend fun request(): PermissionOutcome = requester?.invoke() ?: PermissionOutcome.DENIED

    /** Same reason as [PermissionBridge.pending]: a rotation must not orphan the ask. */
    val pending = CompletableDeferredHolder<Boolean>()
}

/**
 * READ_CONTACTS, and the contacts themselves (FR-IMP-01).
 *
 * Entirely on-device. This class reads; nothing above it sees a contact the user has not
 * selected, and nothing here logs a name, a number or a birthday. A contacts list is the
 * most sensitive thing this app will ever touch and the ones the user did not pick must
 * never leave the phone — not in a log line, not in an analytics event, not in a crash
 * report. That is why there is not a single `Log` call in this file.
 */
class AndroidContactsGateway(private val context: Context) : ContactsGateway {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun requestPermission(): PermissionOutcome {
        if (hasPermission()) return PermissionOutcome.GRANTED
        return ContactsPermissionBridge.request()
    }

    override fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Every contact that has a name, with their first phone number and first birthday.
     *
     * Two queries rather than one. The obvious single query over the Data table would miss
     * every contact that has only a name — no phone, no birthday — and on a real device
     * those are most of them: the handset this was built against has 515 contacts and 7 rows
     * in the whole event table. FR-IMP-02 wants the dateless ones LISTED, so they have to be
     * read.
     */
    override suspend fun readContacts(): List<DeviceContact> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val resolver = context.contentResolver

        val contacts = LinkedHashMap<String, DeviceContact>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ),
            // A contact with no name is one this app has nothing to say about: a person
            // needs a name, and "(no name)" in a list of 500 is not something to choose from.
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} IS NOT NULL",
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val photoColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn) ?: continue
                val name = cursor.getString(nameColumn)?.trim().orEmpty()
                if (name.isEmpty()) continue
                contacts[id] = DeviceContact(
                    id = id,
                    displayName = name,
                    phone = null,
                    rawBirthday = null,
                    // Display only, for the review screen. Never persisted to photo_key,
                    // which is reserved for M6, and never uploaded.
                    photoUri = cursor.getString(photoColumn),
                )
            }
        }
        if (contacts.isEmpty()) return@withContext emptyList()

        val phones = mutableMapOf<String, String>()
        val birthdays = mutableMapOf<String, String>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Event.START_DATE,
                ContactsContract.CommonDataKinds.Event.TYPE,
            ),
            "${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            // DATA1 for both mimetypes: the number for a phone, the date for an event.
            val valueColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Event.START_DATE)
            val typeColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Event.TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn) ?: continue
                val value = cursor.getString(valueColumn)?.trim()
                // A birthday row with a NULL date is a real thing on a real device — the
                // handset this was built against has one. It is not a birthday; the contact
                // belongs in the "no date" half of the review screen.
                if (value.isNullOrEmpty()) continue
                when (cursor.getString(mimeColumn)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                        phones.putIfAbsent(id, value)
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE ->
                        // TYPE_BIRTHDAY only. An anniversary or a custom date on a contact is
                        // a real date, but it is not this person's birthday, and importing it
                        // AS one would put the wrong label on it for ever. M3 imports
                        // birthdays; other event types stay a manual add.
                        if (cursor.getInt(typeColumn) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) {
                            birthdays.putIfAbsent(id, value)
                        }
                }
            }
        }

        contacts.values.map { contact ->
            contact.copy(
                phone = phones[contact.id],
                rawBirthday = birthdays[contact.id],
            )
        }
    }
}
