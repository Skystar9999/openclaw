package ai.openclaw.android.sms

import android.content.Context
import ai.openclaw.android.node.SmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * SMS Gateway API - HTTP server pentru trimitere și citire SMS prin SIM
 * 
 * Endpoint-uri disponibile:
 * - GET  /sms/status      - Status gateway
 * - GET  /sms/inbox       - Listează SMS-uri primite
 * - GET  /sms/inbox/{id}  - Citește SMS specific
 * - POST /sms/send        - Trimite SMS
 * - POST /sms/{id}/read   - Marchează SMS ca citit
 * - DELETE /sms/{id}      - Șterge SMS
 * 
 * WebSocket: ws://<tablet-ip>:8889 (notificări în timp real)
 * 
 * Autentificare: API Key în header "X-API-Key"
 */
class SmsGatewayServer(
    private val context: Context,
    private val port: Int = 8888,
    private val apiKey: String = "default-key-change-in-production"
) {
    private val smsManager = SmsManager(context)
    private val smsInbox = SmsInboxReader(context)
    private val json = Json { prettyPrint = true }
    private var server: HttpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // WebSocket server pentru notificări real-time
    private var webSocketServer: SmsWebSocketServer? = null
    
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
        val webSocketPort: Int,
        val smsEnabled: Boolean,
        val hasPermission: Boolean,
        val hasReadPermission: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Pornește serverul HTTP și WebSocket
     */
    fun start(): Boolean {
        var httpStarted = false
        var wsStarted = false
        
        // Start HTTP Server
        try {
            server = HttpServer.create(InetSocketAddress(port), 0).apply {
                createContext("/sms/status", StatusHandler())
                createContext("/sms/inbox", InboxHandler())
                createContext("/sms/send", SendSmsHandler())
                executor = java.util.concurrent.Executors.newCachedThreadPool()
                start()
            }
            httpStarted = true
        } catch (e: Exception) {
            android.util.Log.e("SmsGateway", "HTTP Server error: ${e.message}")
        }
        
        // Start WebSocket Server
        try {
            webSocketServer = SmsWebSocketServer(context, port + 1) // Port 8889
            wsStarted = webSocketServer?.start() ?: false
        } catch (e: Exception) {
            android.util.Log.e("SmsGateway", "WebSocket Server error: ${e.message}")
        }
        
        return httpStarted
    }
    
    /**
     * Oprește serverul HTTP și WebSocket
     */
    fun stop() {
        server?.stop(0)
        server = null
        webSocketServer?.stop()
        webSocketServer = null
    }
    
    /**
     * Verifică dacă serverul rulează
     */
    fun isRunning(): Boolean = server != null
    
    /**
     * Returnează URL-ul gateway-ului HTTP
     */
    fun getUrl(): String = "http://0.0.0.0:$port"
    
    /**
     * Returnează URL-ul WebSocket
     */
    fun getWebSocketUrl(): String = webSocketServer?.getUrl() ?: "ws://0.0.0.0:${port + 1}"
    
    /**
     * Trimite notificare WebSocket pentru SMS trimis
     */
    fun notifySmsSent(to: String, body: String, success: Boolean) {
        webSocketServer?.notifySmsSent(to, body, success)
    }
    
    // ============ HANDLERS ============
    
    private inner class StatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            when (exchange.requestMethod) {
                "GET" -> {
                    val status = GatewayStatus(
                        status = if (isRunning()) "running" else "stopped",
                        port = port,
                        webSocketPort = port + 1,
                        smsEnabled = smsManager.hasTelephonyFeature(),
                        hasPermission = smsManager.canSendSms(),
                        hasReadPermission = smsInbox.hasReadSmsPermission()
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
    
    private inner class InboxHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            // Verifică API Key
            if (!checkApiKey(exchange)) return
            
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            
            when {
                method == "GET" && path == "/sms/inbox" -> handleListInbox(exchange)
                method == "GET" && path.startsWith("/sms/inbox/") -> handleGetMessage(exchange, path)
                method == "POST" && path.contains("/read") -> handleMarkAsRead(exchange, path)
                method == "DELETE" && path.startsWith("/sms/inbox/") -> handleDeleteMessage(exchange, path)
                method == "OPTIONS" -> handleOptions(exchange)
                else -> sendError(exchange, 405, "Method Not Allowed")
            }
        }
        
        private fun handleListInbox(exchange: HttpExchange) {
            // Parse query parameters
            val query = exchange.requestURI.query ?: ""
            val params = parseQueryParams(query)
            
            val limit = params["limit"]?.toIntOrNull() ?: 50
            val onlyUnread = params["unread"]?.toBoolean() ?: false
            val fromNumber = params["from"]
            
            val inbox = smsInbox.readInbox(
                limit = limit,
                onlyUnread = onlyUnread,
                fromNumber = fromNumber
            )
            
            sendJsonResponse(exchange, 200, inbox)
        }
        
        private fun handleGetMessage(exchange: HttpExchange, path: String) {
            val messageId = path.substringAfterLast("/")
            val message = smsInbox.readById(messageId)
            
            if (message != null) {
                sendJsonResponse(exchange, 200, message)
            } else {
                sendError(exchange, 404, "Message not found")
            }
        }
        
        private fun handleMarkAsRead(exchange: HttpExchange, path: String) {
            val messageId = path.substringAfter("/sms/inbox/").substringBefore("/read")
            val success = smsInbox.markAsRead(messageId)
            
            if (success) {
                sendJsonResponse(exchange, 200, mapOf("success" to true, "id" to messageId))
            } else {
                sendError(exchange, 400, "Failed to mark as read")
            }
        }
        
        private fun handleDeleteMessage(exchange: HttpExchange, path: String) {
            val messageId = path.substringAfterLast("/")
            val success = smsInbox.deleteMessage(messageId)
            
            if (success) {
                sendJsonResponse(exchange, 200, mapOf("success" to true, "id" to messageId, "deleted" to true))
            } else {
                sendError(exchange, 400, "Failed to delete message")
            }
        }
        
        private fun handleOptions(exchange: HttpExchange) {
            val headers = exchange.responseHeaders
            headers.add("Access-Control-Allow-Origin", "*")
            headers.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
            headers.add("Access-Control-Allow-Headers", "Content-Type, X-API-Key")
            exchange.sendResponseHeaders(204, -1)
        }
    }
    
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
            if (!checkApiKey(exchange)) return
            
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
                    
                    // Notifică WebSocket
                    notifySmsSent(request.to, request.message, result.ok)
                    
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
    
    // ============ UTILS ============
    
    private fun checkApiKey(exchange: HttpExchange): Boolean {
        val requestApiKey = exchange.requestHeaders.getFirst("X-API-Key")
        if (requestApiKey != apiKey) {
            sendError(exchange, 401, "Unauthorized: Invalid API Key")
            return false
        }
        return true
    }
    
    private fun parseQueryParams(query: String): Map<String, String> {
        return query.split("\u0026")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
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