package com.dmwas.autosendapp.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dmwas.autosendapp.Contact

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts")
    fun getAllContacts(): List<Contact>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertContact(contact: Contact)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertContacts(contacts: List<Contact>)

    @Update
    fun updateContact(contact: Contact)

    @Query("DELETE FROM contacts WHERE id = :contactId")
    fun deleteContact(contactId: String)
}
