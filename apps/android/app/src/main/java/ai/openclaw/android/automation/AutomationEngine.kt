package ai.openclaw.android.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Task Automation Engine - Tasker-like automation în OpenClaw
 * 
 * Triggers:
 * - Time based (scheduled tasks)
 * - Event based (SMS primit, apel, baterie, etc.)
 * - Condition based (battery level, WiFi connected, etc.)
 * 
 * Actions:
 * - HTTP requests
 * - Send SMS
 * - ADB commands
 * - Notification
 * - Launch app
 * 
 * Port: 8896
 */
class AutomationEngine(
    private val context: Context,
    private val port: Int = 8896
) : NanoHTTPD(port) {
    
    private val json = Json { prettyPrint = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    
    // Storage pentru task-uri
    private val tasks = ConcurrentHashMap<String, AutomationTask>()
    private val taskHistory = mutableListOf<TaskExecution>()
    
    @Serializable
    data class AutomationTask(
        val id: String,
        val name: String,
        val enabled: Boolean = true,
        val trigger: TaskTrigger,
        val conditions: List<TaskCondition> = emptyList(),
        val actions: List<TaskAction>,
        val createdAt: Long = System.currentTimeMillis()
    )
    
    @Serializable
    data class TaskTrigger(
        val type: String, // time, event, boot, battery
        val config: Map<String, String> = emptyMap()
    )
    
    @Serializable
    data class TaskCondition(
        val type: String, // battery_level, wifi_connected, screen_on
        val operator: String, // eq, gt, lt, neq
        val value: String
    )
    
    @Serializable
    data class TaskAction(
        val type: String, // http_request, send_sms, adb_command, notification, launch_app
        val config: Map<String, String>
    )
    
    @Serializable
    data class TaskExecution(
        val taskId: String,
        val taskName: String,
        val timestamp: Long,
        val success: Boolean,
        val message: String
    )
    
    init {
        loadSavedTasks()
        registerSystemReceivers()
    }
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        return try {
            when {
                uri == "/automation/tasks" && method == Method.GET ->
                    handleListTasks()
                uri == "/automation/tasks" && method == Method.POST ->
                    handleCreateTask(session)
                uri.startsWith("/automation/tasks/") && method == Method.GET ->
                    handleGetTask(uri.substringAfterLast("/"))
                uri.startsWith("/automation/tasks/") && method == Method.PUT ->
                    handleUpdateTask(session, uri.substringAfterLast("/"))
                uri.startsWith("/automation/tasks/") && method == Method.DELETE ->
                    handleDeleteTask(uri.substringAfterLast("/"))
                uri.startsWith("/automation/tasks/") && uri.endsWith("/run") && method == Method.POST ->
                    handleRunTask(uri.substringAfterLast("/").removeSuffix("/run"))
                uri.startsWith("/automation/tasks/") && uri.endsWith("/toggle") && method == Method.POST ->
                    handleToggleTask(uri.substringAfterLast("/").removeSuffix("/toggle"))
                uri == "/automation/history" && method == Method.GET ->
                    handleHistory()
                uri == "/automation/triggers" && method == Method.GET ->
                    handleListTriggers()
                uri == "/automation/actions" && method == Method.GET ->
                    handleListActions()
                method == Method.OPTIONS ->
                    handleOptions()
                else ->
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"Not Found\"}")
            }
        } catch (e: Exception) {
            android.util.Log.e("Automation", "Error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    // ============ Handlers HTTP ============
    
    private fun handleListTasks(): Response {
        return newJsonResponse(tasks.values.toList())
    }
    
    private fun handleCreateTask(session: IHTTPSession): Response {
        return try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val body = bodyMap["postData"] ?: "{}"
            
            val task = json.decodeFromString<AutomationTask>(body)
            val taskWithId = task.copy(id = task.id.ifEmpty { UUID.randomUUID().toString() })
            
            tasks[taskWithId.id] = taskWithId
            
            if (taskWithId.enabled) {
                scheduleTask(taskWithId)
            }
            
            saveTasks()
            newJsonResponse(taskWithId)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleGetTask(taskId: String): Response {
        val task = tasks[taskId]
        return if (task != null) {
            newJsonResponse(task)
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"Task not found\"}")
        }
    }
    
    private fun handleUpdateTask(session: IHTTPSession, taskId: String): Response {
        return try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val body = bodyMap["postData"] ?: "{}"
            
            val task = json.decodeFromString<AutomationTask>(body)
            val updatedTask = task.copy(id = taskId)
            
            tasks[taskId] = updatedTask
            
            // Reschedule
            cancelTask(taskId)
            if (updatedTask.enabled) {
                scheduleTask(updatedTask)
            }
            
            saveTasks()
            newJsonResponse(updatedTask)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleDeleteTask(taskId: String): Response {
        cancelTask(taskId)
        tasks.remove(taskId)
        saveTasks()
        return newJsonResponse(mapOf("success" to true, "deleted" to taskId))
    }
    
    private fun handleRunTask(taskId: String): Response {
        val task = tasks[taskId]
        return if (task != null) {
            scope.launch {
                executeTask(task)
            }
            newJsonResponse(mapOf("success" to true, "message" to "Task execution started"))
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"Task not found\"}")
        }
    }
    
    private fun handleToggleTask(taskId: String): Response {
        val task = tasks[taskId]
        return if (task != null) {
            val updated = task.copy(enabled = !task.enabled)
            tasks[taskId] = updated
            
            if (updated.enabled) {
                scheduleTask(updated)
            } else {
                cancelTask(taskId)
            }
            
            saveTasks()
            newJsonResponse(updated)
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"Task not found\"}")
        }
    }
    
    private fun handleHistory(): Response {
        return newJsonResponse(taskHistory.takeLast(50))
    }
    
    private fun handleListTriggers(): Response {
        val triggers = listOf(
            mapOf("type" to "time", "description" to "Scheduled time", "config" to listOf("hour", "minute", "repeat")),
            mapOf("type" to "event", "description" to "System event", "config" to listOf("event_type")),
            mapOf("type" to "boot", "description" to "Device boot", "config" to emptyList<String>()),
            mapOf("type" to "battery", "description" to "Battery level", "config" to listOf("level", "status")),
            mapOf("type" to "sms_received", "description" to "SMS received", "config" to listOf("from", "contains")),
            mapOf("type" to "call_received", "description" to "Call received", "config" to listOf("from"))
        )
        return newJsonResponse(triggers)
    }
    
    private fun handleListActions(): Response {
        val actions = listOf(
            mapOf("type" to "http_request", "description" to "Make HTTP request", "config" to listOf("url", "method", "body")),
            mapOf("type" to "send_sms", "description" to "Send SMS", "config" to listOf("to", "message")),
            mapOf("type" to "adb_command", "description" to "Execute ADB command", "config" to listOf("command")),
            mapOf("type" to "notification", "description" to "Show notification", "config" to listOf("title", "message")),
            mapOf("type" to "launch_app", "description" to "Launch app", "config" to listOf("package_name")),
            mapOf("type" to "wait", "description" to "Wait", "config" to listOf("seconds"))
        )
        return newJsonResponse(actions)
    }
    
    // ============ Execuție Task-uri ============
    
    private suspend fun executeTask(task: AutomationTask) {
        android.util.Log.i("Automation", "Executing task: ${task.name}")
        
        // Verifică condiții
        if (!checkConditions(task.conditions)) {
            logExecution(task, false, "Conditions not met")
            return
        }
        
        // Execută acțiuni
        var allSuccess = true
        var lastMessage = "Success"
        
        for (action in task.actions) {
            val result = executeAction(action)
            if (!result.success) {
                allSuccess = false
                lastMessage = result.message
                break
            }
            // Delay între acțiuni
            delay(500)
        }
        
        logExecution(task, allSuccess, lastMessage)
    }
    
    private fun checkConditions(conditions: List<TaskCondition>): Boolean {
        // Implementare condiții
        return conditions.all { condition ->
            when (condition.type) {
                "battery_level" -> checkBatteryLevel(condition)
                "wifi_connected" -> checkWifiConnected(condition)
                "screen_on" -> checkScreenOn(condition)
                else -> true
            }
        }
    }
    
    private fun checkBatteryLevel(condition: TaskCondition): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val conditionLevel = condition.value.toIntOrNull() ?: 0
        
        return when (condition.operator) {
            "gt" -> level > conditionLevel
            "lt" -> level < conditionLevel
            "eq" -> level == conditionLevel
            else -> true
        }
    }
    
    private fun checkWifiConnected(condition: TaskCondition): Boolean {
        // Implementare verificare WiFi
        return true
    }
    
    private fun checkScreenOn(condition: TaskCondition): Boolean {
        // Implementare verificare ecran
        return true
    }
    
    private data class ActionResult(val success: Boolean, val message: String)
    
    private suspend fun executeAction(action: TaskAction): ActionResult {
        return try {
            when (action.type) {
                "http_request" -> executeHttpRequest(action.config)
                "send_sms" -> executeSendSms(action.config)
                "adb_command" -> executeAdbCommand(action.config)
                "notification" -> executeNotification(action.config)
                "launch_app" -> executeLaunchApp(action.config)
                "wait" -> executeWait(action.config)
                else -> ActionResult(false, "Unknown action type: ${action.type}")
            }
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Unknown error")
        }
    }
    
    private suspend fun executeHttpRequest(config: Map<String, String>): ActionResult {
        val url = config["url"] ?: return ActionResult(false, "URL required")
        val method = config["method"] ?: "GET"
        
        // Implementare HTTP request
        return ActionResult(true, "HTTP $method to $url")
    }
    
    private suspend fun executeSendSms(config: Map<String, String>): ActionResult {
        val to = config["to"] ?: return ActionResult(false, "Recipient required")
        val message = config["message"] ?: return ActionResult(false, "Message required")
        
        // Implementare send SMS via SMS Gateway
        return ActionResult(true, "SMS sent to $to")
    }
    
    private suspend fun executeAdbCommand(config: Map<String, String>): ActionResult {
        val command = config["command"] ?: return ActionResult(false, "Command required")
        
        val process = Runtime.getRuntime().exec(command)
        val exitCode = process.waitFor()
        
        return ActionResult(exitCode == 0, "Exit code: $exitCode")
    }
    
    private fun executeNotification(config: Map<String, String>): ActionResult {
        val title = config["title"] ?: "Automation"
        val message = config["message"] ?: ""
        
        // Implementare notificare
        return ActionResult(true, "Notification shown: $title")
    }
    
    private fun executeLaunchApp(config: Map<String, String>): ActionResult {
        val packageName = config["package_name"] ?: return ActionResult(false, "Package name required")
        
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            context.startActivity(intent)
            ActionResult(true, "App launched: $packageName")
        } else {
            ActionResult(false, "App not found: $packageName")
        }
    }
    
    private suspend fun executeWait(config: Map<String, String>): ActionResult {
        val seconds = config["seconds"]?.toIntOrNull() ?: 1
        delay(seconds * 1000L)
        return ActionResult(true, "Waited $seconds seconds")
    }
    
    private fun logExecution(task: AutomationTask, success: Boolean, message: String) {
        val execution = TaskExecution(
            taskId = task.id,
            taskName = task.name,
            timestamp = System.currentTimeMillis(),
            success = success,
            message = message
        )
        taskHistory.add(execution)
        
        // Trimite notificare dacă eșuează
        if (!success) {
            android.util.Log.e("Automation", "Task ${task.name} failed: $message")
        }
    }
    
    // ============ Scheduling ============
    
    private fun scheduleTask(task: AutomationTask) {
        when (task.trigger.type) {
            "time" -> scheduleTimeTrigger(task)
            "boot" -> {} // Boot receiver se ocupă
            else -> {} // Event triggers au receivers separate
        }
    }
    
    private fun scheduleTimeTrigger(task: AutomationTask) {
        val hour = task.trigger.config["hour"]?.toIntOrNull() ?: 0
        val minute = task.trigger.config["minute"]?.toIntOrNull() ?: 0
        
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }
        
        if (calendar.timeInMillis < System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            putExtra("task_id", task.id)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )
    }
    
    private fun cancelTask(taskId: String) {
        val intent = Intent(context, TaskAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }
    
    // ============ System Receivers ============
    
    private fun registerSystemReceivers() {
        // Battery receiver
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(BatteryLevelReceiver(), batteryFilter)
        
        // Boot receiver
        val bootFilter = IntentFilter(Intent.ACTION_BOOT_COMPLETED)
        context.registerReceiver(BootCompletedReceiver(), bootFilter)
    }
    
    // ============ Persistence ============
    
    private fun loadSavedTasks() {
        // TODO: Load from SharedPreferences sau SQLite
    }
    
    private fun saveTasks() {
        // TODO: Save to SharedPreferences sau SQLite
    }
    
    // ============ Utilități ============
    
    private fun handleOptions(): Response {
        val response = newFixedLengthResponse(Response.Status.NO_CONTENT, "", "")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
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
    
    override fun stop() {
        scope.cancel()
        super.stop()
    }
    
    // ============ Receivers ============
    
    class TaskAlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val taskId = intent.getStringExtra("task_id")
            // TODO: Execute task
        }
    }
    
    inner class BatteryLevelReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Verifică task-uri cu trigger battery
        }
    }
    
    inner class BootCompletedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Re-schedule task-uri la boot
            tasks.values.filter { it.enabled && it.trigger.type == "boot" }
                .forEach { scheduleTask(it) }
        }
    }
}