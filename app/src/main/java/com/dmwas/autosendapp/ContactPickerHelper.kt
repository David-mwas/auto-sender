package com.dmwas.autosendapp

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

object ContactPickerHelper {
    fun getContactDetails(context: Context, uri: Uri): Pair<String, String>? {
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    
                    val number = if (numberIndex != -1) it.getString(numberIndex) else ""
                    val name = if (nameIndex != -1) it.getString(nameIndex) else "Unknown"
                    
                    return Pair(name, number.replace(Regex("[^0-9+]"), ""))
                }
            }
        } catch (e: Exception) {
            Log.e("ContactPicker", "Error picking contact", e)
        }
        return null
    }
}
