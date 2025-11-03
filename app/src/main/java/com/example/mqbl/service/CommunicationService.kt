package com.example.mqbl.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

// ▼▼▼ 수정된 코드 (10k -> 16k 변환) ▼▼▼
// 폰 마이크(로컬)는 서버와 동일한 16000Hz를 유지
private const val LOCAL_AUDIO_SAMPLE_RATE = 16000
// ▲▲▲ 수정된 코드 ▲▲▲
private const val LOCAL_AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val LOCAL_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
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
        fun getIsPhoneMicModeEnabledFlow(): StateFlow<Boolean> = _isPhoneMicModeEnabled.asStateFlow()
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

    // 스마트폰 마이크 관련 변수
    private val _isPhoneMicModeEnabled = MutableStateFlow(false)
    private var localAudioRecordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var localAudioBufferSize = 0

    // --- PC 서버 TCP/IP ---
    private var serverAudioSocket: Socket? = null // 오디오 소켓
    private var serverCommandSocket: Socket? = null // 명령어 소켓

    private var serverAudioOutputStream: OutputStream? = null
    private var serverCommandOutputStream: OutputStream? = null
    // (문제 2 해결됨)
    private var serverCommandBufferedReader: BufferedReader? = null

    private var serverConnectionJob: Job? = null // 통합 연결 관리 Job

    private var currentServerIp: String = ""
    private var currentServerPort: Int = 0
    private val _serverTcpUiState = MutableStateFlow(TcpUiState(connectionStatus = "서버: 연결 끊김"))
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

        lifecycleScope.launch {
            settingsRepository.isPhoneMicModeEnabledFlow.first().let { enabled ->
                _isPhoneMicModeEnabled.value = enabled
                if (enabled) {
                    startLocalAudioRecording()
                }
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
                updateNotificationCombined()
                Log.i(TAG_SERVICE, "Foreground service started explicitly.")
            }
            ACTION_STOP_FOREGROUND -> {
                Log.i(TAG_SERVICE, "Stopping foreground service notification...")
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            else -> {
                lifecycleScope.launch {
                    if (settingsRepository.isBackgroundExecutionEnabledFlow.first()) {
                        updateNotificationCombined()
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
        stopLocalAudioRecording()
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

    /**
     * 스마트폰 마이크 모드를 설정합니다.
     * @param enabled 활성화 여부
     * @return 권한이 있고 작업이 성공하면 true, 권한이 없으면 false 반환
     */
    fun setPhoneMicMode(enabled: Boolean): Boolean {
        if (_isPhoneMicModeEnabled.value == enabled) return true // 이미 원하는 상태임

        if (enabled) {
            // --- 모드를 켜려고 할 때 ---
            // 1. 권한부터 확인
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                showToast("오디오 녹음 권한이 없습니다. 앱 설정에서 권한을 허용해주세요.")
                Log.e(TAG_SERVICE, "setPhoneMicMode(true) failed: RECORD_AUDIO permission not granted.")
                return false // ViewModel에 실패를 알림
            }

            // 2. 권한이 있으면 로직 실행
            _isPhoneMicModeEnabled.value = true
            if (_isRecording.value) {
                stopAndSaveAudioRecording()
            }
            startLocalAudioRecording()
            if (_mainUiState.value.isEspConnected) {
                sendCommandToEsp32("PAUSE_AUDIO")
            }
            updateNotificationCombined()
            return true // ViewModel에 성공을 알림

        } else {
            // --- 모드를  끌 때 ---
            _isPhoneMicModeEnabled.value = false
            stopLocalAudioRecording()
            if (_mainUiState.value.isEspConnected) {
                sendCommandToEsp32("RESUME_AUDIO")
            }
            updateNotificationCombined()
            return true // ViewModel에 성공을 알림
        }
    }

    fun startAudioRecording() {
        if (_isPhoneMicModeEnabled.value) {
            showToast("스마트폰 마이크 모드 중에는 파일 녹음을 할 수 없습니다.")
            return
        }
        if (!_mainUiState.value.isEspConnected) {
            showToast("스마트 넥밴드가 연결되지 않아 녹음을 시작할 수 없습니다.")
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
                _mainUiState.update { it.copy(status = "스마트 넥밴드: 연결 중...", isConnecting = true, connectError = null) }
                updateNotificationCombined()

                socket = Socket()
                socket.connect(InetSocketAddress(ip, port), SOCKET_TIMEOUT)
                socket.tcpNoDelay = true

                _mainUiState.update { it.copy(status = "스마트 넥밴드: 연결됨", isConnecting = false, isEspConnected = true, espDeviceName = "스마트 넥밴드") }
                updateNotificationCombined()

                if (_isPhoneMicModeEnabled.value) {
                    sendToEsp32("PAUSE_AUDIO")
                } else {
                    sendToEsp32("RESUME_AUDIO")
                }

                val inputStream = socket.getInputStream()
                val outputStream = socket.getOutputStream()

                // (버퍼 크기 4096 유지)
                val buffer = ByteArray(4096)

                while (currentCoroutineContext().isActive) {
                    val messageToSend = esp32OutgoingMessages.tryReceive().getOrNull()
                    if (messageToSend != null) {
                        try {
                            outputStream.write((messageToSend + "\n").toByteArray(Charsets.UTF_8))
                            outputStream.flush()
                            Log.i(TAG_ESP32_TCP, "Sent: $messageToSend")
                        } catch (e: IOException) {
                            Log.e(TAG_ESP32_TCP, "Write failed, closing connection.", e)
                            break
                        }
                    }

                    if (inputStream.available() > 0) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            Log.v(TAG_ESP32_TCP, "Read $bytesRead bytes from ESP32 (10kHz)")
                            val audioData = buffer.copyOf(bytesRead) // 10kHz 데이터

                            // ▼▼▼ 수정된 코드 (10k -> 16k 변환) ▼▼▼
                            // 10kHz -> 16kHz로 리샘플링
                            val resampledAudioData = resample10kTo16k(audioData)
                            Log.v(TAG_SERVICE, "Resampled ${audioData.size} bytes (10k) -> ${resampledAudioData.size} bytes (16k)")

                            if (_isRecording.value) {
                                // 16kHz로 변환된 데이터를 녹음
                                audioRecordingStream?.write(resampledAudioData)
                            }

                            if (_serverTcpUiState.value.isConnected && !_isPhoneMicModeEnabled.value) {
                                // 16kHz로 변환된 데이터를 서버로 전송
                                sendAudioToServer(resampledAudioData)
                            }
                            // ▲▲▲ 수정된 코드 ▲▲▲

                        } else if (bytesRead < 0) {
                            Log.w(TAG_ESP32_TCP, "Read -1, connection closed by peer.")
                            break
                        }
                    }

                    delay(5)
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
                _mainUiState.update { it.copy(isConnecting = false, isEspConnected = false, espDeviceName = null, status = "스마트 넥밴드: 연결 끊김") }
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

                // (문제 2 해결됨)
                // 1. 오디오 소켓 (6789)은 쓰기 전용으로 설정
                serverAudioSocket = Socket()
                serverAudioSocket!!.connect(InetSocketAddress(ip, port), SOCKET_TIMEOUT)
                serverAudioOutputStream = serverAudioSocket!!.getOutputStream()
                Log.i(TAG_SERVER_TCP, "Audio socket connected to $ip:$port (Write-Only)")

                // 2. 명령어 소켓 (6790)은 읽기/쓰기용으로 설정
                val commandPort = port + 1
                serverCommandSocket = Socket()
                serverCommandSocket!!.connect(InetSocketAddress(ip, commandPort), SOCKET_TIMEOUT)
                serverCommandOutputStream = serverCommandSocket!!.getOutputStream()
                serverCommandBufferedReader = BufferedReader(InputStreamReader(serverCommandSocket!!.getInputStream()))
                Log.i(TAG_SERVER_TCP, "Command socket connected to $ip:$commandPort (Read/Write)")

                _serverTcpUiState.update { it.copy(isConnected = true, connectionStatus = "서버: 연결됨", errorMessage = null) }
                updateNotificationCombined()

                // STT 결과 수신 루프 시작
                startServerKeywordReceiveLoop()

            } catch (e: Exception) {
                Log.e(TAG_SERVER_TCP, "Server Connection Error", e)
                _serverTcpUiState.update { it.copy(isConnected = false, connectionStatus = "서버: 연결 실패", errorMessage = e.message) }
                updateNotificationCombined()
                closeServerSockets()
            }
        }
    }

    private fun startServerKeywordReceiveLoop() {
        // (문제 2 해결됨)
        serverConnectionJob = lifecycleScope.launch(Dispatchers.IO) {
            while (this.isActive && serverCommandSocket?.isConnected == true) { // 명령어 소켓 감시
                try {
                    val line = serverCommandBufferedReader?.readLine() // 명령어 소켓에서 읽음
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
        // (문제 2 해결됨)
        try { serverAudioOutputStream?.close() } catch (e: IOException) {}
        try { serverAudioSocket?.close() } catch (e: IOException) {}
        try { serverCommandOutputStream?.close() } catch (e: IOException) {}
        try { serverCommandBufferedReader?.close() } catch (e: IOException) {}
        try { serverCommandSocket?.close() } catch (e: IOException) {}
        serverAudioSocket = null
        serverAudioOutputStream = null
        serverCommandSocket = null
        serverCommandOutputStream = null
        serverCommandBufferedReader = null

        Log.d(TAG_SERVER_TCP, "Server sockets closed.")
    }

    @SuppressLint("MissingPermission")
    private fun startLocalAudioRecording() {
        if (localAudioRecordingJob?.isActive == true) return

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG_SERVICE, "startLocalAudioRecording called, but permission is missing!")
            lifecycleScope.launch {
                settingsRepository.setPhoneMicMode(false)
            }
            return
        }

        try {
            // ▼▼▼ 수정된 코드 (10k -> 16k 변환) ▼▼▼
            // 폰 마이크는 16000Hz 유지
            localAudioBufferSize = AudioRecord.getMinBufferSize(LOCAL_AUDIO_SAMPLE_RATE, LOCAL_AUDIO_CHANNEL_CONFIG, LOCAL_AUDIO_FORMAT)
            if (localAudioBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                showToast("오디오 녹음 장치를 초기화할 수 없습니다. (샘플링 속도 16kHz 미지원)")
                Log.e(TAG_SERVICE, "Device does not support 16000Hz sampling rate.")
                // ▲▲▲ 수정된 코드 ▲▲▲
                return
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                LOCAL_AUDIO_SAMPLE_RATE,
                LOCAL_AUDIO_CHANNEL_CONFIG,
                LOCAL_AUDIO_FORMAT,
                localAudioBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                showToast("오디오 녹음 장치 초기화 실패")
                Log.e(TAG_SERVICE, "AudioRecord initialization failed.")
                return
            }

            audioRecord?.startRecording()
            Log.i(TAG_SERVICE, "Smartphone Mic Recording Started (for Server). Buffer size: $localAudioBufferSize")

            localAudioRecordingJob = lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ByteArray(localAudioBufferSize)
                while (isActive) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        if (_serverTcpUiState.value.isConnected && _isPhoneMicModeEnabled.value) {
                            // 폰 마이크는 16kHz이므로 변환 없이 바로 전송
                            sendAudioToServer(buffer.copyOf(bytesRead))
                        }
                    } else if (bytesRead < 0) {
                        Log.e(TAG_SERVICE, "Error reading from AudioRecord: $bytesRead")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG_SERVICE, "Failed to start local audio recording", e)
            showToast("마이크 시작 오류: ${e.message}")
            stopLocalAudioRecording()
        }
    }

    private fun stopLocalAudioRecording() {
        if (localAudioRecordingJob?.isActive == true) {
            localAudioRecordingJob?.cancel()
            localAudioRecordingJob = null
        }
        if (audioRecord != null) {
            try {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.stop()
                }
                audioRecord?.release()
                Log.i(TAG_SERVICE, "Smartphone Mic Recording Stopped.")
            } catch (e: IllegalStateException) {
                Log.e(TAG_SERVICE, "Error stopping AudioRecord", e)
            }
            audioRecord = null
        }
    }

    // --- Hub Listeners & Notifications ---
    private fun listenForServerToEsp32Messages() {
        lifecycleScope.launch {
            CommunicationHub.serverToEsp32Flow.collect { message ->
                Log.i(TAG_SERVICE, "Service received message from Hub: $message")

                var commandToSendToEsp32: String? = null
                val receivedKeywords = message.trim().split(',').map { it.trim() }.filter { it.isNotEmpty() }

                val alarmKeywordsDetected = receivedKeywords.filter { received ->
                    isAlarmKeyword(received.lowercase())
                }

                if (alarmKeywordsDetected.isNotEmpty()) {
                    commandToSendToEsp32 = "VIBRATE_BOTH"
                    val description = "'${alarmKeywordsDetected.joinToString()}' 경고 감지됨"
                    addDetectionEvent(description)
                    sendAlertNotification("🚨 위험 감지!", description)
                } else {
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

                commandToSendToEsp32?.let { command ->
                    if (_mainUiState.value.isEspConnected) {
                        Log.d(TAG_ESP32_TCP, "Service sending command ('$command') to ESP32.")
                        sendToEsp32(command)
                    } else {
                        Log.w(TAG_ESP32_TCP, "Service cannot send command to ESP32: Not connected.")
                    }
                }
            }
        }
    }

    private fun isAlarmKeyword(keyword: String): Boolean {
        return keyword in listOf("siren", "horn", "boom")
    }

    private fun updateNotificationCombined() {
        lifecycleScope.launch {
            if (!settingsRepository.isBackgroundExecutionEnabledFlow.first()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                return@launch
            }

            val isPhoneMicMode = _isPhoneMicModeEnabled.value
            val isServerConnected = _serverTcpUiState.value.isConnected
            val isEspConnected = _mainUiState.value.isEspConnected

            val statusText = when {
                isPhoneMicMode && isServerConnected -> "스마트폰 마이크 모드 실행 중"
                !isPhoneMicMode && isEspConnected && isServerConnected -> "넥밴드 모드 실행 중"
                (isPhoneMicMode || isEspConnected) && !isServerConnected -> "서버 연결 대기 중..."
                !isPhoneMicMode && !isEspConnected -> "넥밴드 연결 대기 중..."
                else -> "SmartNeckBand 실행 중"
            }
            updateNotification(statusText)
        }
    }

    private fun createNotificationChannel() {
        val name = "SmartNeckBand 통신 서비스"
        val descriptionText = "백그라운드 연결 상태 알림"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val alertChannelName = "SmartNeckBand 위험 감지 알림"
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
            .setContentTitle("SmartNeckBand")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        lifecycleScope.launch {
            if (!settingsRepository.isBackgroundExecutionEnabledFlow.first()) {
                Log.d(TAG_SERVICE, "Background execution disabled, skipping notification update.")
                return@launch
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
        }
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
                // ▼▼▼ 수정된 코드 (10k -> 16k 변환) ▼▼▼
                // 16kHz로 리샘플링된 pcmData가 저장되므로, 헤더도 16kHz로 생성
                val header = createWavHeader(pcmData.size, 16000)
                // ▲▲▲ 수정된 코드 ▲▲▲
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

    // ▼▼▼ 수정된 코드 (10k -> 16k 변환) ▼▼▼
    private fun createWavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        // ▲▲▲ 수정된 코드 ▲▲▲
        val headerSize = 44
        val totalSize = dataSize + headerSize - 8
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
        header.putInt(sampleRate) // 16000 전달됨
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

    // ▼▼▼ 신규 추가 함수 (10k -> 16k 리샘플러) ▼▼▼
    /**
     * 10kHz 16bit PCM(ByteArray)을 16kHz 16bit PCM(ByteArray)로 변환합니다.
     * @param input 10kHz PCM 데이터
     * @return 16kHz PCM 데이터
     */
    private fun resample10kTo16k(input: ByteArray): ByteArray {
        try {
            // 1. ByteArray -> ShortArray 변환 (16-bit little-endian)
            val inputBuffer = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
            val numSamples = input.size / 2
            if (numSamples == 0) return ByteArray(0)

            val inputSamples = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                inputSamples[i] = inputBuffer.short
            }

            // 2. 출력 샘플 수 계산 (10:16 = 5:8 비율)
            val outputNumSamples = (numSamples * 8.0 / 5.0).toInt()
            val outputSamples = ShortArray(outputNumSamples)

            // 3. 선형 보간 (Linear Interpolation) 수행
            for (i in 0 until outputNumSamples) {
                val in_idx_float = i * 5.0 / 8.0 // 16k 인덱스 -> 10k 인덱스 매핑
                val in_idx_int = in_idx_float.toInt()
                val fraction = in_idx_float - in_idx_int

                val s1 = inputSamples[in_idx_int]
                // 경계 값 처리: 마지막 샘플을 초과하지 않도록 함
                val s2 = if (in_idx_int + 1 < numSamples) inputSamples[in_idx_int + 1] else s1

                // 보간: s1 + (s2 - s1) * fraction
                outputSamples[i] = (s1 + (s2 - s1) * fraction).toInt().toShort()
            }

            // 4. ShortArray -> ByteArray 변환
            val outputBuffer = ByteBuffer.allocate(outputNumSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in outputSamples) {
                outputBuffer.putShort(sample)
            }
            return outputBuffer.array()

        } catch (e: Exception) {
            Log.e(TAG_SERVICE, "Resampling 10k->16k failed", e)
            return ByteArray(0) // 오류 발생 시 빈 배열 반환
        }
    }
    // ▲▲▲ 신규 추가 함수 ▲▲▲
}