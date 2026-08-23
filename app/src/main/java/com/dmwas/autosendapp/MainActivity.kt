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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

                if (!isAuthenticated) {
                    AppLockScreen(
                        onUnlock = { isAuthenticated = true },
                        onBiometricUnlock = { authenticateForAccess { isAuthenticated = true } }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AutoSenderApp(
                            contactsManager = contactsManager,
                            logsManager = logsManager,
                            themeManager = themeManager,
                            onSendMoney = { contact, amount ->
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                                    showPermissionDialog = true
                                } else {
                                    initiateTransfer(contact, amount)
                                }
                            },
                            onOpenSettings = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        val txPrefs = getSharedPreferences("TxPrefs", Context.MODE_PRIVATE)
        txPrefs.edit()
            .putString("phone_number", contact.phoneNumber)
            .putString("contact_id", contact.id)
            .putString("contact_name", contact.name)
            .putString("amount", amount)
            .putString("mpesa_pin", pin)
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
    var manualPin by remember { mutableStateOf("") }
    val savedPin = SecurityHelper.getPin(context)
    val isDark = isSystemInDarkTheme()
    val gradientColors = if (isDark) {
        listOf(Color(0xFF0D1F0D), Color(0xFF1B3A1B), Color(0xFF0D1F0D))
    } else {
        listOf(Color(0xFF2E7D32), Color(0xFF43A047), Color(0xFF1B5E20))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo area
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("M", fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("AutoSender", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Text("Secure M-PESA Automation", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
            Spacer(modifier = Modifier.height(48.dp))

            // Biometric button
            Button(
                onClick = onBiometricUnlock,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White)
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Unlock with Biometric", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }

            if (!savedPin.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.3f))
                    Text("  or use PIN  ", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.3f))
                }
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedTextField(
                    value = manualPin,
                    onValueChange = { manualPin = it },
                    label = { Text("M-PESA PIN", color = Color.White.copy(alpha = 0.8f)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White.copy(alpha = 0.9f),
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                        cursorColor = Color.White,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (manualPin == savedPin) onUnlock()
                        else Toast.makeText(context, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White)
                ) {
                    Text("Unlock with PIN", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
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
    onOpenSettings: () -> Unit
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
                2 -> SettingsTab(themeManager = themeManager, onOpenSettings = onOpenSettings)
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
        val headerGradient = if (isDark) listOf(Color(0xFF0D1F0D), Color(0xFF1B3A1B)) else listOf(Color(0xFF2E7D32), Color(0xFF43A047))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(headerGradient))
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Column {
                Text("AutoSender", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.8f))
                Text("Send Money", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
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
    val lastTx = remember(contact, refreshTrigger) { logsManager.getLogs().firstOrNull { it.contactId == contact.id } }
    val initials = contact.name.split(" ").take(2).joinToString("") { it.firstOrNull()?.toString() ?: "" }.uppercase()

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with initials
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(initials, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(contact.phoneNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (lastTx != null && lastTx.amount != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Last: KES ${"%.0f".format(lastTx.amount)} • ${SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(lastTx.timestamp))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
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
        val headerGradient = if (isDark) listOf(Color(0xFF0D1F0D), Color(0xFF1B3A1B)) else listOf(Color(0xFF2E7D32), Color(0xFF43A047))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(headerGradient))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp).offset(x = (-8).dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initials, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(contact.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(contact.phoneNumber, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f))
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Sent Today", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                        Text("KES ${"%.2f".format(totalToday)}", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("All Time", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                        Text("KES ${"%.2f".format(totalAllTime)}", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { showSendDialog = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF2E7D32))
                ) {
                    Icon(Icons.Default.SendTimeExtension, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Money", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
    var allLogs by remember { mutableStateOf(logsManager.getLogs()) }
    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf<LogStatus?>(null) }
    var selectedLog by remember { mutableStateOf<TransactionLog?>(null) }
    
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
        val headerGradient = if (isDark) listOf(Color(0xFF0D1F0D), Color(0xFF1B3A1B)) else listOf(Color(0xFF2E7D32), Color(0xFF43A047))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(headerGradient))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Text("Transaction Logs", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Text("${filteredLogs.size} of ${allLogs.size} records", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
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
                items(filteredLogs.take(visibleItemCount)) { log -> 
                    LogItemCard(log, onClick = { selectedLog = log }) 
                }
                if (filteredLogs.isEmpty()) {
                    item { EmptyState(Icons.Default.SearchOff, "No Results", "Try adjusting your search or filters") }
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
        LogStatus.ERROR -> Color(0xFFE65100)
        LogStatus.INFO -> InfoBlue
    }
    val statusIcon = when (log.status) {
        LogStatus.SUCCESS -> Icons.Default.CheckCircle
        LogStatus.FAILED -> Icons.Default.Cancel
        LogStatus.ERROR -> Icons.Default.Warning
        LogStatus.INFO -> Icons.Default.Info
    }
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(statusColor.copy(alpha = 0.12f)),
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
                    else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(log.timestamp))
                }
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (log.amount != null) "$dateString - Sent KES ${"%.0f".format(log.amount)}" else "$dateString - ${log.status.name.lowercase().capitalize()}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(log.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (onClick != null) {
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(18.dp).align(Alignment.CenterVertically))
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
fun SettingsTab(themeManager: ThemeManager, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var showPinDialog by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf(SecurityHelper.getPin(context) ?: "") }
    val currentTheme by themeManager.themeMode.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        val isDark = isSystemInDarkTheme()
        val headerGradient = if (isDark) listOf(Color(0xFF0D1F0D), Color(0xFF1B3A1B)) else listOf(Color(0xFF2E7D32), Color(0xFF43A047))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(headerGradient))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Text("Customize your AutoSender experience", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.05f)
                        ),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.05f))
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AutoSender",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = Icons.Default.Nfc, // NFC/Contactless style
                        contentDescription = "Virtual Card",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                // Middle Row (Balance)
                Column {
                    Text(
                        text = "TOTAL SENT",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "KES %,.2f".format(totalAllTime),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }
                
                // Bottom Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "TODAY",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "KES %,.2f".format(totalToday),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Text(
                        text = "M-PESA",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        }
    }
}
