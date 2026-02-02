package ai.openclaw.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ai.openclaw.android.sms.SmsGatewayServer
import ai.openclaw.android.adb.AdbBridgeServer
import ai.openclaw.android.call.VoiceCallManager
import ai.openclaw.android.monitor.SystemMonitorServer
import ai.openclaw.android.camera.CameraServer
import ai.openclaw.android.files.FileManagerServer
import ai.openclaw.android.homeassistant.HomeAssistantBridge
import ai.openclaw.android.automation.AutomationEngine

class NodeForegroundService : Service() {
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var notificationJob: Job? = null
  private var lastRequiresMic = false
  private var didStartForeground = false
  private var smsGateway: SmsGatewayServer? = null
  private var adbBridge: AdbBridgeServer? = null
  private var voiceCallManager: VoiceCallManager? = null
  private var systemMonitor: SystemMonitorServer? = null
  private var cameraServer: CameraServer? = null
  private var fileManager: FileManagerServer? = null
  private var homeAssistantBridge: HomeAssistantBridge? = null
  private var automationEngine: AutomationEngine? = null

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
    val initial = buildNotification(title = "OpenClaw Node", text = "Starting…")
    startForegroundWithTypes(notification = initial, requiresMic = false)

    // Start all services
    startSmsGateway()
    startAdbBridge()
    startSystemMonitor()
    initVoiceCallManager()
    startCameraServer()
    startFileManager()
    startHomeAssistantBridge()
    startAutomationEngine()

