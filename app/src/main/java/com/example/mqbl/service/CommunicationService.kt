package com.example.mqbl.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService // 단일 import
import androidx.lifecycle.lifecycleScope
import com.example.mqbl.MainActivity
import com.example.mqbl.R
import com.example.mqbl.common.CommunicationHub
import com.example.mqbl.ui.ble.BleUiState
import com.example.mqbl.ui.ble.DetectionEvent
import com.example.mqbl.ui.tcp.TcpUiState // 정확한 경로로 단일 import
import com.example.mqbl.ui.tcp.TcpMessageItem // 정확한 경로로 단일 import
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

// --- 상수 정의 ---
private const val TAG_SERVICE = "CommService"
private const val TAG_BLE = "CommService_BLE"
private const val TAG_TCP = "CommService_TCP"
private val BT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private const val MAX_DETECTION_LOG_SIZE = 10
private const val DEFAULT_TCP_SERVER_IP = "192.168.0.18"
private const val DEFAULT_TCP_SERVER_PORT = 12345
private const val MAX_TCP_LOG_SIZE = 50
private const val NOTIFICATION_CHANNEL_ID = "MQBL_Communication_Channel"
private const val NOTIFICATION_ID = 1
// -----------------

class CommunicationService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): CommunicationService = this@CommunicationService
        fun getBleUiStateFlow(): StateFlow<BleUiState> = _bleUiState.asStateFlow()
        fun getBondedDevicesFlow(): StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()
        fun getDetectionLogFlow(): StateFlow<List<DetectionEvent>> = _detectionEventLog.asStateFlow()
        fun getTcpUiStateFlow(): StateFlow<TcpUiState> = _tcpUiState.asStateFlow()
        fun getReceivedTcpMessagesFlow(): StateFlow<List<TcpMessageItem>> = _receivedTcpMessages.asStateFlow()
    }
    private val binder = LocalBinder()

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _bleUiState = MutableStateFlow(BleUiState())
    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    private val _detectionEventLog = MutableStateFlow<List<DetectionEvent>>(emptyList())

    private var tcpSocket: Socket? = null
    private var tcpPrintWriter: PrintWriter? = null
    private var tcpBufferedReader: BufferedReader? = null
    private var tcpReceiveJob: Job? = null
    private var currentServerIp: String = DEFAULT_TCP_SERVER_IP
    private var currentServerPort: Int = DEFAULT_TCP_SERVER_PORT

    private val _tcpUiState = MutableStateFlow(TcpUiState(connectionStatus = "TCP/IP: 연결 끊김"))
    private val _receivedTcpMessages = MutableStateFlow<List<TcpMessageItem>>(emptyList())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG_SERVICE, "Service onCreate")
        createNotificationChannel()
        initializeBle()
        listenForTcpToBleMessages()
        listenForBleToTcpMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG_SERVICE, "Service onStartCommand Received")
        startForeground(NOTIFICATION_ID, createNotification("서비스 실행 중..."))
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Log.i(TAG_SERVICE, "Service onBind")
        return binder
    }
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG_SERVICE, "Service onUnbind")
        return true
    }

    override fun onDestroy() {
        Log.w(TAG_SERVICE, "Service onDestroy")
        disconnectBle()
        disconnectTcpInternal(userRequested = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun requestBleConnect(device: BluetoothDevice) { connectToDevice(device) }
    fun requestBleDisconnect() { disconnectBle() }
    fun sendBleValue(value: Int) { sendValueInternal(value) }
    fun refreshBleState() {
        checkBlePermissionsAndLoadDevices()
        if (bluetoothAdapter?.isEnabled == false && _bleUiState.value.connectedDeviceName == null) {
            _bleUiState.update { it.copy(status = "상태: 블루투스 비활성화됨") }
        }
    }

    fun requestTcpConnect(ip: String, port: Int) {
        Log.i(TAG_TCP, "Request TCP Connect received for $ip:$port")
        currentServerIp = ip
        currentServerPort = port
        connectTcp()
    }

    fun requestTcpDisconnect() {
        Log.i(TAG_TCP, "Request TCP Disconnect received")
        disconnectTcpInternal(userRequested = true)
    }

    fun sendTcpMessage(message: String) {
        Log.d(TAG_TCP, "Request TCP Send Message received: $message")
        sendTcpData(message)
    }

    private fun initializeBle() {
        Log.d(TAG_BLE, "Initializing BLE...")
        bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            _bleUiState.update { it.copy(status = "상태: 블루투스 미지원", isBluetoothSupported = false) }
            updateNotificationCombined()
        } else {
            checkBlePermissionsAndLoadDevices()
            if (bluetoothAdapter?.isEnabled == false) {
                _bleUiState.update { it.copy(status = "상태: 블루투스 비활성화됨") }
            }
            updateNotificationCombined()
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkBlePermissionsAndLoadDevices() {
        if (!hasRequiredBlePermissions()) {
            Log.w(TAG_BLE, "Required BLE permissions missing.")
            _bleUiState.update { it.copy(status = "상태: 필수 권한 없음", connectError = "블루투스 연결/검색 권한이 필요합니다.") }
            return
        }
        Log.d(TAG_BLE, "BLE permissions granted.")
        if (bluetoothAdapter?.isEnabled == true) {
            loadBondedDevices()
        } else {
            Log.w(TAG_BLE, "Bluetooth is disabled.")
            _bleUiState.update { currentState ->
                if (currentState.connectedDeviceName == null && !currentState.isConnecting) {
                    currentState.copy(status = "상태: 블루투스 비활성화됨")
                } else currentState
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadBondedDevices() {
        if (!hasRequiredBlePermissions() || bluetoothAdapter?.isEnabled != true) {
            return
        }
        Log.d(TAG_BLE, "Loading bonded devices...")
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        val deviceList = pairedDevices?.toList() ?: emptyList()
        _bondedDevices.value = deviceList
        Log.d(TAG_BLE, "Found ${deviceList.size} bonded devices.")
        _bleUiState.update { currentState ->
            if (currentState.connectedDeviceName == null && !currentState.isConnecting) {
                if (deviceList.isEmpty()) {
                    currentState.copy(status = "상태: 페어링된 기기 없음", connectError = null)
                } else {
                    currentState.copy(status = "상태: 기기 목록 로드됨", connectError = null)
                }
            } else currentState
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasRequiredBlePermissions()) {
            _bleUiState.update { it.copy(status = "상태: 연결 권한 없음", connectError = "연결 전 권한 승인이 필요합니다.") }
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            _bleUiState.update { it.copy(status = "상태: 블루투스 비활성화됨", connectError = "연결 전 블루투스 활성화가 필요합니다.") }
            return
        }
        if (_bleUiState.value.isConnecting || _bleUiState.value.connectedDeviceName != null) {
            Log.w(TAG_BLE, "Connect cancelled: Already connecting or connected.")
            return
        }
        val deviceName = try { device.name ?: "알 수 없는 기기" } catch (e: SecurityException) { "알 수 없는 이름" }
        Log.i(TAG_BLE, "Service starting connection to ${device.address} ($deviceName)")
        _bleUiState.update { it.copy(status = "상태: ${deviceName}에 연결 중...", isConnecting = true, connectError = null) }
        updateNotificationCombined()
        disconnectBle()
        connectThread = ConnectThread(device)
        connectThread?.start()
    }

    private fun disconnectBle() {
        if (connectThread == null && connectedThread == null && !_bleUiState.value.isConnecting && _bleUiState.value.connectedDeviceName == null) return
        Log.i(TAG_BLE, "Disconnecting BLE connection...")
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        if (_bleUiState.value.connectedDeviceName != null || _bleUiState.value.isConnecting) {
            _bleUiState.update {
                it.copy(
                    status = "상태: 연결 해제됨",
                    connectedDeviceName = null,
                    isConnecting = false,
                    connectError = null
                )
            }
            updateNotificationCombined()
        }
    }

    private fun sendValueInternal(value: Int) {
        if (connectedThread == null) {
            Log.w(TAG_BLE, "Cannot send BLE data, not connected.")
            _bleUiState.update { it.copy(connectError = "BLE 메시지 전송 실패: 연결 안됨") }
            return
        }
        val message = value.toString()
        connectedThread?.write(message.toByteArray())
    }

    @SuppressLint("MissingPermission")
    private fun manageConnectedSocket(socket: BluetoothSocket) {
        val deviceName = try { socket.remoteDevice.name ?: "알 수 없는 기기" } catch (e: SecurityException) { "알 수 없는 기기" }
        Log.i(TAG_BLE, "BLE Connection successful with $deviceName.")
        lifecycleScope.launch {
            _bleUiState.update {
                it.copy(
                    status = "상태: ${deviceName}에 연결됨",
                    connectedDeviceName = deviceName,
                    isConnecting = false,
                    connectError = null
                )
            }
            updateNotificationCombined()
        }
        connectedThread?.cancel()
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
    }

    private fun connectionFailed(errorMsg: String = "기기 연결에 실패했습니다.") {
        Log.e(TAG_BLE, "BLE Connection failed: $errorMsg")
        lifecycleScope.launch {
            _bleUiState.update {
                it.copy(
                    status = "상태: 연결 실패",
                    connectedDeviceName = null,
                    isConnecting = false,
                    connectError = errorMsg
                )
            }
            updateNotificationCombined()
        }
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
    }

    private fun connectionLost() {
        Log.w(TAG_BLE, "BLE Connection lost.")
        if (_bleUiState.value.connectedDeviceName != null || _bleUiState.value.status.contains("연결됨")) {
            lifecycleScope.launch {
                _bleUiState.update {
                    it.copy(
                        status = "상태: 연결 끊김",
                        connectedDeviceName = null,
                        isConnecting = false,
                        connectError = "기기와의 연결이 끊어졌습니다."
                    )
                }
                updateNotificationCombined()
            }
        }
        connectedThread = null
    }

    private fun processBleMessage(message: String) {
        updateDataLog("<- $message (BLE)")
        lifecycleScope.launch {
            Log.d(TAG_BLE, "Forwarding message from BLE to Hub for TCP: $message")
            CommunicationHub.emitBleToTcp(message)
        }
        val trimmedMessage = message.trim()
        var eventDescription: String? = null
        when (trimmedMessage.lowercase()) {
            "siren" -> eventDescription = "사이렌 감지됨 (BLE)"
            "horn" -> eventDescription = "경적 감지됨 (BLE)"
            "boom" -> eventDescription = "폭발음 감지됨 (BLE)"
        }
        if (eventDescription != null) { addDetectionEvent(eventDescription) }
    }

    private fun updateDataLog(logEntry: String) {
        lifecycleScope.launch {
            val currentLog = _bleUiState.value.receivedDataLog
            val newLog = "$currentLog\n$logEntry".takeLast(1000)
            _bleUiState.update { it.copy(receivedDataLog = newLog) }
        }
    }

    private fun addDetectionEvent(description: String) {
        lifecycleScope.launch {
            val currentTime = timeFormatter.format(Date())
            val newEvent = DetectionEvent(description = description, timestamp = currentTime)
            val currentList = _detectionEventLog.value
            val updatedList = (listOf(newEvent) + currentList).take(MAX_DETECTION_LOG_SIZE)
            _detectionEventLog.value = updatedList
            Log.i(TAG_BLE, "Detection Event Added: $description")
        }
    }

    private fun hasRequiredBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun connectTcp() {
        if (tcpSocket?.isConnected == true || _tcpUiState.value.connectionStatus.contains("연결 중")) {
            Log.w(TAG_TCP, "TCP Connect ignored: Already connected or connecting.")
            return
        }
        Log.i(TAG_TCP, "Attempting to connect TCP to $currentServerIp:$currentServerPort...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                _tcpUiState.update { it.copy(connectionStatus = "TCP/IP: 연결 중...", errorMessage = null) }
                updateNotificationCombined()

                tcpSocket = Socket()
                tcpSocket?.connect(InetSocketAddress(currentServerIp, currentServerPort), 5000)

                if (tcpSocket?.isConnected == true) {
                    tcpPrintWriter = PrintWriter(tcpSocket!!.getOutputStream(), true)
                    tcpBufferedReader = BufferedReader(InputStreamReader(tcpSocket!!.getInputStream()))
                    _tcpUiState.update { it.copy(isConnected = true, connectionStatus = "TCP/IP: 연결됨", errorMessage = null) }
                    Log.i(TAG_TCP, "TCP Connected to $currentServerIp:$currentServerPort")
                    updateNotificationCombined()
                    startTcpReceiveLoop()
                } else {
                    throw IOException("Socket connect failed post-attempt")
                }
            } catch (e: Exception) {
                Log.e(TAG_TCP, "TCP Connection Error to $currentServerIp:$currentServerPort", e)
                _tcpUiState.update { it.copy(isConnected = false, connectionStatus = "TCP/IP: 연결 실패", errorMessage = "연결 오류: ${e.message}") }
                updateNotificationCombined()
                closeTcpSocketResources()
            }
        }
    }

    private fun startTcpReceiveLoop() {
        tcpReceiveJob?.cancel()
        tcpReceiveJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG_TCP, "Starting TCP receive loop...")
            try {
                while (isActive && tcpSocket?.isConnected == true && tcpBufferedReader != null) {
                    val line = tcpBufferedReader?.readLine()
                    if (line != null) {
                        Log.i(TAG_TCP, "TCP Received: $line")
                        val newItem = TcpMessageItem(source = "$currentServerIp:$currentServerPort", payload = line)
                        _receivedTcpMessages.update { list -> (listOf(newItem) + list).take(MAX_TCP_LOG_SIZE) }

                        Log.d(TAG_TCP, "Forwarding TCP message to Hub for BLE: $line")
                        CommunicationHub.emitTcpToBle(line)
                    } else {
                        Log.w(TAG_TCP, "TCP readLine returned null, server might have closed connection.")
                        break
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG_TCP, "TCP Receive Loop IOException (Connection likely lost)", e)
            } catch (e: Exception) {
                Log.e(TAG_TCP, "TCP Receive Loop Exception", e)
            } finally {
                Log.d(TAG_TCP, "TCP receive loop ended.")
                if (isActive) {
                    disconnectTcpInternal(userRequested = false, "수신 중 연결 끊김")
                }
            }
        }
    }

    private fun sendTcpData(message: String) {
        if (tcpSocket?.isConnected != true || tcpPrintWriter == null) {
            Log.w(TAG_TCP, "Cannot send TCP data: Not connected.")
            _tcpUiState.update { it.copy(errorMessage = "TCP 메시지 전송 실패: 연결 안됨") }
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                tcpPrintWriter?.println(message)
                Log.i(TAG_TCP, "TCP Sent: $message")
                val sentItem = TcpMessageItem(source = "클라이언트 -> 서버", payload = message)
                _receivedTcpMessages.update { list -> (listOf(sentItem) + list).take(MAX_TCP_LOG_SIZE) }
            } catch (e: Exception) {
                Log.e(TAG_TCP, "TCP Send Error", e)
                _tcpUiState.update { it.copy(errorMessage = "TCP 메시지 전송 오류: ${e.message}") }
                disconnectTcpInternal(userRequested = false, "전송 중 연결 끊김")
            }
        }
    }

    private fun disconnectTcpInternal(userRequested: Boolean, reason: String? = null) {
        if (tcpSocket == null && !_tcpUiState.value.isConnected && !_tcpUiState.value.connectionStatus.contains("연결 중")) {
            Log.d(TAG_TCP, "TCP Disconnect ignored: Already disconnected or not initialized.")
            return
        }
        Log.i(TAG_TCP, "Disconnecting TCP (User requested: $userRequested, Reason: $reason)...")
        tcpReceiveJob?.cancel()
        tcpReceiveJob = null
        closeTcpSocketResources()

        val statusMessage = reason ?: if (userRequested) "연결 해제됨" else "연결 끊김"
        _tcpUiState.update { it.copy(isConnected = false, connectionStatus = "TCP/IP: $statusMessage", errorMessage = if (reason != null && !userRequested) reason else null) }
        updateNotificationCombined()
    }

    private fun closeTcpSocketResources() {
        try { tcpPrintWriter?.close() } catch (e: IOException) { Log.e(TAG_TCP, "Error closing PrintWriter", e) }
        try { tcpBufferedReader?.close() } catch (e: IOException) { Log.e(TAG_TCP, "Error closing BufferedReader", e) }
        try { tcpSocket?.close() } catch (e: IOException) { Log.e(TAG_TCP, "Error closing socket", e) }
        tcpSocket = null
        tcpPrintWriter = null
        tcpBufferedReader = null
        Log.d(TAG_TCP, "TCP socket resources closed.")
    }

    private fun listenForBleToTcpMessages() { // BLE -> TCP
        lifecycleScope.launch {
            CommunicationHub.bleToTcpFlow.collect { message ->
                Log.i(TAG_TCP, "Service received message from Hub (BLE->TCP): $message")
                if (_tcpUiState.value.isConnected) {
                    Log.d(TAG_TCP, "Service sending BLE message via TCP")
                    sendTcpData(message)
                } else {
                    Log.w(TAG_TCP, "Service cannot send BLE message via TCP: Not connected.")
                }
            }
        }
    }

    private fun listenForTcpToBleMessages() { // TCP -> BLE
        lifecycleScope.launch {
            CommunicationHub.tcpToBleFlow.collect { message ->
                Log.i(TAG_BLE, "Service received message from Hub (TCP->BLE): $message")
                val trimmedMessage = message.trim()
                var eventDescription: String? = null
                when (trimmedMessage.lowercase()) {
                    "siren" -> eventDescription = "사이렌 감지됨 (TCP)"
                    "horn" -> eventDescription = "경적 감지됨 (TCP)"
                    "boom" -> eventDescription = "폭발음 감지됨 (TCP)"
                }
                if (eventDescription != null) { addDetectionEvent(eventDescription) }

                if (connectedThread != null && _bleUiState.value.connectedDeviceName != null) {
                    Log.d(TAG_BLE, "Service forwarding TCP message to BLE device.")
                    connectedThread?.write(message.toByteArray())
                } else {
                    Log.w(TAG_BLE, "Service cannot forward TCP message to BLE: Not connected.")
                }
            }
        }
    }

    private fun updateNotificationCombined() {
        val bleStatus = _bleUiState.value.connectedDeviceName ?: "끊김"
        val tcpStatusText = _tcpUiState.value.connectionStatus
        val contentText = "BLE: $bleStatus, $tcpStatusText"
        updateNotification(contentText)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "MQBL 통신 서비스"
            val descriptionText = "백그라운드 BLE 및 TCP/IP 연결 상태 알림" // 설명 변경
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG_SERVICE, "Notification channel created.")
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // --- BLE 통신 스레드 (Inner Class) ---
    @SuppressLint("MissingPermission")
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            try { device.createRfcommSocketToServiceRecord(BT_UUID) }
            catch (e: IOException) { Log.e(TAG_BLE, "ConnectThread: Socket create failed", e); lifecycleScope.launch { connectionFailed("소켓 생성 실패: ${e.message}") }; null }
            catch (e: SecurityException) { Log.e(TAG_BLE, "ConnectThread: Socket create security error", e); lifecycleScope.launch { connectionFailed("소켓 생성 권한 오류: ${e.message}") }; null }
        }
        override fun run() {
            if (mmSocket == null) { connectThread = null; return }
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: SecurityException){
                Log.e(TAG_BLE, "cancelDiscovery failed", e)
            }
            mmSocket?.let { socket ->
                try {
                    Log.i(TAG_BLE, "ConnectThread: Connecting...")
                    socket.connect()
                    Log.i(TAG_BLE, "ConnectThread: Connected.")
                    manageConnectedSocket(socket)
                } catch (e: Exception) {
                    Log.e(TAG_BLE, "ConnectThread: Connection failed", e)
                    lifecycleScope.launch { connectionFailed("연결 실패: ${e.message}") }
                    try { socket.close() } catch (ce: IOException) { Log.e(TAG_BLE, "ConnectThread: Socket close failed", ce) }
                }
            }
            connectThread = null
        }
        fun cancel() { try { mmSocket?.close() } catch (e: IOException) { Log.e(TAG_BLE, "ConnectThread: cancel failed", e) } }
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream? = try { mmSocket.inputStream } catch (e: IOException) { Log.e(TAG_BLE, "ConnectedThread: Error getting input stream", e); lifecycleScope.launch { connectionLost() }; null }
        private val mmOutStream: OutputStream? = try { mmSocket.outputStream } catch (e: IOException) { Log.e(TAG_BLE, "ConnectedThread: Error getting output stream", e); lifecycleScope.launch { connectionLost() }; null }
        private var isRunning = true

        override fun run() {
            Log.i(TAG_BLE, "ConnectedThread: Listening...")
            val buffer = ByteArray(1024)
            val messageBuilder = StringBuilder()
            while (isRunning) {
                if (mmInStream == null) { lifecycleScope.launch { connectionLost() }; break }
                try {
                    val numBytes = mmInStream.read(buffer)
                    val readChunk = String(buffer, 0, numBytes)
                    messageBuilder.append(readChunk)
                    var newlineIndex = messageBuilder.indexOf('\n')
                    while (newlineIndex >= 0) {
                        val line = messageBuilder.substring(0, newlineIndex)
                        val finalMessage = if (line.endsWith('\r')) line.dropLast(1) else line
                        Log.d(TAG_BLE, "Received line: '$finalMessage'")
                        processBleMessage(finalMessage)
                        messageBuilder.delete(0, newlineIndex + 1)
                        newlineIndex = messageBuilder.indexOf('\n')
                    }
                } catch (e: IOException) {
                    Log.w(TAG_BLE, "ConnectedThread: Read failed, disconnecting.", e)
                    lifecycleScope.launch { connectionLost() }
                    isRunning = false
                }
            }
            Log.i(TAG_BLE, "ConnectedThread: Finished.")
        }

        fun write(bytes: ByteArray) {
            if (mmOutStream == null) { lifecycleScope.launch { connectionLost() }; return }
            try {
                mmOutStream.write(bytes)
                Log.d(TAG_BLE, "Data sent: ${String(bytes)}")
                updateDataLog("-> ${String(bytes)} (BLE)")
            } catch (e: IOException) {
                Log.e(TAG_BLE, "ConnectedThread: Write Error", e)
                lifecycleScope.launch { connectionLost() }
            }
        }
        fun cancel() {
            isRunning = false
            try { mmSocket.close() } catch (e: IOException) { Log.e(TAG_BLE, "ConnectedThread: Socket close error", e) }
        }
    }
    // --- BLE 통신 스레드 끝 ---
}
