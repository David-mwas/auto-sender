package com.dmwas.autosendapp

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MpesaUssdService : AccessibilityService() {

    private val TAG = "MpesaUssdService"
    private var lastDialogText = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private val logsManager by lazy { LogsManager(this) }

    companion object {
        // Held so startSession() can reset lastDialogText for the new send session
        @Volatile private var instance: MpesaUssdService? = null
        private var sessionTimeoutJob: kotlinx.coroutines.Job? = null

        fun startSession(context: Context, scope: CoroutineScope) {
            // Reset dedup guard so a new session with identical dialog text is not skipped
            instance?.lastDialogText = ""
            context.getSharedPreferences("UssdPrefs", Context.MODE_PRIVATE).edit().putBoolean("isSessionActive", true).apply()
            sessionTimeoutJob?.cancel()
            sessionTimeoutJob = scope.launch {
                delay(300_000L) // 5-minute safety timeout
                endSession(context)
                Log.w("MpesaUssdService", "Session timed out — going dormant")
            }
        }

        fun endSession(context: Context) {
            context.getSharedPreferences("UssdPrefs", Context.MODE_PRIVATE).edit().putBoolean("isSessionActive", false).apply()
            sessionTimeoutJob?.cancel()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service connected — dormant until USSD session starts")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val prefs = getSharedPreferences("UssdPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("isSessionActive", false)) return

        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        serviceScope.launch {
            delay(400) // Give the normal USSD dialog time to fully render on screen

            // Re-check session is still active after the delay.
            // Multiple accessibility events can fire in rapid succession (all passing the
            // pre-launch check above). Once the first coroutine finishes and calls endSession(),
            // this guard ensures every other pending coroutine exits immediately instead of
            // logging the same transaction again.
            if (!getSharedPreferences("UssdPrefs", Context.MODE_PRIVATE).getBoolean("isSessionActive", false)) return@launch

            val rootNode = rootInActiveWindow ?: event?.source ?: return@launch
            var inputNode: AccessibilityNodeInfo? = null
            var buttonNode: AccessibilityNodeInfo? = null
            val allText = java.lang.StringBuilder()
            val clickableNodes = mutableListOf<AccessibilityNodeInfo>()

            findNodes(rootNode, onInputFound = { inputNode = it }, onButtonFound = { buttonNode = it }, allText = allText, clickableNodes = clickableNodes)

            val dialogText = allText.toString().lowercase()
            if (dialogText.trim().isEmpty()) return@launch
            
            if (dialogText.contains("autosender active") || dialogText.contains("start autosender")) {
                return@launch
            }
            
            if (android.BuildConfig.DEBUG) Log.d(TAG, "USSD Dialog text: $dialogText")

            if (dialogText == lastDialogText) {
                return@launch 
            }
            

            delay(400)
            var actionTaken = true

            val txPrefs = getSharedPreferences("TxPrefs", Context.MODE_PRIVATE)
            val phoneNumber = txPrefs.getString("phone_number", "") ?: ""
            val amount = txPrefs.getString("amount", "") ?: ""
            val amountDouble = amount.toDoubleOrNull()
            val contactId = txPrefs.getString("contact_id", null)
            val contactName = txPrefs.getString("contact_name", null)

            // PIN is read directly from encrypted storage — never stored in plain TxPrefs
            val pin = SecurityHelper.getPin(this) ?: ""

            if (inputNode != null && buttonNode != null) {
                when {
                    dialogText.contains("send money") && dialogText.contains("withdraw") -> {
                        Toast.makeText(applicationContext, "Selecting: Send Money", Toast.LENGTH_SHORT).show()
                        inputText(inputNode!!, "1")
                        delay(800)
                        clickNode(buttonNode!!)
                    }
                    dialogText.contains("send money") && dialogText.contains("any network") -> {
                        val opt = getOption(dialogText, "any network") ?: "1"
                        Toast.makeText(applicationContext, "Selecting: Send Money to Any Network ($opt)", Toast.LENGTH_SHORT).show()
                        inputText(inputNode!!, opt)
                        delay(800)
                        clickNode(buttonNode!!)
                    }
                    dialogText.contains("enter phone no") || dialogText.contains("enter phone number") -> {
                        if (phoneNumber.isNotEmpty()) {
                            Toast.makeText(applicationContext, "Entering Phone Number", Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, phoneNumber)
                            delay(800)
                            clickNode(buttonNode!!)
                        } else {
                            actionTaken = false
                        }
                    }
                    dialogText.contains("enter amount") -> {
                        if (amount.isNotEmpty()) {
                            Toast.makeText(applicationContext, "Entering Amount", Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, amount)
                            delay(800)
                            clickNode(buttonNode!!)
                        } else {
                            actionTaken = false
                        }
                    }
                    dialogText.contains("enter m-pesa pin") || dialogText.contains("enter pin") -> {
                        if (pin.isNotEmpty()) {
                            Toast.makeText(applicationContext, "Entering PIN", Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, pin)
                            delay(800)
                            clickNode(buttonNode!!)
                        } else {
                            actionTaken = false
                        }
                    }
                    dialogText.contains("send") && dialogText.contains("ksh") && (dialogText.contains("accept") || dialogText.contains("reply with 1")) -> {
                        val opt = if (dialogText.contains("accept")) (getOption(dialogText, "accept") ?: "1") else "1"
                        Toast.makeText(applicationContext, "Confirming Transfer", Toast.LENGTH_SHORT).show()
                        inputText(inputNode!!, opt)
                        delay(800)
                        clickNode(buttonNode!!)
                        // Do NOT end session here. Wait for the final success/failure dialog.
                    }
                    else -> {
                        actionTaken = false
                    }
                }
            } else if (
                dialogText.contains("transfer successful") ||
                dialogText.contains("you have sent") ||
                dialogText.contains("sent to") ||
                dialogText.contains("m-pesa confirmed") ||
                (dialogText.contains("success") && (dialogText.contains("ksh") || dialogText.contains("kes") || dialogText.contains("sent"))) ||
                dialogText.contains("failed") ||
                dialogText.contains("request is being processed") ||
                dialogText.contains("wait for an sms") ||
                dialogText.contains("wait for sms") ||
                dialogText.contains("wait for a confirmation")
            ) {
                // Determine if this is a definitive M-PESA success (actual money sent confirmation)
                val isDefinitiveSuccess = dialogText.contains("transfer successful") ||
                    dialogText.contains("you have sent") ||
                    (dialogText.contains("sent to") && (dialogText.contains("ksh") || dialogText.contains("kes"))) ||
                    dialogText.contains("m-pesa confirmed") ||
                    (dialogText.contains("success") && (dialogText.contains("ksh") || dialogText.contains("kes")))
                
                // "Request being processed" / "wait for sms" = pending, NOT a confirmed success
                // Only log amount on definitive success; pending = INFO with no amount
                val isPending = dialogText.contains("request is being processed") ||
                    dialogText.contains("wait for an sms") ||
                    dialogText.contains("wait for sms") ||
                    dialogText.contains("wait for a confirmation")
                
                val isFailure = dialogText.contains("failed") || dialogText.contains("error")
                
                when {
                    isFailure -> {
                        logsManager.addLog(LogStatus.FAILED, "Transaction Failed", dialogText, null, contactId)
                        TransactionEventBus.emit(TransactionResult(
                            success = false,
                            message = "Transaction failed. Please check SMS for details.",
                            amount = null,
                            contactName = contactName ?: phoneNumber
                        ))
                        if (buttonNode != null) clickNode(buttonNode!!)
                        endSession(this@MpesaUssdService)
                    }
                    isDefinitiveSuccess -> {
                        logsManager.addLog(LogStatus.SUCCESS, "Transaction Completed", dialogText, amountDouble, contactId)
                        TransactionEventBus.emit(TransactionResult(
                            success = true,
                            message = "Transfer successful! Please check SMS for confirmation. Auto-closes in 60s",
                            amount = amountDouble,
                            contactName = contactName ?: phoneNumber
                        ))
                        if (buttonNode != null) clickNode(buttonNode!!)
                        endSession(this@MpesaUssdService)
                    }
                    isPending -> {
                        // M-PESA is processing the transfer — money has been sent, SMS will confirm
                        logsManager.addLog(LogStatus.SUCCESS, "Transfer Submitted", dialogText, amountDouble, contactId)
                        TransactionEventBus.emit(TransactionResult(
                            success = true,
                            message = "Transfer submitted! Your request is being processed. Please wait for the confirmation SMS.",
                            amount = amountDouble,
                            contactName = contactName ?: phoneNumber
                        ))
                        if (buttonNode != null) clickNode(buttonNode!!)
                        endSession(this@MpesaUssdService)
                    }
                    else -> {
                        logsManager.addLog(LogStatus.SUCCESS, "Transaction Completed", dialogText, amountDouble, contactId)
                        TransactionEventBus.emit(TransactionResult(
                            success = true,
                            message = "Transaction submitted. Please check SMS for confirmation.",
                            amount = amountDouble,
                            contactName = contactName ?: phoneNumber
                        ))
                        if (buttonNode != null) clickNode(buttonNode!!)
                        endSession(this@MpesaUssdService)
                    }
                }
                actionTaken = true
            } else if (dialogText.replace(" ", "").contains("call*334#") || dialogText.contains("select sim") || dialogText.contains("choose sim") || (dialogText.contains("call") && dialogText.contains("safaricom"))) {
                val clicked = clickFirstSimOption(rootNode)
                if (!clicked) actionTaken = false
            } else if (clickableNodes.isNotEmpty()) {
                val clicked = when {
                    dialogText.contains("send money") && dialogText.contains("withdraw") -> {
                        clickMatchingNode(clickableNodes, "send money")
                    }
                    dialogText.contains("any network") -> {
                         clickMatchingNode(clickableNodes, "any network")
                    }
                    else -> false
                }
                if (!clicked) actionTaken = false
            } else {
                actionTaken = false
            }
            
            if (actionTaken) {
                lastDialogText = dialogText
            }
        }
    }

    private fun getOption(dialogText: String, keyword: String): String? {
        val regex = Regex("(\\d+)[^0-9a-zA-Z]*$keyword", RegexOption.IGNORE_CASE)
        return regex.find(dialogText)?.groupValues?.get(1)
    }
    
    private fun clickMatchingNode(nodes: List<AccessibilityNodeInfo>, keyword: String): Boolean {
        for (node in nodes) {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (text.contains(keyword) || desc.contains(keyword)) {
                clickNode(node)
                return true
            }
        }
        return false
    }

    private fun inputText(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNodes(
        node: AccessibilityNodeInfo,
        onInputFound: (AccessibilityNodeInfo) -> Unit,
        onButtonFound: (AccessibilityNodeInfo) -> Unit,
        allText: java.lang.StringBuilder,
        clickableNodes: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.text != null) {
            allText.append(node.text).append(" ")
        }
        if (node.contentDescription != null) {
            allText.append(node.contentDescription).append(" ")
        }
        
        if (node.isClickable) {
            clickableNodes.add(node)
        }
        
        if (node.className?.toString() == "android.widget.EditText" || node.isEditable) {
            onInputFound(node)
        } else if (node.className?.toString() == "android.widget.Button") {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val isSend = text.contains("send") || text.contains("ok") || text.contains("reply") || text.contains("yes") || text.contains("submit") ||
                         desc.contains("send") || desc.contains("ok") || desc.contains("reply") || desc.contains("yes") || desc.contains("submit")
            
            if (isSend) {
                onButtonFound(node)
            } else {
                onButtonFound(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodes(child, onInputFound, onButtonFound, allText, clickableNodes)
            }
        }
    }

    private fun clickFirstSimOption(root: AccessibilityNodeInfo): Boolean {
        // Step 1: Collect ALL nodes (not just clickable) and look for Safaricom/SIM 1 text
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        fun collectAll(n: AccessibilityNodeInfo) {
            allNodes.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { collectAll(it) }
        }
        collectAll(root)

        // Find the Safaricom / SIM 1 node by text
        val safaricomNode = allNodes.firstOrNull {
            val label = ((it.text?.toString() ?: "") + " " + (it.contentDescription?.toString() ?: "")).lowercase()
            label.contains("safaricom") || label.contains("sim 1") || label.contains("sim1") ||
            label.contains("airtel") == false && label.contains("telkom") == false && label.contains("1") && label.contains("sim")
        } ?: allNodes.firstOrNull {
            val label = ((it.text?.toString() ?: "") + " " + (it.contentDescription?.toString() ?: "")).lowercase()
            label.contains("safaricom")
        }

        if (safaricomNode != null) {
            // Try walking up to find a clickable parent
            var target: AccessibilityNodeInfo? = safaricomNode
            repeat(5) {
                if (target?.isClickable == true) return@repeat
                target = target?.parent
            }
            if (target != null && target!!.isClickable) {
                target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            // ALWAYS also fire a gesture tap directly on the node's screen position
            val rect = android.graphics.Rect()
            safaricomNode.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val path = android.graphics.Path()
                path.moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                    .build()
                dispatchGesture(gesture, null, null)
            }
            return true
        }

        // Step 2: Fallback — collect only clickable nodes, sort by vertical position, tap the topmost
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, candidates)
        val validCandidates = candidates.filter { node ->
            val txt = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val label = txt.ifEmpty { desc }
            label.isNotEmpty() && !label.contains("cancel") && !label.contains("dismiss")
        }
        val sorted = validCandidates.sortedBy { node ->
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }
        sorted.firstOrNull()?.let { node ->
            var target: AccessibilityNodeInfo? = node
            repeat(5) {
                if (target?.isClickable == true) return@repeat
                target = target?.parent
            }
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // Also gesture tap
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0) {
                val path = android.graphics.Path()
                path.moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                    .build()
                dispatchGesture(gesture, null, null)
            }
            return true
        }
        return false
    }

    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectClickable(it, out) }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
        lastDialogText = ""
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        endSession(this)
        serviceScope.cancel()
    }
}
