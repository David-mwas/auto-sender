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
        private var sessionTimeoutJob: kotlinx.coroutines.Job? = null

        fun startSession(context: Context, scope: CoroutineScope) {
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
        Log.d(TAG, "Service connected — dormant until USSD session starts")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val prefs = getSharedPreferences("UssdPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("isSessionActive", false)) return

        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        serviceScope.launch {
            delay(400) // Give the normal USSD dialog time to fully render on screen

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
            
            Log.d(TAG, "USSD Dialog text: $dialogText")

            if (dialogText == lastDialogText) {
                return@launch 
            }
            
            // Record errors if the USSD throws an explicit error message
            if (dialogText.contains("error") || dialogText.contains("m-pesa cannot") || dialogText.contains("invalid")) {
                logsManager.addLog(LogStatus.ERROR, "USSD Error", dialogText)
            }

            delay(400)
            var actionTaken = true

            val txPrefs = getSharedPreferences("TxPrefs", Context.MODE_PRIVATE)
            val phoneNumber = txPrefs.getString("phone_number", "") ?: ""
            val amount = txPrefs.getString("amount", "") ?: ""
            val amountDouble = amount.toDoubleOrNull()
            val contactId = txPrefs.getString("contact_id", null)
            val contactName = txPrefs.getString("contact_name", null)
            
            val securePrefs = getSharedPreferences("SecurePrefs", Context.MODE_PRIVATE)
            val pin = txPrefs.getString("mpesa_pin", "") ?: ""

            if (inputNode != null && buttonNode != null) {
                when {
                    dialogText.contains("send money") && dialogText.contains("withdraw") -> {
                        Toast.makeText(applicationContext, "Selecting: Send Money", Toast.LENGTH_SHORT).show()
                        logsManager.addLog(LogStatus.INFO, "Started Sending Money", "Navigating Send Money Menu")
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
                        logsManager.addLog(LogStatus.INFO, "Confirming Transfer", "Auto-accepting confirmation dialog: $dialogText")
                        inputText(inputNode!!, opt)
                        delay(800)
                        clickNode(buttonNode!!)
                        // Do NOT end session here. Wait for the final success/failure dialog.
                    }
                    else -> {
                        actionTaken = false
                    }
                }
            } else if (dialogText.contains("success") || dialogText.contains("received") || dialogText.contains("failed") || dialogText.contains("request is being processed") || dialogText.contains("wait for an sms")) {
                val status = if (dialogText.contains("failed") || dialogText.contains("error")) LogStatus.FAILED else LogStatus.SUCCESS
                val msg = if (status == LogStatus.SUCCESS) "Transaction successful. Please check SMS for confirmation." else "Transaction failed. Please check SMS for details."
                
                logsManager.addLog(status, "Transaction Completed", dialogText, amountDouble, contactId)
                
                TransactionEventBus.emit(TransactionResult(
                    success = status == LogStatus.SUCCESS,
                    message = msg,
                    amount = amountDouble,
                    contactName = contactName ?: phoneNumber
                ))
                
                if (buttonNode != null) clickNode(buttonNode!!)
                endSession(this@MpesaUssdService)
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
        endSession(this)
        serviceScope.cancel()
    }
}
