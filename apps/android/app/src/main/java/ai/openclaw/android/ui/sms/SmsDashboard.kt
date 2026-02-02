package ai.openclaw.android.ui.sms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.openclaw.android.sms.SmsInboxReader
import kotlinx.coroutines.launch

/**
 * Dashboard UI pentru SMS Gateway
 * Afișează inbox, status și permite trimiterea SMS
 */
@Composable
fun SmsDashboard(
    modifier: Modifier = Modifier,
    onSendClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val smsInbox = remember { SmsInboxReader(context) }
    
    var messages by remember { mutableStateOf(listOf<SmsInboxReader.SmsMessage>()) }
    var isLoading by remember { mutableStateOf(false) }
    var totalCount by remember { mutableStateOf(0) }
    var unreadCount by remember { mutableStateOf(0) }
    
    // Refresh inbox
    fun refreshInbox() {
        scope.launch {
            isLoading = true
            val inbox = smsInbox.readInbox(limit = 20)
            messages = inbox.messages
            totalCount = inbox.totalCount
            unreadCount = inbox.unreadCount
            isLoading = false
        }
    }
    
    // Initial load
    LaunchedEffect(Unit) {
        refreshInbox()
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header cu status
        SmsStatusCard(
            totalCount = totalCount,
            unreadCount = unreadCount,
            hasPermission = smsInbox.hasReadSmsPermission()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Butoane acțiuni
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onSendClick() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Trimite SMS")
            }
            
            OutlinedButton(
                onClick = { refreshInbox() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reîmprospătează")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Lista SMS-uri
        Text(
            text = "Mesaje recente (${messages.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Nu există mesaje în inbox",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    SmsMessageCard(
                        message = message,
                        onDelete = {
                            scope.launch {
                                smsInbox.deleteMessage(message.id)
                                refreshInbox()
                            }
                        },
                        onMarkRead = {
                            scope.launch {
                                smsInbox.markAsRead(message.id)
                                refreshInbox()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SmsStatusCard(
    totalCount: Int,
    unreadCount: Int,
    hasPermission: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasPermission) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "SMS Gateway Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusItem("Total mesaje", totalCount.toString())
                StatusItem("Necitite", unreadCount.toString())
                StatusItem("Permisiuni", if (hasPermission) "✓" else "✗")
            }
            
            if (!hasPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Permisiunea READ_SMS este necesară pentru a citi inbox-ul",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StatusItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmsMessageCard(
    message: SmsInboxReader.SmsMessage,
    onDelete: () -> Unit,
    onMarkRead: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (message.read) 
                MaterialTheme.colorScheme.surface 
            else 
                MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.address,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                if (!message.read) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text("Nou")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.dateFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Row {
                    if (!message.read) {
                        TextButton(onClick = onMarkRead) {
                            Text("Marchează citit")
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog pentru trimitere SMS
 */
@Composable
fun SendSmsDialog(
    onDismiss: () -> Unit,
    onSend: (to: String, message: String) -> Unit
) {
    var to by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trimite SMS") },
        text = {
            Column {
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    label = { Text("Către (număr)") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Mesaj") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (to.isNotBlank() && message.isNotBlank()) {
                        onSend(to, message)
                        onDismiss()
                    }
                }
            ) {
                Text("Trimite")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Anulează")
            }
        }
    )
}