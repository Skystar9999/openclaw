package ai.openclaw.android.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel pentru Monitor Dashboard
 * Gestionează stare și date pentru monitorizare context și sub-agenți
 */
class MonitorViewModel : ViewModel() {
    
    // Context metrics
    private val _metrics = MutableStateFlow(MonitorMetrics())
    val metrics: StateFlow<MonitorMetrics> = _metrics
    
    // Agents
    private val _activeAgents = MutableStateFlow(listOf<SystemMonitorServer.SubAgentInfo>())
    val activeAgents: StateFlow<List<SystemMonitorServer.SubAgentInfo>> = _activeAgents
    
    private val _completedAgents = MutableStateFlow(listOf<SystemMonitorServer.SubAgentInfo>())
    val completedAgents: StateFlow<List<SystemMonitorServer.SubAgentInfo>> = _completedAgents
    
    private val _recentAgents = MutableStateFlow(listOf<SystemMonitorServer.SubAgentInfo>())
    val recentAgents: StateFlow<List<SystemMonitorServer.SubAgentInfo>> = _recentAgents
    
    // System info
    private val _systemInfo = MutableStateFlow(
        SystemMonitorServer.SystemInfo(
            deviceModel = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE,
            sdkVersion = android.os.Build.VERSION.SDK_INT,
            cpuUsage = 0f,
            memoryTotal = 0L,
            memoryAvailable = 0L,
            memoryUsed = 0L,
            batteryLevel = 0,
            batteryCharging = false,
            storageTotal = 0L,
            storageAvailable = 0L
        )
    )
    val systemInfo: StateFlow<SystemMonitorServer.SystemInfo> = _systemInfo
    
    init {
        // Load initial data
        refresh()
    }
    
    fun refresh() {
        viewModelScope.launch {
            // Simulate fetching data
            // In real implementation, this would call the server endpoints
            updateMetrics()
            loadAgents()
        }
    }
    
    private fun updateMetrics() {
        // Simulate context growth
        val time = System.currentTimeMillis()
        val simulatedTokens = ((time / 10000) % 150000).toInt()
        val percent = (simulatedTokens / 262000f) * 100
        
        _metrics.value = MonitorMetrics(
            tokensIn = simulatedTokens,
            tokensOut = simulatedTokens / 10,
            contextSize = simulatedTokens,
            contextLimit = 262000,
            contextPercent = percent,
            compactions = 3,
            uptime = android.os.SystemClock.elapsedRealtime(),
            activeAgents = _activeAgents.value.size
        )
    }
    
    private fun loadAgents() {
        // Simulate agent data
        val sampleAgents = listOf(
            SystemMonitorServer.SubAgentInfo(
                id = "1",
                name = "GitHub Monitor",
                status = "completed",
                model = "Gemini 3 Flash",
                startTime = System.currentTimeMillis() - 3600000,
                endTime = System.currentTimeMillis() - 3500000,
                duration = 100000,
                tokensUsed = 1500
            ),
            SystemMonitorServer.SubAgentInfo(
                id = "2",
                name = "SMS Gateway",
                status = "running",
                model = "Local",
                startTime = System.currentTimeMillis() - 7200000
            )
        )
        
        _activeAgents.value = sampleAgents.filter { it.status == "running" }
        _completedAgents.value = sampleAgents.filter { it.status == "completed" }
        _recentAgents.value = sampleAgents.sortedByDescending { it.startTime }
    }
    
    fun triggerCompaction() {
        viewModelScope.launch {
            val current = _metrics.value
            val newSize = (current.contextSize * 0.7).toInt()
            
            _metrics.value = current.copy(
                contextSize = newSize,
                contextPercent = (newSize / 262000f) * 100,
                compactions = current.compactions + 1
            )
        }
    }
    
    fun resetContext() {
        viewModelScope.launch {
            _metrics.value = MonitorMetrics()
        }
    }
    
    fun registerAgent(agent: SystemMonitorServer.SubAgentInfo) {
        viewModelScope.launch {
            val current = _activeAgents.value.toMutableList()
            current.add(agent)
            _activeAgents.value = current
        }
    }
    
    fun updateAgentStatus(id: String, status: String) {
        viewModelScope.launch {
            _activeAgents.value = _activeAgents.value.map { agent ->
                if (agent.id == id) {
                    agent.copy(
                        status = status,
                        endTime = if (status != "running") System.currentTimeMillis() else null
                    )
                } else agent
            }
        }
    }
}

/**
 * Data class pentru metrics în UI
 */
data class MonitorMetrics(
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val contextSize: Int = 0,
    val contextLimit: Int = 262000,
    val contextPercent: Float = 0f,
    val compactions: Int = 0,
    val lastCompaction: Long? = null,
    val uptime: Long = 0L,
    val activeAgents: Int = 0
)