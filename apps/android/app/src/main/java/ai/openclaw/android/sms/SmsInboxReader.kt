package ai.openclaw.android.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Citește SMS-urile primite din inbox
 * Necesită permisiunea READ_SMS
 */
class SmsInboxReader(private val context: Context) {
    
    private val json = Json { prettyPrint = true }
    
    @Serializable
    data class SmsMessage(
        val id: String,
        val threadId: String,
        val address: String,       // Număr expeditor
        val body: String,          // Conținut mesaj
        val date: Long,            // Timestamp Unix
        val dateFormatted: String, // Formatat pentru citire
        val read: Boolean,         // Citit/necitit
        val type: String           // "inbox", "sent", etc.
    )
    
    @Serializable
    data class InboxResponse(
        val messages: List<SmsMessage>,
        val totalCount: Int,
        val unreadCount: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Verifică dacă avem permisiunea READ_SMS
     */
    fun hasReadSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Citește SMS-uri din inbox
     * 
     * @param limit Număr maxim de mesaje (default: 50)
     * @param onlyUnread Doar mesaje necitite (default: false)
     * @param fromNumber Filtru după număr expeditor (opțional)
     */
    fun readInbox(
        limit: Int = 50,
        onlyUnread: Boolean = false,
        fromNumber: String? = null
    ): InboxResponse {
        if (!hasReadSmsPermission()) {
            return InboxResponse(
                messages = emptyList(),
                totalCount = 0,
                unreadCount = 0
            )
        }
        
        val messages = mutableListOf<SmsMessage>()
        var unreadCount = 0
        
        // URI pentru SMS inbox
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        
        // Construiește selecția
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        
        if (fromNumber != null) {
            selection = "${Telephony.Sms.ADDRESS} LIKE ?"
            selectionArgs = arrayOf("%$fromNumber%")
        }
        
        if (onlyUnread) {
            selection = if (selection != null) {
                "$selection AND ${Telephony.Sms.READ} = 0"
            } else {
                "${Telephony.Sms.READ} = 0"
            }
        }
        
        val cursor: Cursor? = context.contentResolver.query(
            uri,
            null, // Toate coloanele
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC" // Cele mai recente primele
        )
        
        cursor?.use {
            val idIndex = it.getColumnIndex(Telephony.Sms._ID)
            val threadIdIndex = it.getColumnIndex(Telephony.Sms.THREAD_ID)
            val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)
            val readIndex = it.getColumnIndex(Telephony.Sms.READ)
            val typeIndex = it.getColumnIndex(Telephony.Sms.TYPE)
            
            var count = 0
            while (it.moveToNext() && count < limit) {
                val id = it.getString(idIndex) ?: continue
                val threadId = it.getString(threadIdIndex) ?: ""
                val address = it.getString(addressIndex) ?: ""
                val body = it.getString(bodyIndex) ?: ""
                val date = it.getLong(dateIndex)
                val read = it.getInt(readIndex) == 1
                val type = when (it.getInt(typeIndex)) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
                    Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                    Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
                    else -> "unknown"
                }
                
                if (!read) unreadCount++
                
                messages.add(
                    SmsMessage(
                        id = id,
                        threadId = threadId,
                        address = address,
                        body = body,
                        date = date,
                        dateFormatted = formatDate(date),
                        read = read,
                        type = type
                    )
                )
                count++
            }
        }
        
        // Număr total de mesaje în inbox
        val totalCount = cursor?.count ?: 0
        cursor?.close()
        
        return InboxResponse(
            messages = messages,
            totalCount = totalCount,
            unreadCount = unreadCount
        )
    }
    
    /**
     * Citește un SMS specific după ID
     */
    fun readById(messageId: String): SmsMessage? {
        if (!hasReadSmsPermission()) return null
        
        val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, messageId)
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        
        return cursor?.use {
            if (it.moveToFirst()) {
                val idIndex = it.getColumnIndex(Telephony.Sms._ID)
                val threadIdIndex = it.getColumnIndex(Telephony.Sms.THREAD_ID)
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)
                val readIndex = it.getColumnIndex(Telephony.Sms.READ)
                val typeIndex = it.getColumnIndex(Telephony.Sms.TYPE)
                
                SmsMessage(
                    id = it.getString(idIndex) ?: "",
                    threadId = it.getString(threadIdIndex) ?: "",
                    address = it.getString(addressIndex) ?: "",
                    body = it.getString(bodyIndex) ?: "",
                    date = it.getLong(dateIndex),
                    dateFormatted = formatDate(it.getLong(dateIndex)),
                    read = it.getInt(readIndex) == 1,
                    type = when (it.getInt(typeIndex)) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
                        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                        else -> "unknown"
                    }
                )
            } else null
        }
    }
    
    /**
     * Marchează un SMS ca citit
     */
    fun markAsRead(messageId: String): Boolean {
        if (!hasReadSmsPermission()) return false
        
        return try {
            val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, messageId)
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.READ, 1)
            }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Șterge un SMS după ID
     */
    fun deleteMessage(messageId: String): Boolean {
        if (!hasReadSmsPermission()) return false
        
        return try {
            val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, messageId)
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }
    
    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}