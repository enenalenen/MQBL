package com.example.mqbl.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.mqbl.MainActivity
import com.example.mqbl.R
import com.example.mqbl.common.CommunicationHub
import com.example.mqbl.data.SettingsRepository
import com.example.mqbl.ui.main.CustomSoundEvent
import com.example.mqbl.ui.main.DetectionEvent
import com.example.mqbl.ui.main.MainUiState
import com.example.mqbl.ui.tcp.TcpMessageItem
import com.example.mqbl.ui.tcp.TcpUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// --- 상수 정의 ---
private const val TAG_SERVICE = "CommService"
private const val TAG_ESP32_TCP = "CommService_ESP32"
private const val TAG_SERVER_TCP = "CommService_ServerTCP"

private const val MAX_DETECTION_LOG_SIZE = 10
private const val MAX_TCP_LOG_SIZE = 50
private const val ALERT_NOTIFICATION_CHANNEL_ID = "MQBL_Alert_Channel"
private const val ALERT_NOTIFICATION_ID = 2
private const val NOTIFICATION_CHANNEL_ID = "MQBL_Communication_Channel"
private const val NOTIFICATION_ID = 1
private const val SOCKET_TIMEOUT = 5000 // ms
// -----------------

class CommunicationService : LifecycleService() {

    companion object {
        const val ACTION_START_FOREGROUND = "com.example.mqbl.action.START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.example.mqbl.action.STOP_FOREGROUND"
    }

    inner class LocalBinder : Binder() {
        fun getService(): CommunicationService = this@CommunicationService
        fun getCustomSoundEventLogFlow(): StateFlow<List<CustomSoundEvent>> = _customSoundEventLog.asStateFlow()
        fun getMainUiStateFlow(): StateFlow<MainUiState> = _mainUiState.asStateFlow()
        fun getDetectionLogFlow(): StateFlow<List<DetectionEvent>> = _detectionEventLog.asStateFlow()
        fun getIsRecordingFlow(): StateFlow<Boolean> = _isRecording.asStateFlow()
        fun getServerTcpUiStateFlow(): StateFlow<TcpUiState> = _serverTcpUiState.asStateFlow()
        fun getReceivedServerTcpMessagesFlow(): StateFlow<List<TcpMessageItem>> = _receivedServerTcpMessages.asStateFlow()
    }
    private val binder = LocalBinder()

    private lateinit var settingsRepository: SettingsRepository

    private val _customSoundEventLog = MutableStateFlow<List<CustomSoundEvent>>(emptyList())
    private var customKeywords = listOf<String>()

    // --- ESP32 TCP ---
    private var esp32ConnectionJob: Job? = null
    private val esp32OutgoingMessages = Channel<String>(Channel.BUFFERED)
    private val _mainUiState = MutableStateFlow(MainUiState())
    private val _detectionEventLog = MutableStateFlow<List<DetectionEvent>>(emptyList())

    // 녹음 관련 변수
    private val _isRecording = MutableStateFlow(false)
    private var audioRecordingStream: ByteArrayOutputStream? = null

    // --- PC 서버 TCP/IP ---
    private var serverAudioSocket: Socket? = null // 오디오 소켓
    private var serverCommandSocket: Socket? = null // 명령어 소켓

    private var serverAudioOutputStream: OutputStream? = null
    private var serverCommandOutputStream: OutputStream? = null
    private var serverAudioBufferedReader: BufferedReader? = null

    private var serverConnectionJob: Job? = null // 통합 연결 관리 Job

