package ai.openclaw.android.homeassistant

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home Assistant Integration - Bridge între tabletă și Home Assistant
 * 
 * Features:
 * - Webhook receiver pentru evenimente HA
 * - Sensor updates (battery, location, etc.)
 * - Service calls către HA
 * - Automation triggers
 * 
 * Configurare în local.properties:
 * - ha.baseUrl
 * - ha.webhookId  
 * - ha.apiKey
 * 
 * Port: 8895
 */
class HomeAssistantBridge(
    private val context: Context,
    private val port: Int = 8895
) : NanoHTTPD(port) {
    
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Configurare (în producție ar veni din config file)
    private var haBaseUrl: String = ""
    private var haApiKey: String = ""
    private var haWebhookId: String = ""
    
    @Serializable
    data class SensorData(
        val state: String,
        val attributes: Map<String, String> = emptyMap()
    )
    
    @Serializable
    data class ServiceCall(
        val domain: String,
        val service: String,
        val entityId: String? = null,
        val data: Map<String, String> = emptyMap()
    )
    
    @Serializable
    data class AutomationTrigger(
        val automationId: String,
        val triggerData: Map<String, String> = emptyMap()
    )
    
    @Serializable
    data class HaResponse(
        val success: Boolean,
        val message: String,
        val data: String? = null
    )
    
    @Serializable
    data class DeviceStatus(
        val batteryLevel: Int,
        val batteryCharging: Boolean,
        val screenOn: Boolean,
        val wifiConnected: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        return try {
            when {
                uri == "/ha/config" && method == Method.POST ->
                    handleConfig(session)
                uri == "/ha/sensor/update" && method == Method.POST ->
                    handleSensorUpdate(session)
                uri == "/ha/service/call" && method == Method.POST ->
                    handleServiceCall(session)
                uri == "/ha/automation/trigger" && method == Method.POST ->
                    handleAutomationTrigger(session)
                uri == "/ha/status" && method == Method.GET ->
                    handleStatus()
                uri.startsWith("/ha/webhook/") && method == Method.POST ->
                    handleWebhook(session, uri.substringAfterLast("/"))
                method == Method.OPTIONS ->
                    handleOptions()
                else ->
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"Not Found\"}")
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeAssistant", "Error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleConfig(session: IHTTPSession): Response {
        return try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val body = bodyMap["postData"] ?: "{}"
            
            val config = json.decodeFromString<HaConfig>(body)
            
            haBaseUrl = config.baseUrl
            haApiKey = config.apiKey
            haWebhookId = config.webhookId
            
            // Test conexiune
            scope.launch {
                testConnection()
            }
            
            newJsonResponse(HaResponse(success = true, message = "Configuration saved"))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleSensorUpdate(session: IHTTPSession): Response {
        if (haBaseUrl.isEmpty()) {
            return newJsonResponse(HaResponse(success = false, message = "Not configured"))
        }
        
        return try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val body = bodyMap["postData"] ?: "{}"
            
            val sensorData = json.decodeFromString<Map<String, SensorData>>(body)
            
            scope.launch {
                sensorData.forEach { (sensorId, data) ->
                    updateSensor(sensorId, data)
                }
            }
            
            newJsonResponse(HaResponse(success = true, message = "Sensor update queued"))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleServiceCall(session: IHTTPSession): Response {
        if (haBaseUrl.isEmpty()) {
            return newJsonResponse(HaResponse(success = false, message = "Not configured"))
        }
        
        return try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val body = bodyMap["postData"] ?: "{}"
            
            val serviceCall = json.decodeFromString<ServiceCall>(body)
            
            scope.launch {
                callService(serviceCall)
            }
            
            newJsonResponse(HaResponse(success = true, message = "Service call queued"))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleAutomationTrigger(session: IHTTPSession): Response {
        if (haBaseUrl.isEmpty()) {
            return newJsonResponse(HaResponse(success = false, message = "Not configured"))
        }
        
        return try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val body = bodyMap["postData"] ?: "{}"
            
            val trigger = json.decodeFromString<AutomationTrigger>(body)
            
            scope.launch {
                triggerAutomation(trigger)
            }
            
            newJsonResponse(HaResponse(success = true, message = "Automation triggered"))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleStatus(): Response {
        return newJsonResponse(mapOf(
            "configured" to haBaseUrl.isNotEmpty(),
            "baseUrl" to if (haBaseUrl.isNotEmpty()) "***" else "",
            "webhookConfigured" to haWebhookId.isNotEmpty()
        ))
    }
    
    private fun handleWebhook(session: IHTTPSession, webhookId: String): Response {
        // Procesează webhook de la Home Assistant
        return try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val body = bodyMap["postData"] ?: "{}"
            
            android.util.Log.i("HomeAssistant", "Webhook received: $webhookId - $body")
            
            // Aici poți procesa comenzi de la HA
            // Ex: {"command": "take_photo", "target": "front_camera"}
            
            newJsonResponse(HaResponse(success = true, message = "Webhook processed"))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    // ============ API Calls către Home Assistant ============
    
    private suspend fun testConnection() {
        try {
            val url = URL("$haBaseUrl/api/")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $haApiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            
            val responseCode = conn.responseCode
            android.util.Log.i("HomeAssistant", "Connection test: $responseCode")
        } catch (e: Exception) {
            android.util.Log.e("HomeAssistant", "Connection failed: ${e.message}")
        }
    }
    
    private suspend fun updateSensor(sensorId: String, data: SensorData) {
        try {
            val url = URL("$haBaseUrl/api/states/sensor.${context.packageName}_$sensorId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $haApiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val payload = json.encodeToString(data)
            conn.outputStream.write(payload.toByteArray())
            
            val responseCode = conn.responseCode
            android.util.Log.i("HomeAssistant", "Sensor update $sensorId: $responseCode")
        } catch (e: Exception) {
            android.util.Log.e("HomeAssistant", "Sensor update failed: ${e.message}")
        }
    }
    
    private suspend fun callService(serviceCall: ServiceCall) {
        try {
            val url = URL("$haBaseUrl/api/services/${serviceCall.domain}/${serviceCall.service}")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $haApiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val payload = json.encodeToString(serviceCall)
            conn.outputStream.write(payload.toByteArray())
            
            val responseCode = conn.responseCode
            android.util.Log.i("HomeAssistant", "Service call ${serviceCall.domain}.${serviceCall.service}: $responseCode")
        } catch (e: Exception) {
            android.util.Log.e("HomeAssistant", "Service call failed: ${e.message}")
        }
    }
    
    private suspend fun triggerAutomation(trigger: AutomationTrigger) {
        // Trigger automation via webhook sau API
        try {
            val url = if (haWebhookId.isNotEmpty()) {
                URL("$haBaseUrl/api/webhook/$haWebhookId")
            } else {
                URL("$haBaseUrl/api/events/automation_triggered")
            }
            
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            if (haApiKey.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $haApiKey")
            }
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val payload = json.encodeToString(trigger)
            conn.outputStream.write(payload.toByteArray())
            
            val responseCode = conn.responseCode
            android.util.Log.i("HomeAssistant", "Automation trigger: $responseCode")
        } catch (e: Exception) {
            android.util.Log.e("HomeAssistant", "Automation trigger failed: ${e.message}")
        }
    }
    
    // ============ Utilități ============
    
    fun sendDeviceStatus(status: DeviceStatus) {
        if (haBaseUrl.isEmpty()) return
        
        scope.launch {
            updateSensor("device_status", SensorData(
                state = status.batteryLevel.toString(),
                attributes = mapOf(
                    "charging" to status.batteryCharging.toString(),
                    "screen_on" to status.screenOn.toString(),
                    "wifi_connected" to status.wifiConnected.toString()
                )
            ))
        }
    }
    
    private fun handleOptions(): Response {
        val response = newFixedLengthResponse(Response.Status.NO_CONTENT, "", "")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
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
}

@Serializable
data class HaConfig(
    val baseUrl: String,
    val apiKey: String,
    val webhookId: String = ""
)