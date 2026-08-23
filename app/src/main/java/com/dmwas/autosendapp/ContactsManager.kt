package com.dmwas.autosendapp

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import com.dmwas.autosendapp.db.AutoSenderDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "contacts")
data class Contact(@PrimaryKey val id: String, val name: String, val phoneNumber: String)

class ContactsManager(context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        AutoSenderDatabase::class.java, "autosender-db"
    ).allowMainThreadQueries().build()

    private val contactDao = db.contactDao()
    private val prefs: SharedPreferences = context.getSharedPreferences("ContactsPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    init {
        migrateFromSharedPreferences()
    }

    private fun migrateFromSharedPreferences() {
        val json = prefs.getString("contacts_list", null)
        if (!json.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<Contact>>() {}.type
                val oldContacts: List<Contact> = gson.fromJson(json, type) ?: emptyList()
                if (oldContacts.isNotEmpty()) {
                    contactDao.insertContacts(oldContacts)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Clear SharedPreferences after successful migration
            prefs.edit().remove("contacts_list").apply()
        }
    }

    fun getContacts(): List<Contact> {
        return contactDao.getAllContacts()
    }

    fun addContact(contact: Contact) {
        contactDao.insertContact(contact)
    }

    fun updateContact(contact: Contact) {
        contactDao.updateContact(contact)
    }

    fun removeContact(contactId: String) {
        contactDao.deleteContact(contactId)
    }
}
