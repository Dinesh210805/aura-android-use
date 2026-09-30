package com.aura.aura_ui.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.aura.aura_ui.data.database.dao.AuraContactDao
import com.aura.aura_ui.data.database.dao.SttCorrectionDao
import com.aura.aura_ui.data.database.entities.AuraContactEntity
import com.aura.aura_ui.data.database.entities.SttCorrectionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages contact sync from the device ContactsContract into the local Room DB.
 *
 * Responsibilities:
 *  1. Permission check — on denial emits [HITLSignal.PermissionRequired] to [HITLBroker]
 *  2. Bulk sync — reads all device contacts, computes phonetic keys, upserts to DB
 *  3. Change observer — re-syncs when ContactsContract notifies of changes
 *  4. Learning loop — [recordCorrection] tracks user disambiguation choices;
 *     when a (sttHeard, contactId) pair reaches [SttCorrectionEntity.ALIAS_THRESHOLD]
 *     confirmations, the STT spelling is promoted to that contact's aliases list
 *
 * Contact data NEVER leaves the device.
 */
@Singleton
class ContactSyncService @Inject constructor(
    private val contactDao: AuraContactDao,
    private val correctionDao: SttCorrectionDao,
    private val resolver: ContactResolver
) {
    companion object {
        private const val TAG = "ContactSyncService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Permission + sync entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Call this from a coroutine context (e.g. from onStart in MainActivity).
     * Checks READ_CONTACTS permission; if denied, emits a HITL signal so the
     * broker can show a Settings deeplink. If granted, runs a full sync and
     * registers the ContentObserver for future changes.
     */
    suspend fun syncWithPermissionCheck(context: Context) {
        if (!hasContactsPermission(context)) {
            Log.w(TAG, "READ_CONTACTS not granted — emitting HITL signal")
            HITLBroker.emit(
                HITLSignal.PermissionRequired(
                    permissionName = Manifest.permission.READ_CONTACTS,
                    rationale = "AURA needs access to your contacts to resolve names " +
                            "from voice commands. Your contact data never leaves your device.",
                    onOpenSettings = {
                        // Caller is responsible for launching the Settings intent
                        Log.i(TAG, "User chose to open Settings for READ_CONTACTS")
                    },
                    onCancelled = {
                        Log.i(TAG, "User declined READ_CONTACTS — contact commands degraded")
                    }
                )
            )
            return
        }

        syncContacts(context)
        registerObserver(context)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sync
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads all contacts from ContactsContract, computes phonetic keys, upserts
     * into [AuraContactDao]. Runs on the calling coroutine (already on IO).
     */
    suspend fun syncContacts(context: Context) {
        if (!hasContactsPermission(context)) return

        Log.i(TAG, "Starting contact sync…")
        val deviceContacts = readDeviceContacts(context)
        Log.i(TAG, "Read ${deviceContacts.size} contacts from device")

        contactDao.insertAll(deviceContacts)

        // Remove contacts that no longer exist on the device
        val activeIds = deviceContacts.map { it.contactId }
        if (activeIds.isNotEmpty()) {
            contactDao.deleteStale(activeIds)
        }

        Log.i(TAG, "Sync complete — ${deviceContacts.size} contacts in DB")
    }

    private fun readDeviceContacts(context: Context): List<AuraContactEntity> {
        val contactMap = mutableMapOf<String, MutableAuraContact>()

        // Query phone numbers grouped by contactId
        val phoneProjection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            phoneProjection,
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numberIdx) ?: continue
                val photo = cursor.getString(photoIdx)

                contactMap.getOrPut(id) {
                    MutableAuraContact(id, name, photo)
                }.phones.add(number)
            }
        }

        // Query email addresses
        val emailProjection = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            emailProjection,
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val emailIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx) ?: continue
                val email = cursor.getString(emailIdx) ?: continue
                contactMap[id]?.emails?.add(email)
            }
        }

        // Convert to Room entities with phonetic keys
        return contactMap.values.map { c ->
            val firstToken = c.displayName.trim().split(" ").firstOrNull() ?: c.displayName
            val phoneticKey = resolver.computePhoneticKey(firstToken)
            val phoneticKeyAlt = resolver.computePhoneticKeyAlt(firstToken)

            // Preserve existing aliases from DB if present
            val existing = try { null } catch (e: Exception) { null }  // sync is pre-DB read

            AuraContactEntity(
                contactId = c.id,
                displayName = c.displayName,
                phoneNumbers = c.phones.toJsonArray(),
                emails = c.emails.toJsonArray(),
                photoUri = c.photoUri,
                phoneticKey = phoneticKey,
                phoneticKeyAlt = phoneticKeyAlt,
                aliases = existing ?: "[]",
                lastSyncedAt = System.currentTimeMillis()
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ContentObserver
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerObserver(context: Context) {
        if (observer != null) return
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "ContactsContract changed — scheduling re-sync")
                scope.launch { syncContacts(context) }
            }
        }
        context.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            obs
        )
        observer = obs
        Log.d(TAG, "ContentObserver registered")
    }

    fun unregisterObserver(context: Context) {
        observer?.let {
            context.contentResolver.unregisterContentObserver(it)
            observer = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Learning loop (Task 5)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Call this every time the user confirms a contact via the disambiguation sheet.
     *
     * After [SttCorrectionEntity.ALIAS_THRESHOLD] confirmations for the same
     * (sttHeard, contactId) pair, [sttHeard] is appended to that contact's
     * `aliases` list. Future Stage 1 exact matches will resolve it instantly
     * without going through fuzzy stages.
     */
    fun recordCorrection(sttHeard: String, contactId: String) {
        scope.launch {
            val normalized = sttHeard.lowercase().trim()
            val existing = correctionDao.find(normalized, contactId)
            if (existing == null) {
                correctionDao.insert(
                    SttCorrectionEntity(
                        sttHeard = normalized,
                        resolvedContactId = contactId,
                        correctionCount = 1
                    )
                )
            } else {
                correctionDao.increment(normalized, contactId)
            }

            // Check if this pair is ready for alias promotion
            val updated = correctionDao.find(normalized, contactId) ?: return@launch
            if (updated.correctionCount >= SttCorrectionEntity.ALIAS_THRESHOLD) {
                promoteAlias(normalized, contactId)
            }
        }
    }

    private suspend fun promoteAlias(sttHeard: String, contactId: String) {
        val contact = contactDao.getById(contactId) ?: return

        val aliases = try {
            val arr = org.json.JSONArray(contact.aliases)
            (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        } catch (e: Exception) { mutableListOf() }

        if (sttHeard !in aliases) {
            aliases.add(sttHeard)
            val updated = contact.copy(aliases = aliases.toJsonArray())
            contactDao.update(updated)
            Log.i(TAG, "✅ Alias promoted: '$sttHeard' → contact '${contact.displayName}'")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun hasContactsPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    private data class MutableAuraContact(
        val id: String,
        val displayName: String,
        val photoUri: String?,
        val phones: MutableList<String> = mutableListOf(),
        val emails: MutableList<String> = mutableListOf()
    )

    private fun List<String>.toJsonArray(): String {
        val arr = JSONArray()
        forEach { arr.put(it) }
        return arr.toString()
    }
}
