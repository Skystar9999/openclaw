package ai.openclaw.android.sms

import android.content.Context
import ai.openclaw.android.node.SmsManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SMS Gateway API - HTTP server pentru trimitere și citire SMS prin SIM
 * Folosește NanoHTTPD pentru compatibilitate Android
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
) : NanoHTTPD(port) {
    
    private val smsManager = SmsManager(context)
    private val smsInbox = SmsInboxReader(context)
    private val json = Json { prettyPrint = true }
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
    override fun start() {
        super.start()
        
        // Start WebSocket Server
        try {
            webSocketServer = SmsWebSocketServer(context, port + 1)
            webSocketServer?.start()
        } catch (e: Exception) {
            android.util.Log.e("SmsGateway", "WebSocket error: ${e.message}")
        }
    }
    
    /**
     * Oprește serverul HTTP și WebSocket
     */
    override fun stop() {
        super.stop()
        webSocketServer?.stop()
    }
    
    /**
     * Verifică dacă serverul rulează
     */
    fun isRunning(): Boolean = isAlive
    
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
    
    /**
     * Handler principal pentru toate request-urile HTTP
     */
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        return try {
            when {
                // Status endpoint (no auth required)
                uri == "/sms/status" && method == Method.GET -> 
                    handleStatus()
                
                // Inbox endpoints (require auth)
                uri == "/sms/inbox" && method == Method.GET -> {
                    if (!checkApiKey(session)) return newFixedLengthResponse(
                        Response.Status.UNAUTHORIZED, 
                        MIME_PLAINTEXT, 
                        "{\"error\":\"Unauthorized\"}"
                    )
                    handleListInbox(session)
                }
                uri.startsWith("/sms/inbox/") && method == Method.GET -> {
                    if (!checkApiKey(session)) return newFixedLengthResponse(
                        Response.Status.UNAUTHORIZED, 
                        MIME_PLAINTEXT, 
                        "{\"error\":\"Unauthorized\"}"
                    )
                    val id = uri.substringAfterLast("/")
                    handleGetMessage(id)
                }
                uri.contains("/read") && method == Method.POST -> {
                    if (!checkApiKey(session)) return newFixedLengthResponse(
                        Response.Status.UNAUTHORIZED, 
                        MIME_PLAINTEXT, 
                        "{\"error\":\"Unauthorized\"}"
                    )
                    val id = uri.substringAfter("/sms/inbox/").substringBefore("/read")
                    handleMarkAsRead(id)
                }
                uri.startsWith("/sms/inbox/") && method == Method.DELETE -> {
                    if (!checkApiKey(session)) return newFixedLengthResponse(
                        Response.Status.UNAUTHORIZED, 
                        MIME_PLAINTEXT, 
                        "{\"error\":\"Unauthorized\"}"
                    )
                    val id = uri.substringAfterLast("/")
                    handleDeleteMessage(id)
                }
                
                // Send SMS endpoint (require auth)
                uri == "/sms/send" && method == Method.POST -> {
                    if (!checkApiKey(session)) return newFixedLengthResponse(
                        Response.Status.UNAUTHORIZED, 
                        MIME_PLAINTEXT, 
                        "{\"error\":\"Unauthorized\"}"
                    )
                    handleSendSms(session)
                }
                
                // OPTIONS for CORS
                method == Method.OPTIONS -> handleOptions()
                
                // Default - not found
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, 
                    MIME_PLAINTEXT, 
                    "{\"error\":\"Not Found\"}"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("SmsGateway", "Error handling request: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, 
                MIME_PLAINTEXT, 
                "{\"error\":\"${e.message}\"}"
            )
        }
    }
    
    // ============ HANDLERS ============
    
    private fun handleStatus(): Response {
        val status = GatewayStatus(
            status = if (isAlive) "running" else "stopped",
            port = port,
            webSocketPort = port + 1,
            smsEnabled = smsManager.hasTelephonyFeature(),
            hasPermission = smsManager.canSendSms(),
            hasReadPermission = smsInbox.hasReadSmsPermission()
        )
        return newJsonResponse(status)
    }
    
    private fun handleListInbox(session: IHTTPSession): Response {
        val params = session.parameters
        val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
        val onlyUnread = params["unread"]?.firstOrNull()?.toBoolean() ?: false
        val fromNumber = params["from"]?.firstOrNull()
        
        val inbox = smsInbox.readInbox(
            limit = limit,
            onlyUnread = onlyUnread,
            fromNumber = fromNumber
        )
        
        return newJsonResponse(inbox)
    }
    
    private fun handleGetMessage(messageId: String): Response {
        val message = smsInbox.readById(messageId)
        
        return if (message != null) {
            newJsonResponse(message)
        } else {
            newFixedLengthResponse(
                Response.Status.NOT_FOUND, 
                MIME_PLAINTEXT, 
                "{\"error\":\"Message not found\"}"
            )
        }
    }
    
    private fun handleMarkAsRead(messageId: String): Response {
        val success = smsInbox.markAsRead(messageId)
        
        return if (success) {
            newJsonResponse(mapOf("success" to true, "id" to messageId))
        } else {
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, 
                MIME_PLAINTEXT, 
                "{\"error\":\"Failed to mark as read\"}"
            )
        }
    }
    
    private fun handleDeleteMessage(messageId: String): Response {
        val success = smsInbox.deleteMessage(messageId)
        
        return if (success) {
            newJsonResponse(mapOf("success" to true, "id" to messageId, "deleted" to true))
        } else {
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, 
                MIME_PLAINTEXT, 
                "{\"error\":\"Failed to delete message\"}"
            )
        }
    }
    
    private fun handleSendSms(session: IHTTPSession): Response {
        return try {
            // Citește body
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val body = bodyMap["postData"] ?: "{}"
            
            val request = json.decodeFromString<SendSmsRequest>(body)
            
            // Trimite SMS în background (async, dar returnăm response sincron)
            scope.launch {
                val paramsJson = """{"to":"${request.to}","message":"${request.message}"}"""
                val result = smsManager.send(paramsJson)
                
                // Notifică WebSocket
                notifySmsSent(request.to, request.message, result.ok)
            }
            
            // Returnează response imediat (async)
            val response = SendSmsResponse(
                success = true,
                messageId = generateMessageId(),
                error = null
            )
            
            newJsonResponse(response)
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, 
                MIME_PLAINTEXT, 
                "{\"error\":\"Bad Request: ${e.message}\"}"
            )
        }
    }
    
    private fun handleOptions(): Response {
        val response = newFixedLengthResponse(Response.Status.NO_CONTENT, "", "")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, X-API-Key")
        return response
    }
    
    // ============ UTILS ============
    
    private fun checkApiKey(session: IHTTPSession): Boolean {
        val requestApiKey = session.headers["x-api-key"]
        return requestApiKey == apiKey
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
    
    private fun generateMessageId(): String {
        return "sms_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}