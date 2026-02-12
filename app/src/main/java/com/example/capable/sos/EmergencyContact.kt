package com.example.capable.sos

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Emergency contact data model
 * @param name Display name
 * @param phone Phone number (with country code)
 * @param priority Lower number = higher priority (1 = most important)
 */
data class EmergencyContact(
    val name: String,
    val phone: String,
    val priority: Int
)

/**
 * Manages emergency contacts using SharedPreferences (JSON serialization)
 */
class EmergencyContactStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sos_contacts", Context.MODE_PRIVATE)

    fun saveContacts(contacts: List<EmergencyContact>) {
        val jsonArray = JSONArray()
        for (contact in contacts) {
            val obj = JSONObject().apply {
                put("name", contact.name)
                put("phone", contact.phone)
                put("priority", contact.priority)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_CONTACTS, jsonArray.toString()).apply()
    }

    fun loadContacts(): List<EmergencyContact> {
        val json = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            val contacts = mutableListOf<EmergencyContact>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                contacts.add(
                    EmergencyContact(
                        name = obj.getString("name"),
                        phone = obj.getString("phone"),
                        priority = obj.getInt("priority")
                    )
                )
            }
            contacts.sortedBy { it.priority }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addContact(contact: EmergencyContact) {
        val contacts = loadContacts().toMutableList()
        contacts.add(contact)
        // Re-assign priorities based on order
        saveContacts(contacts.mapIndexed { index, c -> c.copy(priority = index + 1) })
    }

    fun removeContact(phone: String) {
        val contacts = loadContacts().filter { it.phone != phone }
        saveContacts(contacts.mapIndexed { index, c -> c.copy(priority = index + 1) })
    }

    fun getTopPriorityContact(): EmergencyContact? {
        return loadContacts().minByOrNull { it.priority }
    }

    fun getAllContacts(): List<EmergencyContact> {
        return loadContacts()
    }

    fun moveUp(phone: String) {
        val contacts = loadContacts().toMutableList()
        val index = contacts.indexOfFirst { it.phone == phone }
        if (index > 0) {
            val temp = contacts[index]
            contacts[index] = contacts[index - 1]
            contacts[index - 1] = temp
            saveContacts(contacts.mapIndexed { i, c -> c.copy(priority = i + 1) })
        }
    }

    fun moveDown(phone: String) {
        val contacts = loadContacts().toMutableList()
        val index = contacts.indexOfFirst { it.phone == phone }
        if (index >= 0 && index < contacts.size - 1) {
            val temp = contacts[index]
            contacts[index] = contacts[index + 1]
            contacts[index + 1] = temp
            saveContacts(contacts.mapIndexed { i, c -> c.copy(priority = i + 1) })
        }
    }

    companion object {
        private const val KEY_CONTACTS = "emergency_contacts"
    }
}
