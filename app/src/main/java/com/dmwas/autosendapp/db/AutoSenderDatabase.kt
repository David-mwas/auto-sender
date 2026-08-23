package com.dmwas.autosendapp.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.dmwas.autosendapp.Contact
import com.dmwas.autosendapp.TransactionLog

@Database(entities = [Contact::class, TransactionLog::class], version = 1, exportSchema = false)
abstract class AutoSenderDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun transactionLogDao(): TransactionLogDao
}