    val runtime = (application as NodeApp).runtime
    notificationJob =
      scope.launch {
        combine(
          runtime.statusText,
          runtime.serverName,
          runtime.isConnected,
          runtime.voiceWakeMode,
          runtime.voiceWakeIsListening,
        ) { status, server, connected, voiceMode, voiceListening ->
          Quint(status, server, connected, voiceMode, voiceListening)
        }.collect { (status, server, connected, voiceMode, voiceListening) ->
          val title = if (connected) "OpenClaw Node · Connected" else "OpenClaw Node"
          val voiceSuffix =
            if (voiceMode == VoiceWakeMode.Always) {
              if (voiceListening) " · Voice Wake: Listening" else " · Voice Wake: Paused"
            } else {
              ""
            }
          val text = (server?.let { "$status · $it" } ?: status) + voiceSuffix

          val requiresMic =
            voiceMode == VoiceWakeMode.Always && hasRecordAudioPermission()
          startForegroundWithTypes(
            notification = buildNotification(title = title, text = text),
            requiresMic = requiresMic,
          )
        }
      }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        (application as NodeApp).runtime.disconnect()
        stopSelf()
        return START_NOT_STICKY
      }
    }
    // Keep running; connection is managed by NodeRuntime (auto-reconnect + manual).
    return START_STICKY
  }

  override fun onDestroy() {
    notificationJob?.cancel()
    scope.cancel()
    // Stop all services
    smsGateway?.stop()
    smsGateway = null
    adbBridge?.stop()
    adbBridge = null
    systemMonitor?.stop()
    systemMonitor = null
    cameraServer?.stop()
    cameraServer = null
    fileManager?.stop()
    fileManager = null
    homeAssistantBridge?.stop()
    homeAssistantBridge = null
    automationEngine?.stop()
    automationEngine = null
    super.onDestroy()
  }

  override fun onBind(intent: Intent?) = null

  private fun startSmsGateway() {
    try {
      smsGateway = SmsGatewayServer(
        context = this,
        port = 8888,
        apiKey = BuildConfig.SMS_GATEWAY_API_KEY ?: "development-key-change-in-production"
      )
      smsGateway?.start()
      val httpUrl = smsGateway?.getUrl()
      val wsUrl = smsGateway?.getWebSocketUrl()
      android.util.Log.i("NodeForegroundService", "SMS Gateway started: HTTP=$httpUrl, WS=$wsUrl")
    } catch (e: Exception) {
      android.util.Log.e("NodeForegroundService", "Error starting SMS Gateway: ${e.message}")
    }
  }

  private fun startAdbBridge() {
    try {
      adbBridge = AdbBridgeServer(context = this, port = 8890)
      adbBridge?.start()
      android.util.Log.i("NodeForegroundService", "ADB Bridge started on port 8890")
    } catch (e: Exception) {
      android.util.Log.e("NodeForegroundService", "Error starting ADB Bridge: ${e.message}")
    }
  }

  private fun initVoiceCallManager() {
    try {
      voiceCallManager = VoiceCallManager(context = this)
      android.util.Log.i("NodeForegroundService", "Voice Call Manager initialized")
    } catch (e: Exception) {
      android.util.Log.e("NodeForegroundService", "Error initializing Voice Call Manager: ${e.message}")
    }
  }

  private fun startSystemMonitor() {
    try {
      systemMonitor = SystemMonitorServer(context = this, port = 8892)
      systemMonitor?.start()
      android.util.Log.i("NodeForegroundService", "System Monitor started on port 8892")
    } catch (e: Exception) {
      android.util.Log.e("NodeForegroundService", "Error starting System Monitor: ${e.message}")
    }
  }

  private fun startCameraServer() {
    try {
      cameraServer = CameraServer(context = this, port = 8893)
      cameraServer?.start()
      android.util.Log.i("NodeForegroundService", "Camera Server started on port 8893")
    } catch (e: Exception) {
      android.util.Log.e("NodeForegroundService", "Error starting Camera Server: ${e.message}")
    }
  }

  private fun startFileManager() {
    try {
      fileManager = FileManagerServer(context = this, port = 8894)
      fileManager?.start()
      android.util.Log.i("NodeForegroundService", "File Manager started on port 8894")
    } catch (e: Exception) {
      android.util.Log.e("NodeForegroundService", "Error starting File Manager: ${e.message}")
    }
  }

  private fun startHomeAssistantBridge() {
    try {
      homeAssistantBridge = HomeAssistantBridge(context = this, port = 8895)
      homeAssistantBridge?.start()
      android.util.Log.i("NodeForegroundService", "Home Assistant Bridge started on port 8895")
    } catch (e: Exception) {
      android.util.Log.e("NodeForegroundService", "Error starting Home Assistant Bridge: ${e.message}")
    }
  }

  private fun startAutomationEngine() {
    try {
      automationEngine = AutomationEngine(context = this, port = 8896)
      automationEngine?.start()
      android.util.Log.i("NodeForegroundService", "Automation Engine started on port 8896")
    } catch (e: Exception) {
      android.util.Log.e("NodeForegroundService", "Error starting Automation Engine: ${e.message}")
    }
  }

  private fun ensureChannel() {
    val mgr = getSystemService(NotificationManager::class.java)
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        "Connection",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "OpenClaw node connection status"
        setShowBadge(false)
      }
    mgr.createNotificationChannel(channel)
  }

  private fun buildNotification(title: String, text: String): Notification {
    val launchIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val launchPending =
      PendingIntent.getActivity(
        this,
        1,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent = Intent(this, NodeForegroundService::class.java).setAction(ACTION_STOP)
    val stopPending =
      PendingIntent.getService(
        this,
        2,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(launchPending)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(0, "Disconnect", stopPending)
      .build()
  }

  private fun updateNotification(notification: Notification) {
    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.notify(NOTIFICATION_ID, notification)
  }

  private fun startForegroundWithTypes(notification: Notification, requiresMic: Boolean) {
    if (didStartForeground && requiresMic == lastRequiresMic) {
      updateNotification(notification)
      return
    }

    lastRequiresMic = requiresMic
    val types =
      if (requiresMic) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
      } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      }
    startForeground(NOTIFICATION_ID, notification, types)
    didStartForeground = true
  }

  private fun hasRecordAudioPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  companion object {
    private const val CHANNEL_ID = "connection"
    private const val NOTIFICATION_ID = 1

    private const val ACTION_STOP = "ai.openclaw.android.action.STOP"

    fun start(context: Context) {
      val intent = Intent(context, NodeForegroundService::class.java)
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, NodeForegroundService::class.java).setAction(ACTION_STOP)
      context.startService(intent)
    }
  }
}

private data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
