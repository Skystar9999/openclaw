package ai.openclaw.android.missioncontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Mission Control Dashboard - Dashboard nativ Android pentru management complet
 * 
 * Integrare cu:
 * - SMS Gateway (status, inbox, send)
 * - ADB Bridge (remote control)
 * - Voice Call (monitoring)
 * - System Info (battery, storage, network)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionControlDashboard(
    modifier: Modifier = Modifier,
    viewModel: MissionControlViewModel = viewModel()
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Overview", "SMS", "ADB", "System")
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🦞 Mission Control") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab Row
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            
            // Content
            when (selectedTab) {
                0 -> OverviewTab(viewModel)
                1 -> SmsTab(viewModel)
                2 -> AdbTab(viewModel)
                3 -> SystemTab(viewModel)
            }
        }
    }
}

@Composable
private fun OverviewTab(viewModel: MissionControlViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Status Card
        StatusOverviewCard(viewModel)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Quick Actions
        QuickActionsGrid(viewModel)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Active Services
        ActiveServicesCard(viewModel)
    }
}

@Composable
private fun StatusOverviewCard(viewModel: MissionControlViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "System Status",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusIndicator("Gateway", true, Icons.Default.Cloud)
                StatusIndicator("SMS", true, Icons.Default.Message)
                StatusIndicator("ADB", false, Icons.Default.Build)
                StatusIndicator("Voice", false, Icons.Default.Call)
            }
        }
    }
}

@Composable
private fun StatusIndicator(
    label: String,
    active: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) Color.Green else Color.Red,
            modifier = Modifier.size(32.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = if (active) "●" else "○",
            color = if (active) Color.Green else Color.Red,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun QuickActionsGrid(viewModel: MissionControlViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionButton(
                    icon = Icons.Default.Send,
                    label = "Send SMS",
                    onClick = { /* Open SMS Dialog */ },
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    icon = Icons.Default.Refresh,
                    label = "Refresh",
                    onClick = { viewModel.refreshAll() },
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    onClick = { /* Open Settings */ },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ActiveServicesCard(viewModel: MissionControlViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Active Services",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            ServiceRow("HTTP API", "http://0.0.0.0:8888", true)
            ServiceRow("WebSocket", "ws://0.0.0.0:8889", true)
            ServiceRow("ADB Bridge", "http://0.0.0.0:8890", false)
            ServiceRow("Voice Call", "http://0.0.0.0:8891", false)
        }
    }
}

@Composable
private fun ServiceRow(name: String, url: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Badge(
            containerColor = if (active) Color.Green else Color.Red
        ) {
            Text(if (active) "ON" else "OFF")
        }
    }
}

@Composable
private fun SmsTab(viewModel: MissionControlViewModel) {
    // Reuse existing SmsDashboard
    ai.openclaw.android.ui.sms.SmsDashboard(
        onSendClick = { /* Show send dialog */ }
    )
}

@Composable
private fun AdbTab(viewModel: MissionControlViewModel) {
    // TODO: Implementare ADB Control UI
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("ADB Bridge Control - Coming Soon")
    }
}

@Composable
private fun SystemTab(viewModel: MissionControlViewModel) {
    // TODO: Implementare System Info UI
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("System Information - Coming Soon")
    }
}

/**
 * ViewModel pentru Mission Control
 */
class MissionControlViewModel : androidx.lifecycle.ViewModel() {
    fun refreshAll() {
        // TODO: Refresh all services status
    }
}