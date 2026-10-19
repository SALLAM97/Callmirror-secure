package com.example.callmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Socket

class TransmitterService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var prefs: SharedPreferences

    companion object {
        const val TAG = "TransmitterService"
        const val CHANNEL_ID = "transmitter_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.callmirror.TRANSMITTER_START"
        const val ACTION_STOP = "com.example.callmirror.TRANSMITTER_STOP"
        const val ACTION_SEND_CALL_STATE = "com.example.callmirror.TRANSMITTER_SEND"

        const val EXTRA_STATE = "extra_state"
        const val EXTRA_NUMBER = "extra_number"

        // أضفنا حالة الرد هنا عشان نستخدمها مباشرة حتى لو مش متعرفة في CallPayload
        const val CMD_STATUS_ANSWER = "ANSWER"

        var isRunning = false
            private set

        var instance: TransmitterService? = null
            private set

        fun broadcastNotificationPayload(payload: CallPayload) {
            instance?.dispatchNotificationPayload(payload)
        }
    }

    private var cloudMirrorManager: CloudMirrorManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("call_mirror_prefs", Context.MODE_PRIVATE)
        createNotificationChannel()

        val isRemoteMode = prefs.getInt("app_mode", 0) == 2 // MODE_REMOTE
        val pairingCode = prefs.getString("cloud_pairing_code", "") ?: ""

        if (isRemoteMode && pairingCode.isNotBlank()) {
            cloudMirrorManager = CloudMirrorManager(
                context = applicationContext,
                onMessageReceived = { payload ->
                    handleIncomingCommand(payload.status)
                }
            )
            cloudMirrorManager?.connect(pairingCode)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            cloudMirrorManager?.disconnect()
            resetAudioRouting()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_SEND_CALL_STATE) {
            val state = intent?.getStringExtra(EXTRA_STATE) ?: ""
            val number = intent?.getStringExtra(EXTRA_NUMBER)
            handleCallStateChange(state, number)
            return START_STICKY
        }

        // Start Foreground Service
        val notification = buildNotification("Monitoring incoming calls (Hotspot & Cloud)...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
            startCommandListener()

            val enableCloudTransmit = prefs.getBoolean("enable_cloud_transmit", true)
            val pairingCode = prefs.getString("cloud_pairing_code", "") ?: ""
            if (enableCloudTransmit && pairingCode.isNotBlank() && cloudMirrorManager == null) {
                Log.d(TAG, "Starting CloudMirrorManager for Transmitter")
                cloudMirrorManager = CloudMirrorManager(
                    context = applicationContext,
                    onMessageReceived = { payload ->
                        handleIncomingCommand(payload.status)
                    }
                )
                cloudMirrorManager?.connect(pairingCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not startForeground: ${e.message}")
            isRunning = false
        }

        return START_STICKY
    }

    private var commandServerSocket: java.net.ServerSocket? = null

    private fun startCommandListener() {
        val cmdPort = prefs.getInt("transmitter_cmd_port", 8081)
        scope.launch {
            try {
                commandServerSocket?.close()
                commandServerSocket = java.net.ServerSocket(cmdPort)
                Log.d(TAG, "Transmitter command listener started on port $cmdPort")

                while (isRunning) {
                    val clientSocket = try {
                        commandServerSocket?.accept() ?: break
                    } catch (e: Exception) {
                        break
                    }

                    launch {
                        handleCommandClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command listener exception on port $cmdPort: ${e.message}")
            }
        }
    }

    private fun handleCommandClient(socket: Socket) {
        try {
            val code = prefs.getString("cloud_pairing_code", "") ?: ""
            val payload = SecureTransport.receive(socket, code)
            if (payload != null) {
                handleIncomingCommand(payload.status)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling command: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun handleIncomingCommand(status: String) {
        when (status) {
            CallPayload.STATUS_REJECT -> {
                Log.d(TAG, "Received authenticated REJECT command")
                rejectIncomingCall()
            }
            CMD_STATUS_ANSWER -> {
                Log.d(TAG, "Received authenticated ANSWER command")
                answerIncomingCall()
            }
        }
    }

    private fun answerIncomingCall() {
        Log.d(TAG, "Answering incoming phone call via TelecomManager & Routing Audio...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.ANSWER_PHONE_CALLS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    @Suppress("DEPRECATION")
                    telecomManager?.acceptRingingCall()
                    Log.d(TAG, "telecomManager.acceptRingingCall() executed successfully")

                    // توجيه الصوت للبلوتوث (أو السبيكر لو مفعل في الإعدادات) بعد نصف ثانية من فتح الخط
                    scope.launch {
                        delay(500)
                        routeAudioToBluetoothOrSpeaker()
                    }
                } else {
                    Log.w(TAG, "ANSWER_PHONE_CALLS permission not granted on transmitter")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to answer call: ${e.message}")
        }
    }

    private fun routeAudioToBluetoothOrSpeaker() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_CALL

            val useSpeakerFallback = prefs.getBoolean("use_speaker_fallback", false)
            if (!useSpeakerFallback) {
                // تفعيل مسار صوت البلوتوث للمكالمات (SCO)
                audioManager.isBluetoothScoOn = true
                audioManager.startBluetoothSco()
                Log.d(TAG, "Audio routed to Bluetooth SCO")
            } else {
                // خيار بديل لو حبيت تفتح السبيكر
                audioManager.isSpeakerphoneOn = true
                Log.d(TAG, "Audio routed to Speakerphone")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio routing error: ${e.message}")
        }
    }

    private fun resetAudioRouting() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
            if (audioManager.isSpeakerphoneOn) {
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting audio: ${e.message}")
        }
    }

    private fun rejectIncomingCall() {
        Log.d(TAG, "Rejecting/Ending phone call via TelecomManager...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.ANSWER_PHONE_CALLS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    @Suppress("DEPRECATION")
                    telecomManager?.endCall()
                    Log.d(TAG, "telecomManager.endCall() executed successfully")
                } else {
                    Log.w(TAG, "ANSWER_PHONE_CALLS permission not granted on transmitter")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end call: ${e.message}")
        } finally {
            resetAudioRouting()
        }
    }

    private fun handleCallStateChange(stateStr: String, number: String?) {
        val receiverIpRaw = prefs.getString("receiver_ip", "") ?: ""
        val receiverPort = prefs.getInt("receiver_port", 8080)

        val targetIps = if (receiverIpRaw.isNotBlank()) {
            receiverIpRaw.split("[,;\\s]+".toRegex())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
        } else {
            emptyList()
        }

        scope.launch {
            when (stateStr) {
                "RINGING", TelephonyManager.EXTRA_STATE_RINGING -> {
                    var callerName: String? = null
                    val callerNumber = number

                    if (!callerNumber.isNullOrBlank()) {
                        callerName = ContactUtils.getContactName(applicationContext, callerNumber)
                    }

                    val payload = CallPayload(
                        status = CallPayload.STATUS_RINGING,
                        number = callerNumber ?: "Unknown",
                        name = callerName ?: callerNumber ?: "Unknown Caller"
                    )
                    
                    targetIps.forEach { ip ->
                        launch {
                            sendPayload(ip, receiverPort, payload)
                        }
                    }

                    cloudMirrorManager?.sendPayload(payload)
                    Log.d(TAG, "Dispatched RINGING payload to cloud and ${targetIps.size} local devices")
                }
                "OFFHOOK", TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // المكالمة شغالة حالياً (تم الرد) - منقفلش الشاشة على التليفون الأساسي عشان يقدر يدوس إنهاء لما يخلص
                    Log.d(TAG, "Call is now OFFHOOK (Active)")
                }
                "IDLE", TelephonyManager.EXTRA_STATE_IDLE -> {
                    resetAudioRouting()
                    val payload = CallPayload(
                        status = CallPayload.STATUS_ENDED
                    )
                    targetIps.forEach { ip ->
                        launch {
                            sendPayload(ip, receiverPort, payload)
                        }
                    }

                    cloudMirrorManager?.sendPayload(payload)
                    Log.d(TAG, "Dispatched ENDED payload to cloud and ${targetIps.size} local devices")
                }
            }
        }
    }

    private fun sendPayload(ip: String, port: Int, payload: CallPayload) {
        val code = prefs.getString("cloud_pairing_code", "") ?: ""
        SecureTransport.send(ip, port, payload, code)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transmitter Call Mirror Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors phone calls and mirrors them to Receiver device"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, TransmitterService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CallMirror: Transmitter Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    fun dispatchNotificationPayload(payload: CallPayload) {
        val receiverIpRaw = prefs.getString("receiver_ip", "") ?: ""
        val receiverPort = prefs.getInt("receiver_port", 8080)

        val targetIps = if (receiverIpRaw.isNotBlank()) {
            receiverIpRaw.split("[,;\\s]+".toRegex())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
        } else {
            emptyList()
        }

        scope.launch {
            targetIps.forEach { ip ->
                launch {
                    sendPayload(ip, receiverPort, payload)
                }
            }
            cloudMirrorManager?.sendPayload(payload)
            Log.d(TAG, "Dispatched NOTIFICATION payload to cloud and ${targetIps.size} local devices")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        isRunning = false
        resetAudioRouting()
        try {
            commandServerSocket?.close()
        } catch (ignored: Exception) {}
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
