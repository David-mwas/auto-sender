package com.dmwas.autosendapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.dmwas.autosendapp.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : FragmentActivity() {

    private lateinit var contactsManager: ContactsManager
    private lateinit var logsManager: LogsManager
    private lateinit var themeManager: ThemeManager

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Call permission is required.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        contactsManager = ContactsManager(this)
        logsManager = LogsManager(this)
        themeManager = ThemeManager(this)

        setContent {
            val themeMode by themeManager.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> systemDark
            }

            AutoSendAppTheme(darkTheme = isDark) {
                var isAuthenticated by remember { mutableStateOf(false) }
                var showPermissionDialog by remember { mutableStateOf(false) }

                // Auto-lock: poll every 30s; re-lock if idle timeout exceeded
                LaunchedEffect(isAuthenticated) {
                    if (isAuthenticated) {
                        while (true) {
                            delay(30_000)
                            if (LockManager.isLocked(this@MainActivity)) {
                                isAuthenticated = false
                            }
                        }
                    }
                }

                if (!isAuthenticated) {
                    AppLockScreen(
                        onUnlock = {
                            LockManager.resetTimer()
                            isAuthenticated = true
                        },
                        onBiometricUnlock = {
                            authenticateForAccess {
                                LockManager.resetTimer()
                                isAuthenticated = true
                            }
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AutoSenderApp(
                            contactsManager = contactsManager,
                            logsManager = logsManager,
                            themeManager = themeManager,
                            onSendMoney = { contact, amount ->
                                LockManager.recordActivity()
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                                    showPermissionDialog = true
                                } else {
                                    initiateTransfer(contact, amount)
                                }
                            },
                            onOpenSettings = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onLock = {
                                LockManager.resetTimer()
                                isAuthenticated = false
                            }
                        )

                        if (showPermissionDialog) {
                            AlertDialog(
                                onDismissRequest = { showPermissionDialog = false },
                                title = { Text("Permission Required", fontWeight = FontWeight.Bold) },
                                text = { Text("AutoSender needs to make phone calls to dial *334# for M-PESA. Tap Grant to allow, or Open Settings if the system blocks the request.") },
                                confirmButton = {
                                    Button(onClick = {
                                        showPermissionDialog = false
                                        androidx.core.app.ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE), 101)
                                    }) { Text("Grant") }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        showPermissionDialog = false
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        intent.data = Uri.fromParts("package", packageName, null)
                                        startActivity(intent)
                                    }) { Text("Open Settings") }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        var accessibilityEnabled = 0
        val service = packageName + "/" + MpesaUssdService::class.java.canonicalName
        try {
            accessibilityEnabled = Settings.Secure.getInt(applicationContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) { }
        val textString = Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return accessibilityEnabled == 1 && textString?.contains(service) == true
    }

    private fun authenticateForAccess(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Toast.makeText(applicationContext, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
            }
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(applicationContext, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AutoSender")
            .setSubtitle("Use your device credentials to continue")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Checks if a screen recording / cast is currently active.
     * Android restricts USSD dialog visibility during recording for security reasons,
     * which can prevent the accessibility service from reading and interacting with
     * the M-PESA USSD dialog.
     */
    private fun isScreenBeingRecorded(): Boolean {
        return try {
            val mediaProjectionManager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            // The most reliable way: check if any MediaProjection is active via Android API
            // We use the display flags as a proxy — FLAG_SECURE prevents screenshots/recording
            val wm = getSystemService(android.view.WindowManager::class.java)
            // Alternative: check if screen mirroring is active via DisplayManager
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            val displays = dm?.displays ?: emptyArray()
            // Virtual displays (type VIRTUAL) indicate screen recording/mirroring is active
            displays.any { it.displayId != android.view.Display.DEFAULT_DISPLAY }
        } catch (e: Exception) {
            false
        }
    }

    private fun initiateTransfer(contact: Contact, amount: String) {
        val pin = SecurityHelper.getPin(this)
        if (pin.isNullOrEmpty()) {
            Toast.makeText(this, "Please set your M-PESA PIN in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable AutoSender in Accessibility Settings", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        // Warn user if screen recording is active — Android security blocks USSD dialog during recording
        if (isScreenBeingRecorded()) {
            Toast.makeText(
                this,
                "⚠️ Screen recording detected! Please stop recording before sending. Android blocks M-PESA USSD dialogs during screen recording for security.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val txPrefs = getSharedPreferences("TxPrefs", Context.MODE_PRIVATE)
        txPrefs.edit()
            .putString("phone_number", contact.phoneNumber)
            .putString("contact_id", contact.id)
            .putString("contact_name", contact.name)
            .putString("amount", amount)
            // PIN is NOT stored here — service reads it directly from EncryptedSharedPreferences
            .apply()
        MpesaUssdService.startSession(this, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:*334%23")
            intent.putExtra("com.android.phone.force.slot", true)
            intent.putExtra("Cdma_Supp", true)
            intent.putExtra("simSlot", 0)
            intent.putExtra("com.android.phone.extra.slot", 0)
            
            // Standard Android TelecomManager approach to force SIM 1
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            if (telecomManager != null && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val phoneAccounts = telecomManager.callCapablePhoneAccounts
                    if (phoneAccounts != null && phoneAccounts.isNotEmpty()) {
                        // Phone accounts are usually ordered by SIM slot (SIM 1 = 0, SIM 2 = 1)
                        val sim1Account = phoneAccounts[0]
                        intent.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim1Account)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Call permission is required.", Toast.LENGTH_LONG).show()
        }
    }
}

// ─────────────────────────────────────────────
//  APP LOCK SCREEN
// ─────────────────────────────────────────────
@Composable
fun AppLockScreen(onUnlock: () -> Unit, onBiometricUnlock: () -> Unit) {
    val context = LocalContext.current
    val savedPin = SecurityHelper.getPin(context)
    var manualPin by remember { mutableStateOf("") }
    
    // Shake animation for error
    val shakeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    
    fun triggerErrorShake() {
        coroutineScope.launch {
            shakeOffset.animateTo(15f, animationSpec = tween(50, easing = LinearEasing))
            shakeOffset.animateTo(-15f, animationSpec = tween(50, easing = LinearEasing))
            shakeOffset.animateTo(15f, animationSpec = tween(50, easing = LinearEasing))
            shakeOffset.animateTo(-15f, animationSpec = tween(50, easing = LinearEasing))
            shakeOffset.animateTo(0f, animationSpec = tween(50, easing = LinearEasing))
            manualPin = ""
            // Haptic feedback (simple workaround)
            android.view.HapticFeedbackConstants.LONG_PRESS.let { 
                (context as? android.app.Activity)?.window?.decorView?.performHapticFeedback(it) 
            }
        }
    }

    val isDark = isSystemInDarkTheme()
    val headerGradient = if (isDark)
        Brush.linearGradient(
            colors = listOf(DarkGradientStart, DarkGradientMid, DarkGradientEnd),
            start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    else
        Brush.linearGradient(
            colors = listOf(GradientStart, GradientMid, GradientEnd),
            start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(headerGradient)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        // Background decorative circles
        Box(modifier = Modifier.size(240.dp).offset(x = 120.dp, y = (-120).dp).align(Alignment.TopEnd).clip(CircleShape).background(Color.White.copy(alpha = 0.03f)))
        Box(modifier = Modifier.size(160.dp).offset(x = (-60).dp, y = 80.dp).align(Alignment.BottomStart).clip(CircleShape).background(Color.White.copy(alpha = 0.03f)))

        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pulse animation for logo
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 0.95f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse_scale"
            )
            val glowAlpha by infiniteTransition.animateFloat(
                initialValue = 0.05f,
                targetValue = 0.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glow_alpha"
            )

            // Logo area
            Box(contentAlignment = Alignment.Center) {
                // Glow ring
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = glowAlpha))
                )
                // Logo bg
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("AutoSender", style = MaterialTheme.typography.headlineLarge, color = Color.White, fontWeight = FontWeight.ExtraBold)
            Text("Secure M-PESA Automation", style = MaterialTheme.typography.bodyMedium, color = MpesaAccentGreen, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Spacer(modifier = Modifier.height(48.dp))

            // Biometric button
            Button(
                onClick = onBiometricUnlock,
                modifier = Modifier.fillMaxWidth(0.85f).height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f), contentColor = Color.White)
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Unlock with Biometric", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }

            if (!savedPin.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                
                // PIN dots display
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.offset(x = shakeOffset.value.dp)
                ) {
                    // Assuming 4-digit PIN for the visual dots, but savedPin length might vary.
                    // Let's just show up to the savedPin length dots.
                    val pinLength = savedPin.length
                    for (i in 0 until pinLength) {
                        val isFilled = i < manualPin.length
                        val dotColor = if (isFilled) Color.White else Color.White.copy(alpha = 0.2f)
                        val dotSize = if (isFilled) 14.dp else 12.dp
                        Box(
                            modifier = Modifier
                                .size(16.dp) // Fixed container
                                .align(Alignment.CenterVertically)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(dotSize)
                                    .clip(CircleShape)
                                    .background(dotColor)
                                    .align(Alignment.Center)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // Custom numeric keypad
                Column(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val keyRows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("", "0", "⌫")
                    )
                    
                    for (row in keyRows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            for (key in row) {
                                if (key.isEmpty()) {
                                    Spacer(modifier = Modifier.size(72.dp))
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = if (key == "⌫") 0.05f else 0.1f))
                                            .clickable {
                                                if (key == "⌫") {
                                                    if (manualPin.isNotEmpty()) {
                                                        manualPin = manualPin.dropLast(1)
                                                    }
                                                } else {
                                                    if (manualPin.length < savedPin.length) {
                                                        manualPin += key
                                                    }
                                                    if (manualPin.length == savedPin.length) {
                                                        if (manualPin == savedPin) {
                                                            onUnlock()
                                                        } else {
                                                            triggerErrorShake()
                                                        }
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = key,
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
//  MAIN APP SHELL
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoSenderApp(
    contactsManager: ContactsManager,
    logsManager: LogsManager,
    themeManager: ThemeManager,
    onSendMoney: (Contact, String) -> Unit,
    onOpenSettings: () -> Unit,
    onLock: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var contacts by remember { mutableStateOf(contactsManager.getContacts()) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var transactionModal by remember { mutableStateOf<TransactionResult?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    val context = LocalContext.current as? MainActivity
    LaunchedEffect(Unit) {
        if (context != null && (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)) {
            androidx.core.app.ActivityCompat.requestPermissions(context, arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE), 101)
        }
        TransactionEventBus.events.collect { result ->
            transactionModal = result
            refreshTrigger++ // Trigger optimistic updates
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
            val items = listOf(
                Triple(Icons.Default.Home, "Home", 0),
                Triple(Icons.AutoMirrored.Filled.List, "Logs", 1),
                Triple(Icons.Default.Settings, "Settings", 2)
            )
            items.forEach { (icon, label, index) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp)) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = { showAddContactDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Contact", modifier = Modifier.size(28.dp))
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> HomeTab(
                    contacts = contacts,
                    contactsManager = contactsManager,
                    logsManager = logsManager,
                    refreshTrigger = refreshTrigger,
                    onContactsChanged = { contacts = contactsManager.getContacts() },
                    showAddContactDialog = showAddContactDialog,
                    onCloseAddContact = { showAddContactDialog = false },
                    onSendMoney = onSendMoney
                )
                1 -> LogsTab(logsManager = logsManager, refreshTrigger = refreshTrigger)
                2 -> SettingsTab(themeManager = themeManager, onOpenSettings = onOpenSettings, onLock = onLock)
            }
        }
    }

    if (transactionModal != null) {
        TransactionResultModal(result = transactionModal!!, onDismiss = { transactionModal = null })
    }
}

// ─────────────────────────────────────────────
//  HOME TAB
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HomeTab(
    contacts: List<Contact>,
    contactsManager: ContactsManager,
    logsManager: LogsManager,
    refreshTrigger: Int,
    onContactsChanged: () -> Unit,
    showAddContactDialog: Boolean,
    onCloseAddContact: () -> Unit,
    onSendMoney: (Contact, String) -> Unit
) {
    var editContact by remember { mutableStateOf<Contact?>(null) }
    var selectedContact by remember { mutableStateOf<Contact?>(null) }

    if (selectedContact != null) {
        ContactDetailScreen(
            contact = selectedContact!!,
            logsManager = logsManager,
            refreshTrigger = refreshTrigger,
            onBack = { selectedContact = null },
            onSend = { amount -> onSendMoney(selectedContact!!, amount) }
        )
        return
    }

    val totalAllTime = remember(contacts, refreshTrigger) { logsManager.getTotalSentAllTime() }
    val totalToday = remember(contacts, refreshTrigger) { logsManager.getTotalSentToday() }

    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            onContactsChanged()
            coroutineScope.launch { delay(600); isRefreshing = false }
        }
    )
    
    var searchQuery by remember { mutableStateOf("") }
    var visibleItemCount by remember { mutableStateOf(20) }
    val listState = rememberLazyListState()
    
    var isInitialLoad by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(800)
        isInitialLoad = false
    }
    
    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isEmpty()) contacts
        else contacts.filter { it.name.contains(searchQuery, ignoreCase = true) || it.phoneNumber.contains(searchQuery) }
    }
    
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { index ->
                if (index != null && index >= visibleItemCount - 2) {
                    visibleItemCount += 20
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize().pullRefresh(refreshState)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
        val isDark = isSystemInDarkTheme()
        val headerGradient = if (isDark)
            Brush.linearGradient(
                colors = listOf(DarkGradientStart, DarkGradientMid, DarkGradientEnd),
                start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            )
        else
            Brush.linearGradient(
                colors = listOf(GradientStart, GradientMid, GradientEnd),
                start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerGradient)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // Decorative circle top-right
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .offset(x = 80.dp, y = (-60).dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.04f))
            )
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(8.dp).clip(CircleShape).background(MpesaAccentGreen)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AutoSender", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f), letterSpacing = 1.sp)
                }
                Text("Send Money", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.ExtraBold)
                Spacer(modifier = Modifier.height(20.dp))
                VirtualCreditCard(totalToday = totalToday, totalAllTime = totalAllTime)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Your Contacts (${filteredContacts.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by name or number...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (isInitialLoad && contacts.isEmpty()) {
                items(5) {
                    ShimmerContactCard()
                }
            } else {
                items(filteredContacts.take(visibleItemCount)) { contact ->
                    ContactCard(
                        contact = contact,
                        logsManager = logsManager,
                        refreshTrigger = refreshTrigger,
                        onClick = { selectedContact = contact },
                        onEdit = { editContact = contact },
                        onDelete = {
                            contactsManager.removeContact(contact.id)
                            onContactsChanged()
                        }
                    )
                }
                if (contacts.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.People,
                            title = "No Contacts Yet",
                            subtitle = "Tap the + button to add your first recipient"
                        )
                    }
                }
            }
        }
        }
        PullRefreshIndicator(
            refreshing = isRefreshing, 
            state = refreshState, 
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        )
    }

    if (editContact != null) {
        var name by remember { mutableStateOf(editContact!!.name) }
        var phone by remember { mutableStateOf(editContact!!.phoneNumber) }
        AlertDialog(
            onDismissRequest = { editContact = null },
            title = { Text("Edit Contact", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Full Name") },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it },
                        label = { Text("Phone Number") },
                        leadingIcon = { Icon(Icons.Default.Phone, null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        contactsManager.updateContact(Contact(editContact!!.id, name, phone))
                        onContactsChanged(); editContact = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editContact = null }) { Text("Cancel") } }
        )
    }

    if (showAddContactDialog) {
        QuickSendDialog(
            contactsManager = contactsManager,
            onDismiss = onCloseAddContact,
            onContactsChanged = onContactsChanged,
            onSendMoney = onSendMoney
        )
    }
}

@Composable
fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, isDark: Boolean = false) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        }
    }
}

@Composable
fun ContactCard(
    contact: Contact,
    logsManager: LogsManager,
    refreshTrigger: Int,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val lastTx = remember(contact, refreshTrigger) {
        logsManager.getLogs().firstOrNull { it.contactId == contact.id && it.status == LogStatus.SUCCESS }
    }
    val initials = contact.name.split(" ").take(2).joinToString("") { it.firstOrNull()?.toString() ?: "" }.uppercase()

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "card_scale"
    )
    val elevation by animateDpAsState(
        targetValue = if (isPressed) 1.dp else 4.dp,
        animationSpec = tween(150),
        label = "card_elevation"
    )

    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Avatar gradient - each contact gets a deterministic color based on initials
    val avatarHue = (initials.hashCode().and(0xFF) / 255f) * 120f + 90f // greens 90-210
    val avatarColor1 = Color.hsv(avatarHue, 0.65f, 0.7f)
    val avatarColor2 = Color.hsv((avatarHue + 30f) % 360f, 0.75f, 0.55f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(elevation, RoundedCornerShape(18.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gradient avatar
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(avatarColor1, avatarColor2))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    initials,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(contact.phoneNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (lastTx != null && lastTx.amount != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SuccessGreen.copy(alpha = 0.10f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(10.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Last: KES ${"%.0f".format(lastTx.amount)} · ${SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(lastTx.timestamp))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SuccessGreen
                        )
                    }
                }
            }
            // Action buttons with subtle backgrounds
            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ErrorRed.copy(alpha = 0.10f))
            ) {
                Icon(Icons.Default.Delete, null, tint = ErrorRed, modifier = Modifier.size(20.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Contact", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete ${contact.name}? This will not delete their transaction logs.") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ─────────────────────────────────────────────
//  CONTACT DETAIL SCREEN
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun ContactDetailScreen(
    contact: Contact,
    logsManager: LogsManager,
    refreshTrigger: Int,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    val totalAllTime = remember(contact, refreshTrigger) { logsManager.getTotalSentAllTime(contact.id) }
    val totalToday = remember(contact, refreshTrigger) { logsManager.getTotalSentToday(contact.id) }
    val contactLogs = remember(contact, refreshTrigger) { logsManager.getLogs().filter { it.contactId == contact.id } }
    var showSendDialog by remember { mutableStateOf(false) }
    val initials = contact.name.split(" ").take(2).joinToString("") { it.firstOrNull()?.toString() ?: "" }.uppercase()

    var filterStatus by remember { mutableStateOf<LogStatus?>(null) }
    var visibleItemCount by remember { mutableStateOf(20) }
    val listState = rememberLazyListState()
    
    val filteredLogs = remember(contactLogs, filterStatus) {
        contactLogs
            .filter { filterStatus == null || it.status == filterStatus }
            .sortedByDescending { it.timestamp }
    }
    
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { index ->
                if (index != null && index >= visibleItemCount - 2) {
                    visibleItemCount += 20
                }
            }
    }

    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            coroutineScope.launch { delay(600); isRefreshing = false }
        }
    )

    Box(modifier = Modifier.fillMaxSize().pullRefresh(refreshState)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Gradient Header
        val isDark = isSystemInDarkTheme()
        val headerGradient = if (isDark)
            Brush.linearGradient(
                colors = listOf(DarkGradientStart, DarkGradientMid, DarkGradientEnd),
                start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            )
        else
            Brush.linearGradient(
                colors = listOf(GradientStart, GradientMid, GradientEnd),
                start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            )
        val avatarHue = (initials.hashCode().and(0xFF) / 255f) * 120f + 90f
        val avatarColor1 = Color.hsv(avatarHue, 0.65f, 0.7f)
        val avatarColor2 = Color.hsv((avatarHue + 30f) % 360f, 0.75f, 0.55f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerGradient)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Decorative circles
            Box(modifier = Modifier.size(200.dp).offset(x = 60.dp, y = (-80).dp).align(Alignment.TopEnd).clip(CircleShape).background(Color.White.copy(alpha = 0.04f)))
            Box(modifier = Modifier.size(100.dp).offset(x = (-30).dp, y = 20.dp).align(Alignment.BottomStart).clip(CircleShape).background(Color.White.copy(alpha = 0.03f)))

            Column(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(GlassWhite12)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(avatarColor1, avatarColor2)))
                            .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initials, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(contact.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.ExtraBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Phone, null, tint = MpesaAccentGreen, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(contact.phoneNumber, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                // Stats row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Today
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(GlassWhite12).padding(12.dp)
                    ) {
                        Column {
                            Text("SENT TODAY", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f), letterSpacing = 0.8.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("KES ${"%.2f".format(totalToday)}", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    // All time
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(GlassWhite12).padding(12.dp)
                    ) {
                        Column {
                            Text("ALL TIME", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f), letterSpacing = 0.8.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("KES ${"%.2f".format(totalAllTime)}", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Animated Send Button
                val sendInteraction = remember { MutableInteractionSource() }
                val isSendPressed by sendInteraction.collectIsPressedAsState()
                val sendScale by animateFloatAsState(
                    targetValue = if (isSendPressed) 0.96f else 1f,
                    animationSpec = spring(Spring.DampingRatioMediumBouncy),
                    label = "send_scale"
                )
                Button(
                    onClick = { showSendDialog = true },
                    modifier = Modifier.fillMaxWidth().height(54.dp).scale(sendScale),
                    shape = RoundedCornerShape(16.dp),
                    interactionSource = sendInteraction,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = GradientStart),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 1.dp)
                ) {
                    Icon(Icons.Default.SendTimeExtension, null, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Send Money", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Activity", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filterStatus == null, onClick = { filterStatus = null }, label = { Text("All") })
                    FilterChip(selected = filterStatus == LogStatus.SUCCESS, onClick = { filterStatus = LogStatus.SUCCESS }, label = { Text("Success") })
                    FilterChip(selected = filterStatus == LogStatus.FAILED, onClick = { filterStatus = LogStatus.FAILED }, label = { Text("Failed") })
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(filteredLogs.take(visibleItemCount)) { log -> LogItemCard(log) }
            if (filteredLogs.isEmpty()) {
                item { EmptyState(Icons.Default.ReceiptLong, "No Activity", "Transactions will appear here after you send money") }
            }
        }
        }
        PullRefreshIndicator(
            refreshing = isRefreshing, 
            state = refreshState, 
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        )
    }

    if (showSendDialog) {
        var amount by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSendDialog = false },
            title = { Text("Send to ${contact.name}", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it },
                    label = { Text("Amount (KES)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    prefix = { Text("KES ", fontWeight = FontWeight.SemiBold) }
                )
            },
            confirmButton = {
                Button(
                    onClick = { if (amount.isNotBlank()) { onSend(amount); showSendDialog = false } },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                ) { Text("Send Now", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showSendDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        )
    }
}

// ─────────────────────────────────────────────
//  LOGS TAB
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun LogsTab(logsManager: LogsManager, refreshTrigger: Int) {
    val context = LocalContext.current
    var allLogs by remember { mutableStateOf(logsManager.getLogs()) }
    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf<LogStatus?>(null) }
    var selectedLog by remember { mutableStateOf<TransactionLog?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        allLogs = logsManager.getLogs()
    }
    
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            allLogs = logsManager.getLogs()
            coroutineScope.launch { delay(600); isRefreshing = false }
        }
    )
    
    var isInitialLoad by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(800)
        isInitialLoad = false
    }
    
    val filteredLogs = remember(allLogs, searchQuery, filterStatus) {
        allLogs.filter { log ->
            val matchesSearch = searchQuery.isBlank() || log.message.contains(searchQuery, true) || log.contextDetails.contains(searchQuery, true)
            val matchesFilter = filterStatus == null || log.status == filterStatus
            matchesSearch && matchesFilter
        }
    }

    var visibleItemCount by remember { mutableStateOf(20) }
    val listState = rememberLazyListState()
    
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { index ->
                if (index != null && index >= visibleItemCount - 2) {
                    visibleItemCount += 20
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize().pullRefresh(refreshState)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
        val isDark = isSystemInDarkTheme()
        val headerGradient = if (isDark)
            Brush.linearGradient(
                colors = listOf(DarkGradientStart, DarkGradientMid, DarkGradientEnd),
                start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            )
        else
            Brush.linearGradient(
                colors = listOf(GradientStart, GradientMid, GradientEnd),
                start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerGradient)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Decorative circle
            Box(modifier = Modifier.size(140.dp).offset(x = 40.dp, y = (-40).dp).align(Alignment.TopEnd).clip(CircleShape).background(Color.White.copy(alpha = 0.04f)))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(MpesaAccentGreen))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("AutoSender", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f), letterSpacing = 0.8.sp)
                    }
                    Text("Transaction Logs", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.ExtraBold)
                    Text("${filteredLogs.size} of ${allLogs.size} records", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Export button
                    IconButton(
                        onClick = { showExportDialog = true },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Export Logs",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // Clear logs button
                    IconButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFB71C1C).copy(alpha = 0.3f))
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = "Clear All Logs",
                            tint = Color(0xFFFF8A80),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 16.dp)) {
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                placeholder = { Text("Search transactions...") },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null, modifier = Modifier.size(18.dp)) } }
                } else null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            // Filter chips row — scrollable
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { FilterChipItem("All", filterStatus == null) { filterStatus = null } }
                item { FilterChipItem("✓ Success", filterStatus == LogStatus.SUCCESS) { filterStatus = LogStatus.SUCCESS } }
                item { FilterChipItem("✗ Failed", filterStatus == LogStatus.FAILED) { filterStatus = LogStatus.FAILED } }
                item { FilterChipItem("⚠ Error", filterStatus == LogStatus.ERROR) { filterStatus = LogStatus.ERROR } }
                item { FilterChipItem("ℹ Info", filterStatus == LogStatus.INFO) { filterStatus = LogStatus.INFO } }
            }
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isInitialLoad && allLogs.isEmpty()) {
                    items(6) {
                        ShimmerLogCard()
                    }
                } else {
                    items(filteredLogs.take(visibleItemCount)) { log -> 
                        LogItemCard(log, onClick = { selectedLog = log }) 
                    }
                    if (filteredLogs.isEmpty()) {
                        item { EmptyState(Icons.Default.SearchOff, "No Results", "Try adjusting your search or filters") }
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
        }
        PullRefreshIndicator(
            refreshing = isRefreshing, 
            state = refreshState, 
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        )
    }

    // Log detail dialog
    if (selectedLog != null) {
        LogDetailDialog(log = selectedLog!!, onDismiss = { selectedLog = null })
    }

    // ── Clear confirmation dialog ──────────────────────────────────────────
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(ErrorRed.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.DeleteForever, null, tint = ErrorRed, modifier = Modifier.size(32.dp))
                }
            },
            title = { Text("Clear All Logs?", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
            text = {
                Text(
                    "This will permanently delete all ${allLogs.size} transaction records. This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        logsManager.clearLogs()
                        allLogs = emptyList()
                        showClearConfirm = false
                        Toast.makeText(context, "All logs cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Yes, Clear All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearConfirm = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Cancel") }
            }
        )
    }

    // ── Export format chooser dialog ───────────────────────────────────────
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            icon = {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                }
            },
            title = { Text("Export Logs", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Choose an export format. The file will open in your share sheet so you can send it via WhatsApp, email, Drive, etc.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // CSV option
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            showExportDialog = false
                            shareLogs(context, logsManager.exportLogsAsCsv(), "autosender_logs.csv", "text/csv")
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.TableChart, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("CSV Spreadsheet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("Open in Excel, Google Sheets, etc.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                    // Plain text option
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            showExportDialog = false
                            shareLogs(context, logsManager.exportLogsAsText(), "autosender_logs.txt", "text/plain")
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.TextSnippet, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Plain Text Report", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("Human-readable, share via WhatsApp/email", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        )
    }
}

/**
 * Writes the given log content to a temp file and opens Android's share sheet.
 * Uses FileProvider so no WRITE_EXTERNAL_STORAGE permission is needed.
 */
fun shareLogs(context: android.content.Context, content: String, fileName: String, mimeType: String) {
    try {
        val exportDir = java.io.File(context.cacheDir, "exports").also { it.mkdirs() }
        val file = java.io.File(exportDir, fileName)
        file.writeText(content)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "AutoSender Transaction Logs")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Export logs via…"))
    } catch (e: Exception) {
        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun FilterChipItem(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
fun LogItemCard(log: TransactionLog, onClick: (() -> Unit)? = null) {
    val statusColor = when (log.status) {
        LogStatus.SUCCESS -> SuccessGreen
        LogStatus.FAILED -> ErrorRed
        LogStatus.ERROR -> Color(0xFFFF6D00)
        LogStatus.INFO -> InfoBlue
    }
    val statusIcon = when (log.status) {
        LogStatus.SUCCESS -> Icons.Default.CheckCircle
        LogStatus.FAILED -> Icons.Default.Cancel
        LogStatus.ERROR -> Icons.Default.Warning
        LogStatus.INFO -> Icons.Default.Info
    }
    val statusLabel = when (log.status) {
        LogStatus.SUCCESS -> "Success"
        LogStatus.FAILED -> "Failed"
        LogStatus.ERROR -> "Error"
        LogStatus.INFO -> "Info"
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label = "log_scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick) else Modifier),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // Left colored accent bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(
                        Brush.verticalGradient(listOf(statusColor, statusColor.copy(alpha = 0.4f)))
                    )
                    .defaultMinSize(minHeight = 70.dp)
            )
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                // Status icon with colored bg
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(statusColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val logDate = Calendar.getInstance().apply { timeInMillis = log.timestamp }
                    val today = Calendar.getInstance()
                    val dateString = when {
                        logDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) && logDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
                        logDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) && logDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 -> "Yesterday"
                        else -> SimpleDateFormat("EEE, MMM dd", Locale.getDefault()).format(Date(log.timestamp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Title
                        Text(
                            if (log.amount != null) "$dateString · KES ${"%.0f".format(log.amount)}" else "$dateString · ${log.message}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Status pill badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(statusColor.copy(alpha = 0.13f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(log.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                if (onClick != null) {
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(18.dp).align(Alignment.CenterVertically))
                }
            }
        }
    }
}

