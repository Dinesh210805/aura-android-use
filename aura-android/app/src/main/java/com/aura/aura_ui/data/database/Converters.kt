package com.aura.aura_ui.data.database

import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * Room type converters for List<String> ↔ JSON string.
 * Used by AuraContactEntity fields: phoneNumbers, emails, aliases.
 */
class Converters {

    @TypeConverter
    fun fromJsonString(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(value)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun toJsonString(list: List<String>?): String {
        if (list == null) return "[]"
        val array = JSONArray()
        list.forEach { array.put(it) }
        return array.toString()
    }
}