    private var currentServerIp: String = ""
    private var currentServerPort: Int = 0
    private val _serverTcpUiState = MutableStateFlow(TcpUiState(connectionStatus = "PC서버: 연결 끊김"))
    private val _receivedServerTcpMessages = MutableStateFlow<List<TcpMessageItem>>(emptyList())

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG_SERVICE, "Service onCreate")
        settingsRepository = SettingsRepository.getInstance(this)

        lifecycleScope.launch {
            settingsRepository.customKeywordsFlow.collect { keywordsString ->
                customKeywords = keywordsString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        lifecycleScope.launch {
            settingsRepository.tcpServerIpFlow.collect { ip -> currentServerIp = ip }
        }
        lifecycleScope.launch {
            settingsRepository.tcpServerPortFlow.collect { portString ->
                currentServerPort = portString.toIntOrNull() ?: 0
            }
        }

        createNotificationChannel()
        listenForServerToEsp32Messages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.i(TAG_SERVICE, "Service onStartCommand Received with action: $action")

        when (action) {
            ACTION_START_FOREGROUND -> {
                startForeground(NOTIFICATION_ID, createNotification("서비스 실행 중..."))
                Log.i(TAG_SERVICE, "Foreground service started explicitly.")
            }
            ACTION_STOP_FOREGROUND -> {
                Log.i(TAG_SERVICE, "Stopping foreground service notification...")
                stopForeground(STOP_FOREGROUND_REMOVE)
                // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
                // 이 줄을 삭제하여 서비스가 완전히 종료되는 것을 방지합니다.
                // stopSelf()
                // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲
            }
            else -> {
                lifecycleScope.launch {
                    if (settingsRepository.isBackgroundExecutionEnabledFlow.first()) {
                        startForeground(NOTIFICATION_ID, createNotification("서비스 실행 중..."))
                        Log.i(TAG_SERVICE, "Service started, background execution is ENABLED.")
                    } else {
                        Log.i(TAG_SERVICE, "Service started, background execution is DISABLED.")
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        requestEsp32Disconnect()
        requestServerTcpDisconnect(userRequested = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // --- Public Methods ---
    fun requestEsp32Connect(ip: String, port: Int) {
        connectToEsp32(ip, port)
    }

    fun requestEsp32Disconnect() {
        disconnectFromEsp32()
    }

    fun sendVibrationValueToEsp32(value: Int) {
        sendToEsp32(value.toString())
    }

    fun sendCommandToEsp32(command: String) {
        sendToEsp32(command)
    }

    fun requestServerTcpConnect(ip: String, port: Int) {
        connectToServer(ip, port)
    }

    fun requestServerTcpDisconnect(userRequested: Boolean = true) {
        disconnectFromServer(userRequested)
    }

    fun sendToServer(message: String) {
        sendToServerCommand(message)
    }

    fun startAudioRecording() {
        if (!_mainUiState.value.isEspConnected) {
            showToast("ESP32가 연결되지 않아 녹음을 시작할 수 없습니다.")
            return
        }
        if (_isRecording.value) {
            showToast("이미 녹음이 진행 중입니다.")
            return
        }
        audioRecordingStream = ByteArrayOutputStream()
        _isRecording.value = true
        showToast("오디오 녹음을 시작합니다.")
        Log.i(TAG_SERVICE, "Audio recording started.")
    }

    fun stopAndSaveAudioRecording() {
        if (!_isRecording.value) return
        val audioData = audioRecordingStream?.toByteArray()
        _isRecording.value = false
        audioRecordingStream = null
        if (audioData != null && audioData.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) { saveAsWavFile(audioData) }
        } else {
            showToast("녹음된 오디오 데이터가 없습니다.")
        }
    }

    // --- ESP32 TCP Methods ---
    private fun sendToEsp32(message: String) {
        if (esp32ConnectionJob?.isActive != true) {
            Log.w(TAG_ESP32_TCP, "Cannot send message, ESP32 connection is not active.")
            return
        }
        esp32OutgoingMessages.trySend(message)
    }

    private fun disconnectFromEsp32() {
        if (esp32ConnectionJob?.isActive == true) {
            esp32ConnectionJob?.cancel()
        }
    }

    private fun connectToEsp32(ip: String, port: Int) {
        if (_mainUiState.value.isEspConnected || _mainUiState.value.isConnecting) {
            return
        }

        esp32ConnectionJob = lifecycleScope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                _mainUiState.update { it.copy(status = "ESP32: 연결 중...", isConnecting = true, connectError = null) }
                updateNotificationCombined()

                socket = Socket()
                socket.connect(InetSocketAddress(ip, port), SOCKET_TIMEOUT)
                socket.tcpNoDelay = true

                _mainUiState.update { it.copy(status = "ESP32: 연결됨", isConnecting = false, isEspConnected = true, espDeviceName = "ESP32 ($ip)") }
                updateNotificationCombined()

                val inputStream = socket.getInputStream()
                val outputStream = socket.getOutputStream()
                val buffer = ByteArray(2048)

                // Single loop for both reading and writing
                while (currentCoroutineContext().isActive) {
                    // 1. Check for and handle outgoing messages (non-blocking)
                    val messageToSend = esp32OutgoingMessages.tryReceive().getOrNull()
                    if (messageToSend != null) {
                        try {
                            outputStream.write((messageToSend + "\n").toByteArray(Charsets.UTF_8))
                            outputStream.flush()
                            Log.i(TAG_ESP32_TCP, "Sent: $messageToSend")
                        } catch (e: IOException) {
                            Log.e(TAG_ESP32_TCP, "Write failed, closing connection.", e)
                            break // Exit loop on write error
                        }
                    }

                    // 2. Check for and handle incoming audio data (non-blocking)
                    if (inputStream.available() > 0) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            Log.v(TAG_ESP32_TCP, "Read $bytesRead bytes from ESP32")
                            val audioData = buffer.copyOf(bytesRead)
                            if (_isRecording.value) {
                                audioRecordingStream?.write(audioData)
                            }
                            if (_serverTcpUiState.value.isConnected) {
                                sendAudioToServer(audioData)
                            }
                        } else if (bytesRead < 0) {
                            Log.w(TAG_ESP32_TCP, "Read -1, connection closed by peer.")
                            break // Exit loop
                        }
                    }

                    delay(5) // Prevent busy-waiting, allow other tasks to run
                }
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    Log.e(TAG_ESP32_TCP, "ESP32 Connection failed", e)
                    _mainUiState.update { it.copy(connectError = e.message) }
                }
            } finally {
                Log.i(TAG_ESP32_TCP, "ESP32 Connection job finishing.")
                socket?.close()
                stopAndSaveAudioRecording()
                _mainUiState.update { it.copy(isConnecting = false, isEspConnected = false, espDeviceName = null, status = "ESP32: 연결 끊김") }
                updateNotificationCombined()
            }
        }
    }

    // --- PC 서버 TCP Private Methods ---
    private fun connectToServer(ip: String, port: Int) {
        if (_serverTcpUiState.value.isConnected || _serverTcpUiState.value.connectionStatus.contains("연결 중")) {
            return
        }

        serverConnectionJob?.cancel()
        serverConnectionJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                _serverTcpUiState.update { it.copy(connectionStatus = "PC서버: 연결 중...", errorMessage = null) }
                updateNotificationCombined()

                // 1. 오디오 소켓 연결 (기존 포트)
                serverAudioSocket = Socket()
                serverAudioSocket!!.connect(InetSocketAddress(ip, port), SOCKET_TIMEOUT)
                serverAudioOutputStream = serverAudioSocket!!.getOutputStream()
                serverAudioBufferedReader = BufferedReader(InputStreamReader(serverAudioSocket!!.getInputStream()))
                Log.i(TAG_SERVER_TCP, "Audio socket connected to $ip:$port")

                // 2. 명령어 소켓 연결 (포트 + 1)
                val commandPort = port + 1
                serverCommandSocket = Socket()
                serverCommandSocket!!.connect(InetSocketAddress(ip, commandPort), SOCKET_TIMEOUT)
                serverCommandOutputStream = serverCommandSocket!!.getOutputStream()
                Log.i(TAG_SERVER_TCP, "Command socket connected to $ip:$commandPort")

                _serverTcpUiState.update { it.copy(isConnected = true, connectionStatus = "PC서버: 연결됨", errorMessage = null) }
                updateNotificationCombined()

                // 3. 오디오 소켓에서 키워드 수신 시작
                startServerKeywordReceiveLoop()

            } catch (e: Exception) {
                Log.e(TAG_SERVER_TCP, "Server Connection Error", e)
                _serverTcpUiState.update { it.copy(isConnected = false, connectionStatus = "PC서버: 연결 실패", errorMessage = e.message) }
                updateNotificationCombined()
                closeServerSockets()
            }
        }
    }

    private fun startServerKeywordReceiveLoop() {
        // 기존 startServerReceiveLoop의 이름을 변경하고, 오디오 소켓만 사용하도록 함
        serverConnectionJob = lifecycleScope.launch(Dispatchers.IO) {
            while (this.isActive && serverAudioSocket?.isConnected == true) {
                try {
                    val line = serverAudioBufferedReader?.readLine()
                    if (line != null) {
                        val newItem = TcpMessageItem(source = "$currentServerIp:$currentServerPort", payload = line)
                        _receivedServerTcpMessages.update { list -> (listOf(newItem) + list).take(MAX_TCP_LOG_SIZE) }
                        CommunicationHub.emitServerToEsp32(line)
                    } else {
                        Log.w(TAG_SERVER_TCP, "Keyword stream ended. Server closed connection.")
                        break
                    }
                } catch (e: IOException) {
                    Log.e(TAG_SERVER_TCP, "Keyword receive loop error.", e)
                    break
                }
            }
            if (this.isActive) {
                disconnectFromServer(false, "수신 중 연결 끊김")
            }
        }
    }

    private fun sendAudioToServer(data: ByteArray) {
        if (serverAudioSocket?.isConnected != true || serverAudioOutputStream == null) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                serverAudioOutputStream?.write(data)
                serverAudioOutputStream?.flush()
            } catch (e: Exception) {
                disconnectFromServer(false, "오디오 전송 중 연결 끊김")
            }
        }
    }

    private fun sendToServerCommand(message: String) {
        // sendToServerTcp -> sendToServerCommand로 이름 변경 및 명령어 소켓 사용
        if (serverCommandSocket?.isConnected != true || serverCommandOutputStream == null) {
            showToast("서버 명령어 채널에 연결되지 않았습니다.")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                serverCommandOutputStream?.write((message + "\n").toByteArray(Charsets.UTF_8))
                serverCommandOutputStream?.flush()
                val sentItem = TcpMessageItem(source = "앱 -> 서버 (명령어)", payload = message)
                _receivedServerTcpMessages.update { list -> (listOf(sentItem) + list).take(MAX_TCP_LOG_SIZE) }
            } catch (e: Exception) {
                // 명령어 전송 실패는 전체 연결을 끊을 필요는 없음
                Log.e(TAG_SERVER_TCP, "Failed to send command", e)
                showToast("서버로 명령어 전송 실패")
            }
        }
    }


    private fun disconnectFromServer(userRequested: Boolean, reason: String? = null) {
        serverConnectionJob?.cancel()
        closeServerSockets()
        val statusMessage = reason ?: if (userRequested) "연결 해제됨" else "연결 끊김"
        _serverTcpUiState.update {
            it.copy(isConnected = false, connectionStatus = "PC서버: $statusMessage", errorMessage = if (reason != null && !userRequested) reason else null)
        }
        updateNotificationCombined()
    }

    private fun closeServerSockets() {
        // 두 소켓을 모두 닫도록 수정
        try { serverAudioOutputStream?.close() } catch (e: IOException) {}
        try { serverAudioBufferedReader?.close() } catch (e: IOException) {}
        try { serverAudioSocket?.close() } catch (e: IOException) {}
        try { serverCommandOutputStream?.close() } catch (e: IOException) {}
        try { serverCommandSocket?.close() } catch (e: IOException) {}
        serverAudioSocket = null
        serverCommandSocket = null
        Log.d(TAG_SERVER_TCP, "Server sockets closed.")
    }

    // --- Hub Listeners & Notifications ---
    private fun listenForServerToEsp32Messages() {
        lifecycleScope.launch {
            CommunicationHub.serverToEsp32Flow.collect { message ->
                // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
                // 이 함수 전체를 아래 내용으로 교체합니다.
                // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
                Log.i(TAG_SERVICE, "Service received message from Hub: $message")

                var commandToSendToEsp32: String? = null
                val receivedKeywords = message.trim().split(',').map { it.trim() }.filter { it.isNotEmpty() }

                // 경고 키워드가 하나라도 있는지 먼저 확인
                val alarmKeywordsDetected = receivedKeywords.filter { received ->
                    isAlarmKeyword(received.lowercase())
                }

                if (alarmKeywordsDetected.isNotEmpty()) {
                    // 경고 키워드가 있으면, 가장 우선적으로 양쪽 진동 처리
                    commandToSendToEsp32 = "VIBRATE_BOTH"
                    val description = "'${alarmKeywordsDetected.joinToString()}' 경고 감지됨"
                    addDetectionEvent(description)
                    sendAlertNotification("🚨 위험 감지!", description)
                } else {
                    // 경고 키워드가 없으면, 사용자 정의 단어가 있는지 확인
                    val customKeywordsDetected = receivedKeywords.filter { received ->
                        customKeywords.any { custom -> received.equals(custom, ignoreCase = true) }
                    }
                    if (customKeywordsDetected.isNotEmpty()) {
                        commandToSendToEsp32 = "VIBRATE_RIGHT"
                        val description = "'${customKeywordsDetected.joinToString()}' 단어 감지됨"
                        addCustomSoundEvent(description)
                        sendAlertNotification("🗣️ 음성 감지!", description)
                    }
                }

                // 최종적으로 결정된 명령어가 있으면 ESP32로 전송
                commandToSendToEsp32?.let { command ->
                    if (_mainUiState.value.isEspConnected) {
                        Log.d(TAG_ESP32_TCP, "Service sending command ('$command') to ESP32.")
                        sendToEsp32(command)
                    } else {
                        Log.w(TAG_ESP32_TCP, "Service cannot send command to ESP32: Not connected.")
                    }
                }
                // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲
            }
        }
    }

    private fun isAlarmKeyword(keyword: String): Boolean {
        return keyword in listOf("siren", "horn", "boom")
    }

    private fun updateNotificationCombined() {
        lifecycleScope.launch {
            if (!settingsRepository.isBackgroundExecutionEnabledFlow.first()) return@launch
            val espStatus = _mainUiState.value.espDeviceName ?: "끊김"
            val serverStatusText = _serverTcpUiState.value.connectionStatus
            updateNotification("ESP32: $espStatus, $serverStatusText")
        }
    }

    private fun createNotificationChannel() {
        val name = "MQBL 통신 서비스"
        val descriptionText = "백그라운드 연결 상태 알림"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val alertChannelName = "MQBL 위험 감지 알림"
        val alertChannelDescription = "위험 상황 감지 시 알림"
        val alertImportance = NotificationManager.IMPORTANCE_HIGH
        val alertChannel = NotificationChannel(ALERT_NOTIFICATION_CHANNEL_ID, alertChannelName, alertImportance).apply {
            description = alertChannelDescription
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(alertChannel)
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MQBL 실행 중")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    private fun sendAlertNotification(title: String, contentText: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)
        val notification = NotificationCompat.Builder(this, ALERT_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun addDetectionEvent(description: String) {
        lifecycleScope.launch {
            val currentTime = timeFormatter.format(Date())
            val newEvent = DetectionEvent(description = description, timestamp = currentTime)
            _detectionEventLog.update { (listOf(newEvent) + it).take(MAX_DETECTION_LOG_SIZE) }
        }
    }

    private fun addCustomSoundEvent(description: String) {
        lifecycleScope.launch {
            val currentTime = timeFormatter.format(Date())
            val newEvent = CustomSoundEvent(description = description, timestamp = currentTime)
            _customSoundEventLog.update { (listOf(newEvent) + it).take(MAX_DETECTION_LOG_SIZE) }
        }
    }

    private fun saveAsWavFile(pcmData: ByteArray) {
        val resolver = applicationContext.contentResolver
        val audioCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val details = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "recorded_audio_$timestamp.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val audioUri = resolver.insert(audioCollection, details)
        if (audioUri == null) {
            showToast("오디오 파일 생성에 실패했습니다.")
            return
        }
        try {
            resolver.openOutputStream(audioUri)?.use { outputStream ->
                val header = createWavHeader(pcmData.size)
                outputStream.write(header)
                outputStream.write(pcmData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                details.clear()
                details.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(audioUri, details, null, null)
            }
            showToast("'Music' 폴더에 오디오 파일이 저장되었습니다.")
        } catch (e: IOException) {
            showToast("오디오 파일 저장 중 오류가 발생했습니다.")
        }
    }

    private fun createWavHeader(dataSize: Int): ByteArray {
        val headerSize = 44
        val totalSize = dataSize + headerSize - 8
        val sampleRate = 10000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        return header.array()
    }

    private fun showToast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}