@Composable
fun LogDetailDialog(log: TransactionLog, onDismiss: () -> Unit) {
    val statusColor = when (log.status) {
        LogStatus.SUCCESS -> SuccessGreen
        LogStatus.FAILED -> ErrorRed
        LogStatus.ERROR -> Color(0xFFE65100)
        LogStatus.INFO -> InfoBlue
    }
    val statusIcon = when (log.status) {
        LogStatus.SUCCESS -> Icons.Default.CheckCircle
        LogStatus.FAILED -> Icons.Default.Cancel
        LogStatus.ERROR -> Icons.Default.Warning
        LogStatus.INFO -> Icons.Default.Info
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(statusColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) { Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(20.dp)) }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Transaction Detail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(log.status.name, style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Amount row
                if (log.amount != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.08f)), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Amount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("KES ${"%.2f".format(log.amount)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = statusColor)
                        }
                    }
                }
                // Date/time
                DetailRow("Date & Time", SimpleDateFormat("EEE, MMM dd yyyy  HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp)))
                // Message
                DetailRow("Result", log.message)
                // Full context
                if (log.contextDetails.isNotBlank() && log.contextDetails != log.message) {
                    HorizontalDivider()
                    Text("Full M-PESA Response", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(10.dp)) {
                        Text(
                            log.contextDetails,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Close") }
        }
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ─────────────────────────────────────────────
//  SETTINGS TAB
// ─────────────────────────────────────────────
@Composable
fun SettingsTab(themeManager: ThemeManager, onOpenSettings: () -> Unit, onLock: () -> Unit) {
    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf(SecurityHelper.getPin(context) ?: "") }
    val currentTheme by themeManager.themeMode.collectAsState()
    var currentTimeoutMs by remember { mutableLongStateOf(LockManager.getTimeoutMs(context)) }

    Column(modifier = Modifier.fillMaxSize()) {
        val isDark = isSystemInDarkTheme()
        val headerGradient = if (isDark)
            Brush.linearGradient(
                colors = listOf(DarkGradientStart, DarkGradientMid, DarkGradientEnd),
                start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            )
        else
            Brush.linearGradient(
                colors = listOf(GradientStart, GradientMid, GradientEnd),
                start = Offset(0f, 0f), end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerGradient)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Box(modifier = Modifier.size(160.dp).offset(x = 50.dp, y = (-50).dp).align(Alignment.TopEnd).clip(CircleShape).background(Color.White.copy(alpha = 0.04f)))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MpesaAccentGreen))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AutoSender", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), letterSpacing = 1.sp)
                }
                Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.ExtraBold)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSectionHeader("Appearance")
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("App Theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeChip("System", currentTheme == ThemeMode.SYSTEM, Icons.Default.Brightness4) { themeManager.setThemeMode(ThemeMode.SYSTEM) }
                            ThemeChip("Light", currentTheme == ThemeMode.LIGHT, Icons.Default.LightMode) { themeManager.setThemeMode(ThemeMode.LIGHT) }
                            ThemeChip("Dark", currentTheme == ThemeMode.DARK, Icons.Default.DarkMode) { themeManager.setThemeMode(ThemeMode.DARK) }
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader("Permissions")
                val isCallGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                val isAccessibilityGranted = (context as? MainActivity)?.isAccessibilityServiceEnabled() == true
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PermissionRow("Phone Calls", "Required to dial *334# USSD code", isCallGranted) {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = Uri.fromParts("package", context.packageName, null)
                            context.startActivity(intent)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        PermissionRow("Accessibility Service", "Required to interact with USSD dialogs", isAccessibilityGranted) {
                            onOpenSettings()
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader("Security")
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showPinDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Password, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("M-PESA PIN", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("Update your encrypted PIN", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Auto-lock Timeout", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LockManager.TIMEOUT_OPTIONS.forEach { (label, ms) ->
                                ThemeChip(label, currentTimeoutMs == ms, Icons.Default.Timer) {
                                    currentTimeoutMs = ms
                                    LockManager.saveTimeoutMs(context, ms)
                                    LockManager.resetTimer()
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onLock,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Lock Screen Now", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                SettingsSectionHeader("About")
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Version", style = MaterialTheme.typography.bodyMedium)
                            Text("1.0.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Platform", style = MaterialTheme.typography.bodyMedium)
                            Text("Safaricom M-PESA", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Update M-PESA PIN", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Your PIN is encrypted and stored securely on this device only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it },
                        label = { Text("M-PESA PIN") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = { if (pin.isNotBlank()) { SecurityHelper.savePin(context, pin); showPinDialog = false } }) { Text("Save PIN") }
            },
            dismissButton = { TextButton(onClick = { showPinDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}

@Composable
fun ThemeChip(label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
fun PermissionRow(name: String, desc: String, granted: Boolean, onFix: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(if (granted) SuccessGreen.copy(alpha = 0.12f) else ErrorRed.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                null, tint = if (granted) SuccessGreen else ErrorRed, modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) {
            TextButton(onClick = onFix) {
                Text("Fix", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ─────────────────────────────────────────────
//  TRANSACTION RESULT MODAL
// ─────────────────────────────────────────────
@Composable
fun TransactionResultModal(result: TransactionResult, onDismiss: () -> Unit) {
    LaunchedEffect(result) {
        kotlinx.coroutines.delay(60_000)
        onDismiss()
    }

    val bgColor = if (result.success) SuccessGreen else ErrorRed
    val icon = if (result.success) Icons.Default.CheckCircle else Icons.Default.Cancel

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(bgColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = bgColor, modifier = Modifier.size(48.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    if (result.success) "Transfer Successful" else "Transfer Failed",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = bgColor,
                    textAlign = TextAlign.Center
                )
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (result.success && result.amount != null) {
                    Text(
                        "KES ${"%.2f".format(result.amount)}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "sent to ${result.contactName ?: "recipient"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        result.message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Auto-closes in 60s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = bgColor)
            ) { Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    )
}

// ─────────────────────────────────────────────
//  SHARED UI
// ─────────────────────────────────────────────
@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    // Infinite pulsing animation for the icon ring
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Outer glow ring
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )
            // Icon container
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}

@Composable
fun QuickSendDialog(
    contactsManager: ContactsManager,
    onDismiss: () -> Unit,
    onContactsChanged: () -> Unit,
    onSendMoney: (Contact, String) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var saveToContacts by remember { mutableStateOf(false) }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                val contactDetails = ContactPickerHelper.getContactDetails(context, uri)
                if (contactDetails != null) {
                    name = contactDetails.first
                    phone = contactDetails.second
                }
            }
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick Send", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Phone Number") },
                    leadingIcon = { Icon(Icons.Default.Phone, null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                            contactPickerLauncher.launch(intent)
                        }) {
                            Icon(Icons.Default.Contacts, "Pick from Contacts", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (Optional)") },
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it },
                    label = { Text("Amount (KES)") },
                    leadingIcon = { Icon(Icons.Default.AttachMoney, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { saveToContacts = !saveToContacts }) {
                    androidx.compose.material3.Checkbox(
                        checked = saveToContacts,
                        onCheckedChange = { saveToContacts = it }
                    )
                    Text("Save to Favorites", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (phone.isNotBlank()) {
                    val finalName = if (name.isNotBlank()) name else "Unknown"
                    val contactId = java.util.UUID.randomUUID().toString()
                    val contact = Contact(contactId, finalName, phone)
                    
                    if (saveToContacts) {
                        contactsManager.addContact(contact)
                        onContactsChanged()
                    }
                    
                    if (amount.isNotBlank()) {
                        onSendMoney(contact, amount)
                    }
                    onDismiss()
                }
            }) { Text(if (amount.isNotBlank()) "Send Now" else "Save Only") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun VirtualCreditCard(totalToday: Double, totalAllTime: Double) {
    // Animate a shimmer sweep across the card
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by shimmerTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing, delayMillis = 1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_x"
    )

    Card(
        modifier = Modifier.fillMaxWidth().height(196.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.18f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.6f),
                            Color.White.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.35f)
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            // Decorative circles
            Box(
                modifier = Modifier.size(160.dp).offset(x = 180.dp, y = (-40).dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.04f))
            )
            Box(
                modifier = Modifier.size(100.dp).offset(x = (-20).dp, y = 100.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.04f))
            )
            // Shimmer sweep overlay
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        start = Offset(shimmerOffset * 600f, 0f),
                        end = Offset(shimmerOffset * 600f + 200f, Float.POSITIVE_INFINITY)
                    )
                )
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "AUTOSENDER",
                            color = Color.White.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "M-PESA",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Nfc,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(30.dp)
                    )
                }
                // Center – total sent amount
                Column {
                    Text(
                        text = "TOTAL SENT",
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "KES %,.2f".format(totalAllTime),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                    // Mint accent underline
                    Box(
                        modifier = Modifier
                            .width(64.dp).height(2.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(MpesaAccentGreen, MpesaAccentGreen.copy(alpha = 0f))
                                )
                            )
                    )
                }
                // Bottom row – today
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "TODAY",
                            color = Color.White.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "KES %,.2f".format(totalToday),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // Chip-style safaricom badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MpesaAccentGreen.copy(alpha = 0.25f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "SAFARICOM",
                            color = MpesaAccentGreen,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
//  SHIMMER LOADING COMPONENTS
// ─────────────────────────────────────────────
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, cornerRadius: androidx.compose.ui.unit.Dp = 8.dp) {
    val isDark = isSystemInDarkTheme()
    val baseColor = if (isDark) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)
    val highlightColor = if (isDark) Color(0xFF3A3A3A) else Color(0xFFF5F5F5)

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -500f,
        targetValue = 1500f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translation"
    )

    val brush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(x = translateAnim - 200f, y = translateAnim - 200f),
        end = Offset(x = translateAnim, y = translateAnim)
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush)
    )
}

@Composable
fun ShimmerContactCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShimmerBox(modifier = Modifier.size(54.dp), cornerRadius = 27.dp) // Avatar
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                ShimmerBox(modifier = Modifier.fillMaxWidth(0.6f).height(18.dp)) // Name
                Spacer(modifier = Modifier.height(6.dp))
                ShimmerBox(modifier = Modifier.fillMaxWidth(0.4f).height(14.dp)) // Phone
            }
            Spacer(modifier = Modifier.width(14.dp))
            ShimmerBox(modifier = Modifier.size(44.dp), cornerRadius = 12.dp) // Edit button
            Spacer(modifier = Modifier.width(8.dp))
            ShimmerBox(modifier = Modifier.size(44.dp), cornerRadius = 12.dp) // Delete button
        }
    }
}

@Composable
fun ShimmerLogCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShimmerBox(modifier = Modifier.size(42.dp), cornerRadius = 12.dp) // Icon
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ShimmerBox(modifier = Modifier.fillMaxWidth(0.7f).height(16.dp)) // Title
                Spacer(modifier = Modifier.height(6.dp))
                ShimmerBox(modifier = Modifier.fillMaxWidth(0.3f).height(12.dp)) // Date
            }
            Spacer(modifier = Modifier.width(12.dp))
            ShimmerBox(modifier = Modifier.width(60.dp).height(24.dp), cornerRadius = 12.dp) // Status pill
        }
    }
}
