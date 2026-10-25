package com.dmwas.autosendapp

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dmwas.autosendapp.db.AutoSenderDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Entity(tableName = "contacts")
data class Contact(@PrimaryKey val id: String, val name: String, val phoneNumber: String)

class ContactsManager(context: Context) {
    // Shared singleton connection — no extra DB handles opened
    private val db = AutoSenderDatabase.getInstance(context)
    private val contactDao = db.contactDao()
    private val prefs: SharedPreferences = context.getSharedPreferences("ContactsPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    // All writes run on IO — never blocks the UI thread
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val contactsFlow: Flow<List<Contact>> = contactDao.getAllContactsFlow()

    init {
        migrateFromSharedPreferences()
    }

    private fun migrateFromSharedPreferences() {
        val json = prefs.getString("contacts_list", null)
        if (!json.isNullOrEmpty()) {
            ioScope.launch {
                try {
                    val type = object : TypeToken<List<Contact>>() {}.type
                    val oldContacts: List<Contact> = gson.fromJson(json, type) ?: emptyList()
                    if (oldContacts.isNotEmpty()) {
                        contactDao.insertContacts(
                            oldContacts.map {
                                it.copy(phoneNumber = ContactPickerHelper.normalizePhoneNumber(it.phoneNumber))
                            }
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                prefs.edit().remove("contacts_list").apply()
            }
        }
    }

    fun getContacts(): List<Contact> {
        return contactDao.getAllContacts()
    }

    fun findByPhoneNumber(phone: String): Contact? {
        val normalized = ContactPickerHelper.normalizePhoneNumber(phone)
        if (normalized.isEmpty()) return null
        return getContacts().firstOrNull {
            ContactPickerHelper.normalizePhoneNumber(it.phoneNumber) == normalized
        }
    }

    fun addContact(contact: Contact) {
        val normalizedContact = contact.copy(
            name = contact.name.trim(),
            phoneNumber = ContactPickerHelper.normalizePhoneNumber(contact.phoneNumber)
        )
        ioScope.launch { contactDao.insertContact(normalizedContact) }
    }

    fun updateContact(contact: Contact) {
        val normalizedContact = contact.copy(
            name = contact.name.trim(),
            phoneNumber = ContactPickerHelper.normalizePhoneNumber(contact.phoneNumber)
        )
        ioScope.launch { contactDao.updateContact(normalizedContact) }
    }

    fun removeContact(contactId: String) {
        ioScope.launch { contactDao.deleteContact(contactId) }
    }
}
