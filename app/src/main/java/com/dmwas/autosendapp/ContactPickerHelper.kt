package com.dmwas.autosendapp

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

object ContactPickerHelper {
    /**
     * Normalizes a Kenyan phone number for Safaricom M-PESA USSD (*334#).
     * Strips spaces/dashes and converts +2547... / 2547... / +2541... / 2541... to 07... / 01...
     */
    fun normalizePhoneNumber(raw: String): String {
        val digitsAndPlus = raw.trim().replace(Regex("[^0-9+]"), "")
        val digitsOnly = digitsAndPlus.removePrefix("+")
        return when {
            digitsOnly.length == 12 && digitsOnly.startsWith("254") -> "0" + digitsOnly.substring(3)
            digitsOnly.length == 9 && (digitsOnly.startsWith("7") || digitsOnly.startsWith("1")) -> "0$digitsOnly"
            else -> digitsOnly
        }
    }

    /**
     * Parses a user-entered amount string (which may contain commas, spaces, or currency prefixes)
     * into a clean positive Double, or null if invalid.
     */
    fun parseAmount(raw: String): Double? {
        val cleaned = raw.trim()
            .replace(",", "")
            .replace(Regex("(?i)(kes|ksh|kshs)"), "")
            .trim()
        val value = cleaned.toDoubleOrNull() ?: return null
        return if (value > 0.0) value else null
    }

    /**
     * Formats an amount string for USSD input (whole integer string if no fractional cents,
     * and stripped of commas/spaces so USSD does not reject it).
     */
    fun sanitizeAmountForUssd(raw: String): String {
        val value = parseAmount(raw) ?: return raw.trim().replace(Regex("[^0-9.]"), "")
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            "%.2f".format(java.util.Locale.US, value)
        }
    }

    fun getContactDetails(context: Context, uri: Uri): Pair<String, String>? {
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    
                    val number = if (numberIndex != -1) it.getString(numberIndex) else ""
                    val name = if (nameIndex != -1) it.getString(nameIndex) else "Unknown"
                    
                    return Pair(name, normalizePhoneNumber(number))
                }
            }
        } catch (e: Exception) {
            Log.e("ContactPicker", "Error picking contact", e)
        }
        return null
    }
}
