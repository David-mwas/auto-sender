package com.dmwas.autosendapp

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import com.dmwas.autosendapp.db.AutoSenderDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date
import java.util.Calendar

enum class LogStatus {
    SUCCESS, FAILED, ERROR, INFO
}

@Entity(tableName = "transaction_logs")
data class TransactionLog(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val status: LogStatus,
    val message: String,
    val contextDetails: String,
    val amount: Double? = null,
    val contactId: String? = null
)

class LogsManager(context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        AutoSenderDatabase::class.java, "autosender-db"
    ).allowMainThreadQueries().build()

    private val transactionLogDao = db.transactionLogDao()
    private val prefs: SharedPreferences = context.getSharedPreferences("LogsPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    init {
        migrateFromSharedPreferences()
    }

    private fun migrateFromSharedPreferences() {
        val json = prefs.getString("logs_list", null)
        if (!json.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<TransactionLog>>() {}.type
                val oldLogs: List<TransactionLog> = gson.fromJson(json, type) ?: emptyList()
                if (oldLogs.isNotEmpty()) {
                    transactionLogDao.insertLogs(oldLogs)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Clear SharedPreferences after successful migration
            prefs.edit().remove("logs_list").apply()
        }
    }

    fun getLogs(): List<TransactionLog> {
        return transactionLogDao.getAllLogs()
    }

    fun addLog(status: LogStatus, message: String, contextDetails: String, amount: Double? = null, contactId: String? = null) {
        val log = TransactionLog(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            status = status,
            message = message,
            contextDetails = contextDetails,
            amount = amount,
            contactId = contactId
        )
        transactionLogDao.insertLog(log)
    }

    fun clearLogs() {
        transactionLogDao.deleteAllLogs()
    }
    
    fun getTotalSentAllTime(contactId: String? = null): Double {
        val logs = getLogs()
        return logs.filter { it.status == LogStatus.SUCCESS && it.amount != null && (contactId == null || it.contactId == contactId) }
            .sumOf { it.amount!! }
    }
    
    fun getTotalSentToday(contactId: String? = null): Double {
        val logs = getLogs()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        return logs.filter { 
            it.status == LogStatus.SUCCESS && 
            it.amount != null && 
            it.timestamp >= todayStart &&
            (contactId == null || it.contactId == contactId)
        }.sumOf { it.amount!! }
    }

    /**
     * Exports all logs as a CSV string.
     * Columns: Date, Time, Status, Message, Details, Amount (KES), Contact ID
     */
    fun exportLogsAsCsv(): String {
        val logs = getLogs()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("\"Date & Time\",\"Status\",\"Message\",\"Details\",\"Amount (KES)\",\"Contact ID\"")
        for (log in logs) {
            val date = sdf.format(java.util.Date(log.timestamp))
            val status = log.status.name
            val message = log.message.replace("\"", "'")
            val details = log.contextDetails.replace("\"", "'").replace("\n", " ")
            val amount = if (log.amount != null) "%.2f".format(log.amount) else ""
            val contactId = log.contactId ?: ""
            sb.appendLine("\"$date\",\"$status\",\"$message\",\"$details\",\"$amount\",\"$contactId\"")
        }
        return sb.toString()
    }

    /**
     * Exports all logs as a human-readable plain-text report.
     */
    fun exportLogsAsText(): String {
        val logs = getLogs()
        val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy  HH:mm:ss", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("AutoSender – Transaction Log Export")
        sb.appendLine("Generated: ${sdf.format(java.util.Date())}")
        sb.appendLine("Total records: ${logs.size}")
        sb.appendLine("=".repeat(50))
        sb.appendLine()
        for (log in logs) {
            sb.appendLine("[${log.status}]  ${sdf.format(java.util.Date(log.timestamp))}")
            sb.appendLine("  Message : ${log.message}")
            if (log.amount != null) sb.appendLine("  Amount  : KES ${"%.2f".format(log.amount)}")
            if (!log.contextDetails.isNullOrBlank() && log.contextDetails != log.message)
                sb.appendLine("  Details : ${log.contextDetails}")
            sb.appendLine("-".repeat(50))
        }
        return sb.toString()
    }
}
