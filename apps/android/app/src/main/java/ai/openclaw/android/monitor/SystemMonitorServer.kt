package ai.openclaw.android.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * System Monitor Server - Monitorizare context, sub-agenți și sistem
 * Similar cu OpenClaw Mac status/monitor
 * 
 * Endpoints:
 * - GET  /monitor/status      - Status general sistem
 * - GET  /monitor/context     - Context window usage (tokens)
 * - GET  /monitor/agents      - Lista sub-agenți activi
 * - GET  /monitor/system      - CPU, RAM, Battery info
 * - GET  /monitor/sessions    - Istoric sesiuni
 * - POST /monitor/compact     - Trigger context compaction
 * 
 * Port: 8892
 */
class SystemMonitorServer(
    private val context: Context,
    private val port: Int = 8892
) : NanoHTTPD(port) {
    
    private val json = Json { prettyPrint = true }
    
    // Data storage pentru metrics
    private var sessionHistory = mutableListOf<SessionEntry>()
    private var subAgents = mutableListOf<SubAgentInfo>()
    private var contextMetrics = ContextMetrics()
    
    @Serializable
    data class SystemStatus(
        val status: String,
        val port: Int,
        val uptime: Long,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    @Serializable
    data class ContextMetrics(
        val tokensIn: Int = 0,
        val tokensOut: Int = 0,
        val contextSize: Int = 0,
        val contextLimit: Int = 262000,
        val contextPercent: Float = 0f,
        val compactions: Int = 0,
        val lastCompaction: Long? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    @Serializable
    data class SubAgentInfo(
        val id: String,
        val name: String,
        val status: String, // running, completed, failed
        val model: String,
        val startTime: Long,
        val endTime: Long? = null,
        val duration: Long? = null,
        val tokensUsed: Int? = null,
        val result: String? = null
    )
    
    @Serializable
    data class SessionEntry(
        val id: String,
        val startTime: Long,
        val endTime: Long? = null,
        val messages: Int = 0,
        val model: String,
        val tokensTotal: Int = 0
    )
    
    @Serializable
    data class SystemInfo(
        val deviceModel: String,
        val androidVersion: String,
        val sdkVersion: Int,
        val cpuUsage: Float,
        val memoryTotal: Long,
        val memoryAvailable: Long,
        val memoryUsed: Long,
        val batteryLevel: Int,
        val batteryCharging: Boolean,
        val storageTotal: Long,
        val storageAvailable: Long,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    @Serializable
    data class AgentsResponse(
        val active: List<SubAgentInfo>,
        val completed: List<SubAgentInfo>,
        val totalCount: Int,
        val activeCount: Int
    )
    
    @Serializable
    data class CompactResponse(
        val success: Boolean,
        val tokensBefore: Int,
        val tokensAfter: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        return try {
            when {
                uri == "/monitor/status" && method == Method.GET ->
                    handleStatus()
                uri == "/monitor/context" && method == Method.GET ->
                    handleContext()
                uri == "/monitor/agents" && method == Method.GET ->
                    handleAgents()
                uri == "/monitor/system" && method == Method.GET ->
                    handleSystem()
                uri == "/monitor/sessions" && method == Method.GET ->
                    handleSessions()
                uri == "/monitor/compact" && method == Method.POST ->
                    handleCompact()
                method == Method.OPTIONS ->
                    handleOptions()
                else ->
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        MIME_PLAINTEXT,
                        "{\"error\":\"Not Found\"}"
                    )
            }
        } catch (e: Exception) {
            android.util.Log.e("SystemMonitor", "Error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "{\"error\":\"${e.message}\"}"
            )
        }
    }
    
    private fun handleStatus(): Response {
        val status = SystemStatus(
            status = if (isAlive) "running" else "stopped",
            port = port,
            uptime = android.os.SystemClock.elapsedRealtime()
        )
        return newJsonResponse(status)
    }
    
    private fun handleContext(): Response {
        // Simulare metrics - în producție ar citi din OpenClaw Gateway
        updateContextMetrics()
        return newJsonResponse(contextMetrics)
    }
    
    private fun handleAgents(): Response {
        val active = subAgents.filter { it.status == "running" }
        val completed = subAgents.filter { it.status != "running" }
        
        val response = AgentsResponse(
            active = active,
            completed = completed.take(50), // Ultimele 50
            totalCount = subAgents.size,
            activeCount = active.size
        )
        
        return newJsonResponse(response)
    }
    
    private fun handleSystem(): Response {
        val info = getSystemInfo()
        return newJsonResponse(info)
    }
    
    private fun handleSessions(): Response {
        return newJsonResponse(sessionHistory.takeLast(20))
    }
    
    private fun handleCompact(): Response {
        val before = contextMetrics.contextSize
        
        // Simulare compaction
        val after = (before * 0.7).toInt() // Reduce cu 30%
        contextMetrics = contextMetrics.copy(
            contextSize = after,
            compactions = contextMetrics.compactions + 1,
            lastCompaction = System.currentTimeMillis()
        )
        
        val response = CompactResponse(
            success = true,
            tokensBefore = before,
            tokensAfter = after
        )
        
        return newJsonResponse(response)
    }
    
    private fun handleOptions(): Response {
        val response = newFixedLengthResponse(Response.Status.NO_CONTENT, "", "")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }
    
    private fun updateContextMetrics() {
        // Într-o implementare reală, aici am citi din OpenClaw Gateway
        // Momentan simulăm growth gradual
        val time = System.currentTimeMillis()
        val simulatedTokens = ((time / 1000) % 100000).toInt()
        
        contextMetrics = ContextMetrics(
            tokensIn = simulatedTokens,
            tokensOut = simulatedTokens / 10,
            contextSize = simulatedTokens,
            contextLimit = 262000,
            contextPercent = (simulatedTokens / 262000f) * 100,
            compactions = contextMetrics.compactions
        )
    }
    
    private fun getSystemInfo(): SystemInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                         batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        
        val storageTotal = getTotalStorage()
        val storageAvailable = getAvailableStorage()
        
        return SystemInfo(
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT,
            cpuUsage = getCpuUsage(),
            memoryTotal = memoryInfo.totalMem,
            memoryAvailable = memoryInfo.availMem,
            memoryUsed = memoryInfo.totalMem - memoryInfo.availMem,
            batteryLevel = batteryLevel,
            batteryCharging = isCharging,
            storageTotal = storageTotal,
            storageAvailable = storageAvailable
        )
    }
    
    private fun getCpuUsage(): Float {
        return try {
            val reader = BufferedReader(InputStreamReader(File("/proc/stat").inputStream()))
            val line = reader.readLine()
            reader.close()
            
            // Parse CPU line
            val parts = line.split(" ")
            if (parts.size > 4) {
                val user = parts[2].toLong()
                val nice = parts[3].toLong()
                val system = parts[4].toLong()
                val idle = parts[5].toLong()
                
                val total = user + nice + system + idle
                val used = user + nice + system
                
                (used.toFloat() / total.toFloat()) * 100f
            } else {
                0f
            }
        } catch (e: Exception) {
            0f
        }
    }
    
    private fun getTotalStorage(): Long {
        return try {
            val stat = android.os.StatFs(context.filesDir.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            blockSize * totalBlocks
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun getAvailableStorage(): Long {
        return try {
            val stat = android.os.StatFs(context.filesDir.path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            blockSize * availableBlocks
        } catch (e: Exception) {
            0L
        }
    }
    
    // Public API pentru înregistrare sub-agenți
    fun registerSubAgent(agent: SubAgentInfo) {
        subAgents.add(agent)
    }
    
    fun updateSubAgentStatus(id: String, status: String, result: String? = null) {
        subAgents.find { it.id == id }?.let { agent ->
            val index = subAgents.indexOf(agent)
            val endTime = System.currentTimeMillis()
            val duration = endTime - agent.startTime
            
            subAgents[index] = agent.copy(
                status = status,
                endTime = endTime,
                duration = duration,
                result = result
            )
        }
    }
    
    fun recordSession(session: SessionEntry) {
        sessionHistory.add(session)
    }
    
    fun updateContext(tokensIn: Int, tokensOut: Int) {
        contextMetrics = contextMetrics.copy(
            tokensIn = contextMetrics.tokensIn + tokensIn,
            tokensOut = contextMetrics.tokensOut + tokensOut,
            contextSize = contextMetrics.contextSize + tokensIn
        )
    }
    
    private fun newJsonResponse(data: Any): Response {
        val jsonString = when (data) {
            is String -> data
            else -> json.encodeToString(data)
        }
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", jsonString)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }
}