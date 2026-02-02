package ai.openclaw.android.monitor

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

/**
 * Monitor Dashboard - UI pentru monitorizare context și sub-agenți
 * Similar cu OpenClaw Mac /status și /monitor
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun MonitorDashboard(
    modifier: Modifier = Modifier,
    viewModel: MonitorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Overview", "Context", "Agents", "System")
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📊 System Monitor") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            
            when (selectedTab) {
                0 -> OverviewTab(viewModel)
                1 -> ContextTab(viewModel)
                2 -> AgentsTab(viewModel)
                3 -> SystemTab(viewModel)
            }
        }
    }
}

@Composable
private fun OverviewTab(viewModel: MonitorViewModel) {
    val metrics by viewModel.metrics.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Status Cards Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusCard(
                title = "Context",
                value = "${metrics.contextPercent.toInt()}%",
                icon = Icons.Default.Memory,
                color = getContextColor(metrics.contextPercent),
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Agents",
                value = "${metrics.activeAgents}",
                icon = Icons.Default.Group,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Uptime",
                value = formatUptime(metrics.uptime),
                icon = Icons.Default.Schedule,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Context Progress Bar
        ContextProgressCard(metrics.contextPercent)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Quick Actions
        QuickActionsCard(viewModel)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Recent Agents
        RecentAgentsCard(viewModel)
    }
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = title, tint = color)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ContextProgressCard(percent: Float) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Context Window Usage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${percent.toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = getContextColor(percent)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = getContextColor(percent),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (percent > 75) {
                Text(
                    "⚠️ Context approaching limit! Consider compaction.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun QuickActionsCard(viewModel: MonitorViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Quick Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.triggerCompaction() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Compress, contentDescription = "Compact")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Compact Context")
                }
                
                OutlinedButton(
                    onClick = { viewModel.resetContext() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset")
                }
            }
        }
    }
}

@Composable
private fun RecentAgentsCard(viewModel: MonitorViewModel) {
    val agents by viewModel.recentAgents.collectAsState()
    
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Recent Sub-Agents",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (agents.isEmpty()) {
                Text(
                    "No recent agent runs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                agents.take(5).forEach { agent ->
                    AgentRow(agent)
                }
            }
        }
    }
}

@Composable
private fun AgentRow(agent: SystemMonitorServer.SubAgentInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(agent.name, fontWeight = FontWeight.Medium)
            Text(
                agent.model,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Badge(
            containerColor = when (agent.status) {
                "running" -> Color(0xFF4CAF50)
                "completed" -> Color(0xFF2196F3)
                "failed" -> Color(0xFFF44336)
                else -> Color.Gray
            }
        ) {
            Text(agent.status.uppercase())
        }
    }
}

@Composable
private fun ContextTab(viewModel: MonitorViewModel) {
    val metrics by viewModel.metrics.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ContextProgressCard(metrics.contextPercent)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Detailed Metrics
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Context Metrics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                MetricRow("Tokens In", metrics.tokensIn.toString())
                MetricRow("Tokens Out", metrics.tokensOut.toString())
                MetricRow("Context Size", "${metrics.contextSize} / ${metrics.contextLimit}")
                MetricRow("Compactions", metrics.compactions.toString())
                metrics.lastCompaction?.let {
                    MetricRow("Last Compaction", formatTimeAgo(it))
                }
            }
        }
    }
}

@Composable
private fun AgentsTab(viewModel: MonitorViewModel) {
    val activeAgents by viewModel.activeAgents.collectAsState()
    val completedAgents by viewModel.completedAgents.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Active Agents
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Active Agents (${activeAgents.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                if (activeAgents.isEmpty()) {
                    Text("No active agents")
                } else {
                    activeAgents.forEach { AgentRow(it) }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Completed Agents
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Completed (${completedAgents.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                completedAgents.take(10).forEach { AgentRow(it) }
            }
        }
    }
}

@Composable
private fun SystemTab(viewModel: MonitorViewModel) {
    val systemInfo by viewModel.systemInfo.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Device Information",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                MetricRow("Model", systemInfo.deviceModel)
                MetricRow("Android", systemInfo.androidVersion)
                MetricRow("SDK", systemInfo.sdkVersion.toString())
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Resources",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                MetricRow("CPU Usage", "${systemInfo.cpuUsage.toInt()}%")
                MetricRow("Memory", formatBytes(systemInfo.memoryUsed) + " / " + formatBytes(systemInfo.memoryTotal))
                MetricRow("Storage", formatBytes(systemInfo.storageAvailable) + " free")
                MetricRow("Battery", "${systemInfo.batteryLevel}%${if (systemInfo.batteryCharging) " ⚡" else ""}")
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun getContextColor(percent: Float): Color {
    return when {
        percent < 50 -> Color(0xFF4CAF50) // Green
        percent < 75 -> Color(0xFFFF9800) // Orange
        else -> Color(0xFFF44336) // Red
    }
}

private fun formatUptime(ms: Long): String {
    val hours = ms / (1000 * 60 * 60)
    val minutes = (ms % (1000 * 60 * 60)) / (1000 * 60)
    return "${hours}h ${minutes}m"
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024 * 1024 * 1024)
    return "${gb}GB"
}

private fun formatTimeAgo(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / (1000 * 60)
    return if (minutes < 60) "$minutes min ago" else "${minutes / 60}h ago"
}