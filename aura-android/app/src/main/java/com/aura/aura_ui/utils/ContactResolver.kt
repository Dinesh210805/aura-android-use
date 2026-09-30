package com.aura.aura_ui.utils

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

/**
 * Contact resolver for querying Android contacts database.
 * Finds phone numbers for contact names to enable deep link messaging.
 */
object ContactResolver {
    private const val TAG = "ContactResolver"

    data class Contact(
        val name: String,
        val phoneNumber: String,
        val normalized: String
    )

    /**
     * Find phone number for a contact name.
     * Supports case-insensitive partial matching.
     * 
     * @param context Android context
     * @param contactName Name to search for (e.g., "Shankar", "Shankar IT")
     * @return Phone number if found, null otherwise
     */
    fun findPhoneNumber(context: Context, contactName: String): String? {
        if (contactName.isBlank()) return null
        
        val contacts = searchContacts(context, contactName)
        
        // Return first match
        return contacts.firstOrNull()?.phoneNumber
    }

    /**
     * Search contacts by name with fuzzy matching.
     * 
     * @param context Android context
     * @param query Search query (case-insensitive)
     * @return List of matching contacts
     */
    fun searchContacts(context: Context, query: String): List<Contact> {
        if (query.isBlank()) return emptyList()
        
        val contacts = mutableListOf<Contact>()
        val queryLower = query.lowercase().trim()
        
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
            )
            
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            
            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val normalizedIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
                
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: continue
                    val number = it.getString(numberIndex) ?: continue
                    val normalized = it.getString(normalizedIndex) ?: number
                    
                    // Fuzzy match: check if query matches start or is contained in name
                    val nameLower = name.lowercase()
                    if (nameLower.startsWith(queryLower) || 
                        nameLower.contains(queryLower) ||
                        queryLower in nameLower) {
                        contacts.add(Contact(name, number, normalized))
                        Log.d(TAG, "✅ Found contact: $name → $number")
                    }
                }
            }
            
            Log.i(TAG, "🔍 Contact search '$query' found ${contacts.size} matches")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Permission denied: READ_CONTACTS required", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Contact search failed", e)
        }
        
        return contacts
    }

    /**
     * Clean phone number for deep link usage.
     * Removes spaces, dashes, parentheses.
     * 
     * @param phoneNumber Raw phone number
     * @return Cleaned number (e.g., "+919876543210" or "9876543210")
     */
    fun cleanPhoneNumber(phoneNumber: String): String {
        return phoneNumber
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
            .replace("\u00A0", "") // non-breaking space
            .trim()
    }
}
