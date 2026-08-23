package com.dmwas.autosendapp.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dmwas.autosendapp.TransactionLog

@Dao
interface TransactionLogDao {
    @Query("SELECT * FROM transaction_logs ORDER BY timestamp DESC")
    fun getAllLogs(): List<TransactionLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLog(log: TransactionLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLogs(logs: List<TransactionLog>)

    @Query("DELETE FROM transaction_logs")
    fun deleteAllLogs()
}
