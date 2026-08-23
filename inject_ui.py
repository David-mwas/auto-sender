import re

with open("app/src/main/java/com/dmwas/autosendapp/MainActivity.kt", "r") as f:
    content = f.read()

# 1. Add kotlinx.coroutines.delay and delay in imports if not present
if "import kotlinx.coroutines.delay" not in content:
    content = content.replace("import kotlinx.coroutines.flow.collect", "import kotlinx.coroutines.flow.collect\nimport kotlinx.coroutines.delay")
    
# 2. Add Modal to AutoSenderApp
modal_state_code = """
    var showTransactionModal by remember { mutableStateOf<TransactionResult?>(null) }
    LaunchedEffect(Unit) {
        TransactionEventBus.events.collect { result ->
            showTransactionModal = result
        }
    }
    
    if (showTransactionModal != null) {
        TransactionResultModal(
            result = showTransactionModal!!,
            onDismiss = { showTransactionModal = null }
        )
    }

    Scaffold(
"""
content = content.replace("    Scaffold(", modal_state_code)

# 3. Replace HomeTab Call to pass logsManager
content = content.replace("0 -> HomeTab(", "0 -> HomeTab(\n                    logsManager = logsManager,")

# 4. Replace HomeTab definition completely
# Find the start of HomeTab and end of HomeTab (which is right before LogsTab)
home_tab_start = content.find("@Composable\nfun HomeTab(")
logs_tab_start = content.find("@Composable\nfun LogsTab(")
settings_tab_start = content.find("@Composable\nfun SettingsTab(")

# Build new HomeTab
new_home_tab = """@Composable
fun HomeTab(
    contacts: List<Contact>,
    contactsManager: ContactsManager,
    logsManager: LogsManager,
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
            onBack = { selectedContact = null },
            onSend = { amount -> onSendMoney(selectedContact!!, amount) }
        )
        return
    }

    val totalAllTime = remember(contacts) { logsManager.getTotalSentAllTime() }
    val totalToday = remember(contacts) { logsManager.getTotalSentToday() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("AutoSender Stats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Sent Today", style = MaterialTheme.typography.bodySmall)
                            Text("KES $totalToday", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("All Time", style = MaterialTheme.typography.bodySmall)
                            Text("KES $totalAllTime", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        
        items(contacts) { contact ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedContact = contact },
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(contact.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(contact.phoneNumber, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    IconButton(onClick = { editContact = contact }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Contact", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    IconButton(onClick = {
                        contactsManager.removeContact(contact.id)
                        onContactsChanged()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Contact", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (contacts.isEmpty()) {
            item {
                Text(
                    text = "No contacts saved yet.\\nTap the + button to add one.",
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }

    if (showAddContactDialog || editContact != null) {
        val isEdit = editContact != null
        var name by remember { mutableStateOf(editContact?.name ?: "") }
        var phone by remember { mutableStateOf(editContact?.phoneNumber ?: "") }

        AlertDialog(
            onDismissRequest = { 
                onCloseAddContact()
                editContact = null
            },
            title = { Text(if (isEdit) "Edit Contact" else "Add Contact") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone Number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        if (isEdit) {
                            contactsManager.updateContact(Contact(editContact!!.id, name, phone))
                        } else {
                            contactsManager.addContact(Contact(java.util.UUID.randomUUID().toString(), name, phone))
                        }
                        onContactsChanged()
                        onCloseAddContact()
                        editContact = null
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    onCloseAddContact()
                    editContact = null
                }) { Text("Cancel") }
            }
        )
    }
}

"""

new_logs_tab = """@Composable
fun LogsTab(logsManager: LogsManager) {
    val allLogs = remember { logsManager.getLogs() }
    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf<LogStatus?>(null) }
    
    val filteredLogs = remember(allLogs, searchQuery, filterStatus) {
        allLogs.filter { log ->
            val matchesSearch = if (searchQuery.isBlank()) true else {
                log.message.contains(searchQuery, ignoreCase = true) || log.contextDetails.contains(searchQuery, ignoreCase = true)
            }
            val matchesFilter = if (filterStatus == null) true else log.status == filterStatus
            matchesSearch && matchesFilter
        }
    }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search logs...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FilterChip(selected = filterStatus == null, onClick = { filterStatus = null }, label = { Text("All") })
            FilterChip(selected = filterStatus == LogStatus.SUCCESS, onClick = { filterStatus = LogStatus.SUCCESS }, label = { Text("Success") })
            FilterChip(selected = filterStatus == LogStatus.FAILED || filterStatus == LogStatus.ERROR, onClick = { filterStatus = LogStatus.FAILED }, label = { Text("Failed") })
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredLogs) { log ->
                LogItemCard(log)
            }
            if (filteredLogs.isEmpty()) {
                item {
                    Text(
                        text = "No logs match your search.",
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

"""

extra_components = """@Composable
fun LogItemCard(log: TransactionLog) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        val color = when (log.status) {
            LogStatus.SUCCESS -> Color(0xFF4CAF50)
            LogStatus.ERROR, LogStatus.FAILED -> Color(0xFFF44336)
            LogStatus.INFO -> Color(0xFF9E9E9E)
        }
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "[${java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp))}] ${log.status.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = color
                )
                if (log.amount != null) {
                    Text("KES ${log.amount}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = log.message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = log.contextDetails, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun TransactionResultModal(result: TransactionResult, onDismiss: () -> Unit) {
    LaunchedEffect(result) {
        kotlinx.coroutines.delay(60_000)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (result.success) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(40.dp))
                } else {
                    Icon(Icons.Default.Cancel, contentDescription = null, tint = Color(0xFFF44336), modifier = Modifier.size(40.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(if (result.success) "Transfer Successful" else "Transfer Failed")
            }
        },
        text = {
            Column {
                if (result.success && result.amount != null) {
                    Text("Sent KES ${result.amount} to ${result.contactName ?: ""}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(result.message)
            }
        }
    )
}

@Composable
fun ContactDetailScreen(
    contact: Contact,
    logsManager: LogsManager,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    val totalAllTime = remember(contact) { logsManager.getTotalSentAllTime(contact.id) }
    val totalToday = remember(contact) { logsManager.getTotalSentToday(contact.id) }
    val contactLogs = remember(contact) { logsManager.getLogs().filter { it.contactId == contact.id } }
    
    var showSendDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(contact.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        Text(contact.phoneNumber, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Sent Today", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                        Text("KES $totalToday", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("All Time", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                        Text("KES $totalAllTime", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { showSendDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Send Money", color = MaterialTheme.colorScheme.onSecondary)
                }
            }
        }
        
        Text("Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(contactLogs) { log ->
                LogItemCard(log)
            }
            if (contactLogs.isEmpty()) {
                item {
                    Text(
                        text = "No activity yet.",
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }

    if (showSendDialog) {
        var amount by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSendDialog = false },
            title = { Text("Send to ${contact.name}") },
            text = {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (Ksh)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Text("KES", modifier = Modifier.padding(start = 12.dp)) }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (amount.isNotBlank()) {
                            onSend(amount)
                            showSendDialog = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Send Now") }
            },
            dismissButton = {
                TextButton(onClick = { showSendDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        )
    }
}

"""

content = content[:home_tab_start] + new_home_tab + new_logs_tab + extra_components + content[settings_tab_start:]

with open("app/src/main/java/com/dmwas/autosendapp/MainActivity.kt", "w") as f:
    f.write(content)

