package ai.openclaw.android.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * WebSocket Server pentru notificări SMS în timp real
 * 
 * Endpoint: ws://<tablet-ip>:8889
 * 
 * Evenimente:
 * - sms:received - SMS nou primit
 * - sms:sent - SMS trimis
 * - sms:status - Status gateway
 */
class SmsWebSocketServer(
    private val context: Context,
    private val port: Int = 8889
) {
    private val json = Json { prettyPrint = true }
    private var server: WebSocketServerImpl? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var smsReceiver: SmsBroadcastReceiver? = null
    
    @Serializable
    data class WebSocketMessage(
        val type: String,
        val data: Map<String, String>? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    @Serializable
    data class SmsReceivedEvent(
        val id: String?,
        val from: String,
        val body: String,
        val timestamp: Long
    )
    
    /**
     * Pornește serverul WebSocket
     */
    fun start(): Boolean {
        return try {
            server = WebSocketServerImpl(InetSocketAddress(port)).apply {
                start()
            }
            registerSmsReceiver()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Oprește serverul WebSocket
     */
    fun stop() {
        unregisterSmsReceiver()
        server?.stop()
        server = null
    }
    
    /**
     * Verifică dacă serverul rulează
     */
    fun isRunning(): Boolean = server?.isRunning ?: false
    
    /**
     * Returnează URL-ul WebSocket
     */
    fun getUrl(): String = "ws://0.0.0.0:$port"
    
    /**
     * Trimite un mesaj către toți clienții conectați
     */
    fun broadcast(message: WebSocketMessage) {
        val jsonString = json.encodeToString(message)
        server?.broadcast(jsonString)
    }
    
    /**
     * Trimite notificare SMS primit
     */
    fun notifySmsReceived(from: String, body: String, id: String? = null) {
        val message = WebSocketMessage(
            type = "sms:received",
            data = mapOf(
                "id" to (id ?: "unknown"),
                "from" to from,
                "body" to body,
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
        broadcast(message)
    }
    
    /**
     * Trimite notificare SMS trimis
     */
    fun notifySmsSent(to: String, body: String, success: Boolean) {
        val message = WebSocketMessage(
            type = "sms:sent",
            data = mapOf(
                "to" to to,
                "body" to body,
                "success" to success.toString(),
                "timestamp" to System.currentTimeMillis().toString()
            )
        )
        broadcast(message)
    }
    
    /**
     * Trimite status gateway
     */
    fun broadcastStatus() {
        val message = WebSocketMessage(
            type = "sms:status",
            data = mapOf(
                "connected" to "true",
                "clients" to (server?.connections?.size ?: 0).toString()
            )
        )
        broadcast(message)
    }
    
    private fun registerSmsReceiver() {
        smsReceiver = SmsBroadcastReceiver { from, body, timestamp ->
            notifySmsReceived(from, body)
        }
        
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(smsReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(smsReceiver, filter)
        }
    }
    
    private fun unregisterSmsReceiver() {
        smsReceiver?.let {
            context.unregisterReceiver(it)
            smsReceiver = null
        }
    }
    
    /**
     * Implementare WebSocket Server
     */
    private inner class WebSocketServerImpl(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            android.util.Log.i("SmsWebSocket", "Client connected: ${conn.remoteSocketAddress}")
            broadcastStatus()
        }
        
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            android.util.Log.i("SmsWebSocket", "Client disconnected: ${conn.remoteSocketAddress}")
        }
        
        override fun onMessage(conn: WebSocket, message: String) {
            // Procesează mesaje primite de la clienți
            android.util.Log.i("SmsWebSocket", "Received: $message")
            
            // Poți adăuga aici logica pentru subscribe/unsubscribe la evenimente
            val response = WebSocketMessage(
                type = "ack",
                data = mapOf("received" to "true")
            )
            conn.send(json.encodeToString(response))
        }
        
        override fun onError(conn: WebSocket?, ex: Exception) {
            android.util.Log.e("SmsWebSocket", "Error: ${ex.message}")
        }
        
        override fun onStart() {
            android.util.Log.i("SmsWebSocket", "Server started on port $port")
        }
    }
    
    /**
     * BroadcastReceiver pentru SMS primite
     */
    private inner class SmsBroadcastReceiver(
        private val onSmsReceived: (from: String, body: String, timestamp: Long) -> Unit
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                val bundle = intent.extras
                if (bundle != null) {
                    val pdus = bundle.get("pdus") as? Array<*>
                    pdus?.forEach { pdu ->
                        val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray)
                        val from = smsMessage.originatingAddress ?: "Unknown"
                        val body = smsMessage.messageBody ?: ""
                        val timestamp = smsMessage.timestampMillis
                        
                        onSmsReceived(from, body, timestamp)
                    }
                }
            }
        }
    }
}