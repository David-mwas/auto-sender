package com.dmwas.autosendapp.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dmwas.autosendapp.Contact
import com.dmwas.autosendapp.TransactionLog

@Database(entities = [Contact::class, TransactionLog::class], version = 1, exportSchema = false)
abstract class AutoSenderDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun transactionLogDao(): TransactionLogDao

    companion object {
        @Volatile private var INSTANCE: AutoSenderDatabase? = null

        fun getInstance(context: Context): AutoSenderDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoSenderDatabase::class.java,
                    "autosender-db"
                )
                    .allowMainThreadQueries() // reads are small/fast; writes go through IO scope
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
