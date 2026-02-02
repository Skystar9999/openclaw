package ai.openclaw.android.sms

import android.content.Context
import ai.openclaw.android.node.SmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

/**
 * SMS Gateway API - HTTP server pentru trimitere SMS prin SIM
 * 
 * Endpoint-uri disponibile:
 * - POST /sms/send - Trimite SMS
 * - GET /sms/status - Status gateway
 * - GET /sms/inbox - Listează SMS-uri primite (necesită permisiuni)
 * 
 * Autentificare: API Key în header "X-API-Key"
 */
class SmsGatewayServer(
    private val context: Context,
    private val port: Int = 8080,
    private val apiKey: String = "default-key-change-in-production"
) {
    private val smsManager = SmsManager(context)
    private val json = Json { prettyPrint = true }
    private var server: HttpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    @Serializable
    data class SendSmsRequest(
        val to: String,
        val message: String
    )
    
    @Serializable
    data class SendSmsResponse(
        val success: Boolean,
        val messageId: String? = null,
        val error: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    @Serializable
    data class GatewayStatus(
        val status: String,
        val port: Int,
        val smsEnabled: Boolean,
        val hasPermission: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Pornește serverul HTTP
     */
    fun start(): Boolean {
        return try {
            server = HttpServer.create(InetSocketAddress(port), 0).apply {
                createContext("/sms/send", SendSmsHandler())
                createContext("/sms/status", StatusHandler())
                executor = java.util.concurrent.Executors.newCachedThreadPool()
                start()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Oprește serverul HTTP
     */
    fun stop() {
        server?.stop(0)
        server = null
    }
    
    /**
     * Verifică dacă serverul rulează
     */
    fun isRunning(): Boolean = server != null
    
    /**
     * Returnează URL-ul gateway-ului
     */
    fun getUrl(): String = "http://0.0.0.0:$port"
    
    private inner class SendSmsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            when (exchange.requestMethod) {
                "POST" -> handleSendSms(exchange)
                "OPTIONS" -> handleOptions(exchange)
                else -> sendError(exchange, 405, "Method Not Allowed")
            }
        }
        
        private fun handleSendSms(exchange: HttpExchange) {
            // Verifică API Key
            val requestApiKey = exchange.requestHeaders.getFirst("X-API-Key")
            if (requestApiKey != apiKey) {
                sendError(exchange, 401, "Unauthorized: Invalid API Key")
                return
            }
            
            try {
                // Citește body
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val request = json.decodeFromString<SendSmsRequest>(body)
                
                // Trimite SMS în background
                scope.launch {
                    val paramsJson = """{"to":"${request.to}","message":"${request.message}"}"""
                    val result = smsManager.send(paramsJson)
                    
                    val response = SendSmsResponse(
                        success = result.ok,
                        messageId = if (result.ok) generateMessageId() else null,
                        error = result.error
                    )
                    
                    sendJsonResponse(exchange, 200, response)
                }
            } catch (e: Exception) {
                sendError(exchange, 400, "Bad Request: ${e.message}")
            }
        }
        
        private fun handleOptions(exchange: HttpExchange) {
            val headers = exchange.responseHeaders
            headers.add("Access-Control-Allow-Origin", "*")
            headers.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            headers.add("Access-Control-Allow-Headers", "Content-Type, X-API-Key")
            exchange.sendResponseHeaders(204, -1)
        }
    }
    
    private inner class StatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            when (exchange.requestMethod) {
                "GET" -> {
                    val status = GatewayStatus(
                        status = if (isRunning()) "running" else "stopped",
                        port = port,
                        smsEnabled = smsManager.hasTelephonyFeature(),
                        hasPermission = smsManager.canSendSms()
                    )
                    sendJsonResponse(exchange, 200, status)
                }
                "OPTIONS" -> handleOptions(exchange)
                else -> sendError(exchange, 405, "Method Not Allowed")
            }
        }
        
        private fun handleOptions(exchange: HttpExchange) {
            val headers = exchange.responseHeaders
            headers.add("Access-Control-Allow-Origin", "*")
            headers.add("Access-Control-Allow-Methods", "GET, OPTIONS")
            headers.add("Access-Control-Allow-Headers", "Content-Type, X-API-Key")
            exchange.sendResponseHeaders(204, -1)
        }
    }
    
    private fun sendJsonResponse(exchange: HttpExchange, code: Int, data: Any) {
        val jsonString = when (data) {
            is String -> data
            else -> json.encodeToString(data)
        }
        val bytes = jsonString.toByteArray(Charsets.UTF_8)
        
        exchange.responseHeaders.apply {
            add("Content-Type", "application/json")
            add("Access-Control-Allow-Origin", "*")
        }
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.close()
    }
    
    private fun sendError(exchange: HttpExchange, code: Int, message: String) {
        val error = mapOf("error" to message, "success" to false)
        sendJsonResponse(exchange, code, error)
    }
    
    private fun generateMessageId(): String {
        return "sms_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}