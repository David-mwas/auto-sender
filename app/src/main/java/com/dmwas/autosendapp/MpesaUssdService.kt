package com.dmwas.autosendapp

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MpesaUssdService : AccessibilityService() {

    private val TAG = "MpesaUssdService"
    @Volatile private var lastDialogText = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val eventMutex = Mutex()
    private val logsManager by lazy { LogsManager(this) }

    private val sessionClaimReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CLAIM_USSD_SESSION) return
            val owner = intent.getStringExtra("owner") ?: return
            if (owner != packageName) {
                val activeAgeMs = System.currentTimeMillis() - sessionStartTimeMs
                if (isSessionActive(this@MpesaUssdService) && activeAgeMs in 0..60_000L) {
                    // User-initiated money transfer in AutoSender is actively running; preempt background statement check
                    Log.d(TAG, "Active AutoSender session preempting $owner")
                    notifyPeerSessionClaimed(this@MpesaUssdService)
                } else {
                    Log.d(TAG, "Yielding USSD session to $owner")
                    endSession(this@MpesaUssdService)
                }
            }
        }
    }

    companion object {
        const val ACTION_CLAIM_USSD_SESSION = "com.dmwas.ussd.ACTION_CLAIM_USSD_SESSION"
        private const val PEER_STATEMENT_PKG = "com.example.statementapp"

        // Held so startSession() can reset lastDialogText for the new send session
        @Volatile private var instance: MpesaUssdService? = null
        @Volatile private var isSessionActiveMem: Boolean = false
        @Volatile private var sessionStartTimeMs: Long = 0L
        @Volatile private var hasLoggedThisSession: Boolean = false
        private val companionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var sessionTimeoutJob: kotlinx.coroutines.Job? = null

        private fun notifyPeerSessionClaimed(context: Context) {
            try {
                context.sendBroadcast(
                    Intent(ACTION_CLAIM_USSD_SESSION).apply {
                        setPackage(PEER_STATEMENT_PKG)
                        putExtra("owner", context.packageName)
                    }
                )
            } catch (e: Exception) {
                Log.w("MpesaUssdService", "Failed to broadcast session claim", e)
            }
        }

        fun startSession(context: Context, scope: CoroutineScope = companionScope) {
            // Reset dedup and single-log guards so a new session starts clean
            instance?.lastDialogText = ""
            hasLoggedThisSession = false
            isSessionActiveMem = true
            sessionStartTimeMs = System.currentTimeMillis()
            context.getSharedPreferences("UssdPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("isSessionActive", true)
                .apply()
            notifyPeerSessionClaimed(context)
            sessionTimeoutJob?.cancel()
            sessionTimeoutJob = companionScope.launch {
                delay(90_000L) // 90-second safety timeout
                endSession(context)
                Log.w("MpesaUssdService", "Session timed out — going dormant")
            }
        }

        fun endSession(context: Context) {
            isSessionActiveMem = false
            sessionStartTimeMs = 0L
            instance?.lastDialogText = ""
            context.getSharedPreferences("UssdPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("isSessionActive", false)
                .apply()
            sessionTimeoutJob?.cancel()
        }

        private fun isSessionActive(context: Context): Boolean {
            return isSessionActiveMem ||
                context.getSharedPreferences("UssdPrefs", Context.MODE_PRIVATE).getBoolean("isSessionActive", false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            ContextCompat.registerReceiver(
                this,
                sessionClaimReceiver,
                IntentFilter(ACTION_CLAIM_USSD_SESSION),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register sessionClaimReceiver", e)
        }
        Log.d(TAG, "Service connected — dormant until USSD session starts")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isSessionActive(this)) return

        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        serviceScope.launch {
            // Serialize event handling so rapid accessibility events never double-click or double-log
            eventMutex.withLock {
                delay(350) // Give the USSD dialog time to render on screen

                if (!isSessionActive(this@MpesaUssdService)) return@withLock

                val rootNode = rootInActiveWindow ?: event?.source ?: return@withLock
                var inputNode: AccessibilityNodeInfo? = null
                var positiveButtonNode: AccessibilityNodeInfo? = null
                var fallbackButtonNode: AccessibilityNodeInfo? = null
                var dismissButtonNode: AccessibilityNodeInfo? = null
                val allText = java.lang.StringBuilder()
                val clickableNodes = mutableListOf<AccessibilityNodeInfo>()

                findNodes(
                    node = rootNode,
                    onInputFound = { inputNode = it },
                    onPositiveButtonFound = { positiveButtonNode = it },
                    onFallbackButtonFound = { if (fallbackButtonNode == null) fallbackButtonNode = it },
                    onDismissButtonFound = { if (dismissButtonNode == null) dismissButtonNode = it },
                    allText = allText,
                    clickableNodes = clickableNodes
                )

                val buttonNode = positiveButtonNode ?: fallbackButtonNode
                val dialogText = allText.toString().lowercase().trim().replace(Regex("\\s+"), " ")
                if (dialogText.isEmpty()) return@withLock

                if (dialogText.contains("autosender active") || dialogText.contains("start autosender")) {
                    return@withLock
                }

                if (com.dmwas.autosendapp.BuildConfig.DEBUG) Log.d(TAG, "USSD Dialog text: $dialogText")

                if (dialogText == lastDialogText) {
                    return@withLock
                }

                val txPrefs = getSharedPreferences("TxPrefs", Context.MODE_PRIVATE)
                val rawPhone = txPrefs.getString("phone_number", "") ?: ""
                val phoneNumber = ContactPickerHelper.normalizePhoneNumber(rawPhone)
                val rawAmount = txPrefs.getString("amount", "") ?: ""
                val ussdAmount = ContactPickerHelper.sanitizeAmountForUssd(rawAmount)
                val amountDouble = ContactPickerHelper.parseAmount(rawAmount)
                val contactId = txPrefs.getString("contact_id", null)
                val contactName = txPrefs.getString("contact_name", null)

                // PIN is read directly from encrypted storage — never stored in plain TxPrefs
                val pin = SecurityHelper.getPin(this@MpesaUssdService) ?: ""

                if (inputNode != null && buttonNode != null) {
                    when {
                        dialogText.contains("send money") && (dialogText.contains("withdraw") || dialogText.contains("airtime") || dialogText.contains("lipa")) -> {
                            lastDialogText = dialogText
                            val opt = getOption(dialogText, "send money") ?: "1"
                            Toast.makeText(applicationContext, "Selecting: Send Money", Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, opt)
                            delay(500)
                            clickNode(buttonNode)
                        }
                        dialogText.contains("any network") -> {
                            lastDialogText = dialogText
                            val opt = getOption(dialogText, "any network") ?: "1"
                            Toast.makeText(applicationContext, "Selecting: Send Money to Any Network ($opt)", Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, opt)
                            delay(500)
                            clickNode(buttonNode)
                        }
                        dialogText.contains("enter phone no") || dialogText.contains("enter phone number") || dialogText.contains("phone no") -> {
                            if (phoneNumber.isNotEmpty()) {
                                lastDialogText = dialogText
                                Toast.makeText(applicationContext, "Entering Phone Number", Toast.LENGTH_SHORT).show()
                                inputText(inputNode!!, phoneNumber)
                                delay(500)
                                clickNode(buttonNode)
                            }
                        }
                        dialogText.contains("enter amount") -> {
                            if (ussdAmount.isNotEmpty()) {
                                lastDialogText = dialogText
                                Toast.makeText(applicationContext, "Entering Amount", Toast.LENGTH_SHORT).show()
                                inputText(inputNode!!, ussdAmount)
                                delay(500)
                                clickNode(buttonNode)
                            }
                        }
                        dialogText.contains("enter m-pesa pin") || dialogText.contains("enter pin") || dialogText.contains("mpesa pin") -> {
                            if (pin.isNotEmpty()) {
                                lastDialogText = dialogText
                                Toast.makeText(applicationContext, "Entering PIN", Toast.LENGTH_SHORT).show()
                                inputText(inputNode!!, pin)
                                delay(500)
                                clickNode(buttonNode)
                            }
                        }
                        (dialogText.contains("send") || dialogText.contains("transfer") || dialogText.contains("pay")) &&
                            (dialogText.contains("ksh") || dialogText.contains("kes")) &&
                            (dialogText.contains("accept") || dialogText.contains("confirm") || dialogText.contains("reply with 1") || dialogText.contains("1. yes") || dialogText.contains("1:yes")) -> {
                            lastDialogText = dialogText
                            val opt = when {
                                dialogText.contains("accept") -> getOption(dialogText, "accept") ?: "1"
                                dialogText.contains("confirm") -> getOption(dialogText, "confirm") ?: "1"
                                else -> "1"
                            }
                            Toast.makeText(applicationContext, "Confirming Transfer", Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, opt)
                            delay(500)
                            clickNode(buttonNode)
                            // Do NOT end session here. Wait for the final success/failure dialog.
                        }
                    }
                } else if (isTerminalDialog(dialogText)) {
                    if (hasLoggedThisSession) return@withLock
                    hasLoggedThisSession = true
                    lastDialogText = dialogText
                    endSession(this@MpesaUssdService)

                    val isFailure = isFailureDialog(dialogText)
                    val isDefinitiveSuccess = !isFailure && (
                        dialogText.contains("transfer successful") ||
                        dialogText.contains("you have sent") ||
                        (dialogText.contains("sent to") && (dialogText.contains("ksh") || dialogText.contains("kes"))) ||
                        dialogText.contains("m-pesa confirmed") ||
                        (dialogText.contains("success") && (dialogText.contains("ksh") || dialogText.contains("kes")))
                    )
                    val isPending = !isFailure && (
                        dialogText.contains("request is being processed") ||
                        dialogText.contains("wait for an sms") ||
                        dialogText.contains("wait for sms") ||
                        dialogText.contains("wait for a confirmation") ||
                        dialogText.contains("wait for m-pesa")
                    )

                    val closeBtn = buttonNode ?: dismissButtonNode

                    when {
                        isFailure -> {
                            logsManager.addLogAndWait(LogStatus.FAILED, "Transaction Failed", dialogText, null, contactId)
                            TransactionEventBus.emit(TransactionResult(
                                success = false,
                                message = "Transaction failed: $dialogText",
                                amount = null,
                                contactName = contactName ?: phoneNumber
                            ))
                            if (closeBtn != null) clickNode(closeBtn)
                        }
                        isDefinitiveSuccess -> {
                            logsManager.addLogAndWait(LogStatus.SUCCESS, "Transaction Completed", dialogText, amountDouble, contactId)
                            TransactionEventBus.emit(TransactionResult(
                                success = true,
                                message = "Transfer successful! Please check SMS for confirmation. Auto-closes in 60s",
                                amount = amountDouble,
                                contactName = contactName ?: phoneNumber
                            ))
                            if (closeBtn != null) clickNode(closeBtn)
                        }
                        isPending -> {
                            logsManager.addLogAndWait(LogStatus.SUCCESS, "Transfer Submitted", dialogText, amountDouble, contactId)
                            TransactionEventBus.emit(TransactionResult(
                                success = true,
                                message = "Transfer submitted! Your request is being processed. Please check your M-PESA SMS to confirm.",
                                amount = amountDouble,
                                contactName = contactName ?: phoneNumber
                            ))
                            if (closeBtn != null) clickNode(closeBtn)
                        }
                        else -> {
                            logsManager.addLogAndWait(LogStatus.SUCCESS, "Transaction Completed", dialogText, amountDouble, contactId)
                            TransactionEventBus.emit(TransactionResult(
                                success = true,
                                message = "Transaction submitted. Please check SMS for confirmation.",
                                amount = amountDouble,
                                contactName = contactName ?: phoneNumber
                            ))
                            if (closeBtn != null) clickNode(closeBtn)
                        }
                    }
                } else if (
                    dialogText.replace(" ", "").contains("call*334#") ||
                    dialogText.contains("select sim") ||
                    dialogText.contains("choose sim") ||
                    (dialogText.contains("call") && dialogText.contains("safaricom"))
                ) {
                    if (clickFirstSimOption(rootNode)) {
                        lastDialogText = dialogText
                    }
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
                    if (clicked) {
                        lastDialogText = dialogText
                    }
                }
            }
        }
    }

    private fun isFailureDialog(dialogText: String): Boolean {
        return dialogText.contains("failed") ||
            dialogText.contains("error") ||
            dialogText.contains("insufficient") ||
            dialogText.contains("wrong pin") ||
            dialogText.contains("invalid") ||
            dialogText.contains("cancelled") ||
            dialogText.contains("canceled") ||
            dialogText.contains("unable to process") ||
            dialogText.contains("connection problem") ||
            dialogText.contains("mmi code") ||
            dialogText.contains("not allowed") ||
            dialogText.contains("exceeded") ||
            dialogText.contains("unavailable") ||
            dialogText.contains("timed out") ||
            dialogText.contains("blocked") ||
            dialogText.contains("declined")
    }

    private fun isTerminalDialog(dialogText: String): Boolean {
        return isFailureDialog(dialogText) ||
            dialogText.contains("transfer successful") ||
            dialogText.contains("you have sent") ||
            dialogText.contains("sent to") ||
            dialogText.contains("m-pesa confirmed") ||
            (dialogText.contains("success") && (dialogText.contains("ksh") || dialogText.contains("kes") || dialogText.contains("sent"))) ||
            dialogText.contains("request is being processed") ||
            dialogText.contains("wait for an sms") ||
            dialogText.contains("wait for sms") ||
            dialogText.contains("wait for a confirmation") ||
            dialogText.contains("wait for m-pesa")
    }

    private fun getOption(dialogText: String, keyword: String): String? {
        // Match menu option digits 1-9 (never 0 from amounts like "Fee Ksh 0. Accept")
        val regex = Regex("(?:^|\\s)([1-9])\\s*[.:)\\-]?\\s*$keyword", RegexOption.IGNORE_CASE)
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
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
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
        onPositiveButtonFound: (AccessibilityNodeInfo) -> Unit,
        onFallbackButtonFound: (AccessibilityNodeInfo) -> Unit,
        onDismissButtonFound: (AccessibilityNodeInfo) -> Unit,
        allText: java.lang.StringBuilder,
        clickableNodes: MutableList<AccessibilityNodeInfo>
    ) {
        val isEditText = node.className?.toString() == "android.widget.EditText" || node.isEditable
        val isButton = node.className?.toString() == "android.widget.Button"

        // Exclude EditText content from allText so typing into the field doesn't change the dialog fingerprint
        if (!isEditText) {
            if (node.text != null) {
                allText.append(node.text).append(" ")
            }
            if (node.contentDescription != null) {
                allText.append(node.contentDescription).append(" ")
            }
        }

        if (node.isClickable) {
            clickableNodes.add(node)
        }

        if (isEditText) {
            onInputFound(node)
        } else if (isButton) {
            val text = node.text?.toString()?.lowercase()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
            val isPositive = text.contains("send") || text.contains("ok") || text.contains("reply") ||
                text.contains("yes") || text.contains("submit") || text.contains("accept") || text.contains("confirm") ||
                desc.contains("send") || desc.contains("ok") || desc.contains("reply") ||
                desc.contains("yes") || desc.contains("submit") || desc.contains("accept") || desc.contains("confirm")
            val isCancel = text.contains("cancel") || text.contains("dismiss") || text.contains("no") ||
                desc.contains("cancel") || desc.contains("dismiss") || desc.contains("no")

            when {
                isPositive -> onPositiveButtonFound(node)
                isCancel -> onDismissButtonFound(node)
                else -> onFallbackButtonFound(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodes(
                    node = child,
                    onInputFound = onInputFound,
                    onPositiveButtonFound = onPositiveButtonFound,
                    onFallbackButtonFound = onFallbackButtonFound,
                    onDismissButtonFound = onDismissButtonFound,
                    allText = allText,
                    clickableNodes = clickableNodes
                )
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
                (!label.contains("airtel") && !label.contains("telkom") && label.contains("1") && label.contains("sim"))
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
            val clickedViaAction = if (target != null && target!!.isClickable) {
                target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                false
            }
            if (!clickedViaAction) {
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
            val clickedViaAction = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (!clickedViaAction) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val path = android.graphics.Path()
                    path.moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                        .build()
                    dispatchGesture(gesture, null, null)
                }
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
        try {
            unregisterReceiver(sessionClaimReceiver)
        } catch (_: Exception) {}
        instance = null
        endSession(this)
        serviceScope.cancel()
    }
}
