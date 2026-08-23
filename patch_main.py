import re

with open("app/src/main/java/com/dmwas/autosendapp/MainActivity.kt", "r") as f:
    content = f.read()

# 1. Update initiateTransfer
content = re.sub(
    r"private fun initiateTransfer\(phoneNumber: String, amount: String\) \{.*?(val txPrefs = getSharedPreferences\(\"TxPrefs\", Context\.MODE_PRIVATE\)\s*txPrefs\.edit\(\)\s*\.putString\(\"phone_number\", )phoneNumber(\)\s*\.putString\(\"amount\", amount\))",
    r"private fun initiateTransfer(contact: Contact, amount: String) {\n        val pin = SecurityHelper.getPin(this)\n        if (pin.isNullOrEmpty()) {\n            Toast.makeText(this, \"Please set your M-PESA PIN in Settings first\", Toast.LENGTH_LONG).show()\n            return\n        }\n\n        if (!isAccessibilityServiceEnabled()) {\n            Toast.makeText(this, \"Please enable AutoSender in Accessibility Settings\", Toast.LENGTH_LONG).show()\n            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))\n            return\n        }\n\n        val txPrefs = getSharedPreferences(\"TxPrefs\", Context.MODE_PRIVATE)\n        txPrefs.edit()\n            .putString(\"phone_number\", contact.phoneNumber)\n            .putString(\"contact_id\", contact.id)\n            .putString(\"contact_name\", contact.name)\n            .putString(\"amount\", amount)",
    content,
    flags=re.DOTALL
)

# 2. Update AutoSenderApp call
content = re.sub(
    r"initiateTransfer\(contact\.phoneNumber, amount\)",
    r"initiateTransfer(contact, amount)",
    content
)

with open("app/src/main/java/com/dmwas/autosendapp/MainActivity.kt", "w") as f:
    f.write(content)
