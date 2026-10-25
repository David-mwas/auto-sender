package com.dmwas.autosendapp.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dmwas.autosendapp.LogStatus
import com.dmwas.autosendapp.TransactionLog
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionLogDao {
    @Query("SELECT * FROM transaction_logs ORDER BY timestamp DESC")
    fun getAllLogs(): List<TransactionLog>

    @Query("SELECT * FROM transaction_logs ORDER BY timestamp DESC")
    fun getAllLogsFlow(): Flow<List<TransactionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLog(log: TransactionLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLogs(logs: List<TransactionLog>)

    @Query("UPDATE transaction_logs SET status = :status, message = :message, amount = :amount WHERE id = :id")
    fun updateLogStatus(id: String, status: LogStatus, message: String, amount: Double?)

    @Query("DELETE FROM transaction_logs WHERE id = :id")
    fun deleteLog(id: String)

    @Query("DELETE FROM transaction_logs")
    fun deleteAllLogs()
}
