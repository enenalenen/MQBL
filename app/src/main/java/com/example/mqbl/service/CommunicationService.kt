package com.example.mqbl.service

// --- Android 및 BLE 관련 import ---
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
import androidx.lifecycle.LifecycleService // LifecycleService는 한 번만 import
import androidx.lifecycle.lifecycleScope
import com.example.mqbl.MainActivity
import com.example.mqbl.R
import com.example.mqbl.common.CommunicationHub
import com.example.mqbl.ui.ble.BleUiState
import com.example.mqbl.ui.ble.DetectionEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
// --- MQTT 관련 import ---
import com.example.mqbl.ui.mqtt.MqttMessageItem // MqttMessageItem은 한 번만 import
import com.example.mqbl.ui.mqtt.MqttUiState // MqttUiState는 한 번만 import
import kotlinx.coroutines.flow.StateFlow
import info.mqtt.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import javax.net.ssl.SSLSocketFactory

// --- 상수 정의 ---
private const val TAG_SERVICE = "CommService"
private const val TAG_BLE = "CommService_BLE"
private const val TAG_MQTT = "CommService_MQTT"
private val BT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private const val MAX_DETECTION_LOG_SIZE = 10
private const val MQTT_SERVER_URI = "ssl://980ce8dfb90a4c1f923f97df872e7302.s1.eu.hivemq.cloud:8883"
private const val MQTT_USERNAME = "poiu0987"
private const val MQTT_PASSWORD = "Qwer1234"
private const val MQTT_CLIENT_ID_PREFIX = "mqbl_service_"
private const val MQTT_SUBSCRIBE_TOPIC = "test/mqbl/status"
private const val MQTT_PUBLISH_TOPIC = "test/mqbl/command"
private const val MAX_MQTT_LOG_SIZE = 50
private const val NOTIFICATION_CHANNEL_ID = "MQBL_Communication_Channel"
private const val NOTIFICATION_ID = 1

class CommunicationService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): CommunicationService = this@CommunicationService
        fun getBleUiStateFlow(): StateFlow<BleUiState> = _bleUiState.asStateFlow()
        fun getBondedDevicesFlow(): StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()
        fun getDetectionLogFlow(): StateFlow<List<DetectionEvent>> = _detectionEventLog.asStateFlow()
        fun getMqttUiStateFlow(): StateFlow<MqttUiState> = _mqttUiState.asStateFlow()
        fun getReceivedMqttMessagesFlow(): StateFlow<List<MqttMessageItem>> = _receivedMqttMessages.asStateFlow()
    }
    private val binder = LocalBinder()

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var connectThread: ConnectThread? = null // BLE 연결 스레드
    private var connectedThread: ConnectedThread? = null // BLE 통신 스레드
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _bleUiState = MutableStateFlow(BleUiState())
    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    private val _detectionEventLog = MutableStateFlow<List<DetectionEvent>>(emptyList())

    private lateinit var mqttClient: MqttAndroidClient
    private var mqttClientId: String = ""

    private val _mqttUiState = MutableStateFlow(MqttUiState())
    private val _receivedMqttMessages = MutableStateFlow<List<MqttMessageItem>>(emptyList())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG_SERVICE, "Service onCreate")
        createNotificationChannel()
        initializeBle()
        initializeMqtt()
        listenForMqttMessages()
        listenForBleMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG_SERVICE, "Service onStartCommand Received")
        startForeground(NOTIFICATION_ID, createNotification("서비스 실행 중..."))
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent) // LifecycleService 사용 시 호출 권장
        Log.i(TAG_SERVICE, "Service onBind")
        return binder // binder 객체 반환
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG_SERVICE, "Service onUnbind")
        return true
    }

    override fun onDestroy() {
        Log.w(TAG_SERVICE, "Service onDestroy")
        disconnectBle()
        disconnectMqttInternal(userRequested = false)
        stopForeground(STOP_FOREGROUND_REMOVE) // API 레벨에 따라 stopForeground(true) 또는 removeNotification()
        super.onDestroy()
    }

    // --- 공개 함수 (Binder를 통해 ViewModel에서 호출) ---
    fun requestBleConnect(device: BluetoothDevice) { connectToDevice(device) }
    fun requestBleDisconnect() { disconnectBle() }
    fun sendBleValue(value: Int) { sendValueInternal(value) }
    fun refreshBleState() {
        checkBlePermissionsAndLoadDevices()
        if (bluetoothAdapter?.isEnabled == false && _bleUiState.value.connectedDeviceName == null) {
            _bleUiState.update { it.copy(status = "상태: 블루투스 비활성화됨") }
        }
    }
    fun requestMqttConnect() { connectMqtt() }
    fun requestMqttDisconnect() { disconnectMqttInternal(userRequested = true) }
    fun requestMqttSubscribe(topic: String) { subscribeMqtt(topic) }
    fun requestMqttPublish(payload: String) { publishMqtt(MQTT_PUBLISH_TOPIC, payload) }

    // --- 내부 초기화 함수 ---
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

    private fun initializeMqtt() {
        Log.d(TAG_MQTT, "Initializing MQTT Client...")
        mqttClientId = MQTT_CLIENT_ID_PREFIX + System.currentTimeMillis()
        try {
            mqttClient = MqttAndroidClient(applicationContext, MQTT_SERVER_URI, mqttClientId)
            mqttClient.setCallback(MqttCallbackHandler())
            Log.i(TAG_MQTT, "MQTT Client Initialized. Client ID: $mqttClientId")
        } catch (e: MqttException) {
            Log.e(TAG_MQTT, "Error initializing MQTT Client", e)
            _mqttUiState.update { it.copy(connectionStatus = "상태: MQTT 초기화 실패", errorMessage = "MQTT 클라이언트 생성 오류: ${e.message}") }
            updateNotificationCombined()
        }
    }

    // --- 내부 BLE 로직 함수 ---
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
                if (currentState.connectedDeviceName == null) {
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
            Log.d(TAG_BLE, "Forwarding message from BLE to Hub: $message")
            CommunicationHub.emitBleToMqtt(message)
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

    // --- 내부 MQTT 로직 함수 ---
    private fun connectMqtt() {
        if (!::mqttClient.isInitialized) {
            Log.e(TAG_MQTT, "Connect failed: MQTT Client not initialized.")
            initializeMqtt()
            if (!::mqttClient.isInitialized) return
        }
        if (_mqttUiState.value.isConnected) {
            Log.w(TAG_MQTT, "Connect ignored: Already connected.")
            return
        }
        Log.i(TAG_MQTT, "Attempting to connect MQTT...")
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true; isCleanSession = true
            userName = MQTT_USERNAME; password = MQTT_PASSWORD.toCharArray()
            try { socketFactory = SSLSocketFactory.getDefault() }
            catch (e: Exception) {
                Log.e(TAG_MQTT, "SSL Factory Error", e)
                lifecycleScope.launch { _mqttUiState.update { it.copy(isConnected = false, connectionStatus = "상태: SSL 오류", errorMessage = "SSL 설정 오류: ${e.message}") }; updateNotificationCombined() }
                return
            }
        }
        lifecycleScope.launch { _mqttUiState.update { it.copy(connectionStatus = "상태: MQTT 연결 중...", errorMessage = null) }; updateNotificationCombined() }
        try {
            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) { Log.d(TAG_MQTT, "MQTT Connect Action Success") }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    val errorMsg = exception?.message ?: "Unknown"
                    Log.e(TAG_MQTT, "MQTT Connect Action Failed: $errorMsg")
                    lifecycleScope.launch { _mqttUiState.update { it.copy(isConnected = false, connectionStatus = "상태: MQTT 연결 실패", errorMessage = "연결 실패: $errorMsg") }; updateNotificationCombined() }
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG_MQTT, "MQTT Connect Exception", e)
            lifecycleScope.launch { _mqttUiState.update { it.copy(isConnected = false, connectionStatus = "상태: MQTT 연결 예외", errorMessage = "연결 예외: ${e.message}") }; updateNotificationCombined() }
        }
    }

    private fun disconnectMqttInternal(userRequested: Boolean = false) {
        if (!::mqttClient.isInitialized || !_mqttUiState.value.isConnected) {
            Log.w(TAG_MQTT,"Disconnect ignored: Not connected/initialized.")
            if (_mqttUiState.value.isConnected) {
                lifecycleScope.launch { _mqttUiState.update { it.copy(isConnected = false, connectionStatus = "상태: 연결 끊김", errorMessage = null) }; updateNotificationCombined() }
            }
            return
        }
        Log.i(TAG_MQTT, "Disconnecting MQTT (User requested: $userRequested)...")
        if (userRequested) {
            lifecycleScope.launch { _mqttUiState.update { it.copy(connectionStatus = "상태: MQTT 연결 해제 중...") } }
        }
        try {
            if (!userRequested && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED) {
                mqttClient.disconnectForcibly()
                Log.i(TAG_MQTT, "MQTT disconnected forcibly during service shutdown.")
            } else {
                mqttClient.disconnect(null, object : IMqttActionListener {
                    override fun onSuccess(token: IMqttToken?) { Log.d(TAG_MQTT, "MQTT Disconnect Action Success") }
                    override fun onFailure(token: IMqttToken?, ex: Throwable?) { Log.e(TAG_MQTT, "MQTT Disconnect Action Failed", ex) }
                })
            }
        } catch (e: MqttException) {
            Log.e(TAG_MQTT, "MQTT Disconnect Exception", e)
            if (_mqttUiState.value.isConnected) {
                lifecycleScope.launch { _mqttUiState.update { it.copy(isConnected = false, connectionStatus = "상태: 연결 끊김 (해제 오류)", errorMessage = e.message) }; updateNotificationCombined() }
            }
        }
    }

    private fun subscribeMqtt(topic: String) {
        if (!::mqttClient.isInitialized || !_mqttUiState.value.isConnected) {
            Log.w(TAG_MQTT, "Cannot subscribe: MQTT client not connected.")
            return
        }
        try {
            mqttClient.subscribe(topic, 1, null, object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken?) { Log.i(TAG_MQTT, "Subscribed to $topic") }
                override fun onFailure(token: IMqttToken?, ex: Throwable?) { Log.e(TAG_MQTT, "Failed to subscribe to $topic", ex) }
            })
        } catch (e: MqttException) { Log.e(TAG_MQTT, "Subscribe exception for $topic", e) }
    }

    private fun publishMqtt(topic: String, payload: String) {
        if (!::mqttClient.isInitialized || !_mqttUiState.value.isConnected) {
            Log.w(TAG_MQTT, "Cannot publish: MQTT client not connected.")
            return
        }
        try {
            val message = MqttMessage(payload.toByteArray()).apply { qos = 1; isRetained = false }
            mqttClient.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken?) { Log.d(TAG_MQTT, "Publish action success to $topic (Payload: $payload)") }
                override fun onFailure(token: IMqttToken?, ex: Throwable?) { Log.e(TAG_MQTT, "Failed to publish to $topic", ex) }
            })
        } catch (e: MqttException) { Log.e(TAG_MQTT, "Publish exception for $topic", e) }
    }

    private inner class MqttCallbackHandler : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            Log.i(TAG_MQTT, "MQTT Connect Complete. Reconnect=$reconnect")
            lifecycleScope.launch {
                _mqttUiState.update { it.copy(isConnected = true, connectionStatus = "상태: MQTT 연결됨", errorMessage = null) }
                updateNotificationCombined()
                subscribeMqtt(MQTT_SUBSCRIBE_TOPIC)
            }
        }
        override fun connectionLost(cause: Throwable?) {
            val errorMsg = cause?.message ?: "Unknown reason"
            Log.e(TAG_MQTT, "MQTT Connection Lost: $errorMsg", cause)
            lifecycleScope.launch {
                _mqttUiState.update { it.copy(isConnected = false, connectionStatus = "상태: MQTT 연결 끊김", errorMessage = "연결 끊김: $errorMsg") }
                updateNotificationCombined()
            }
        }
        override fun messageArrived(topic: String?, message: MqttMessage?) {
            if (topic == null || message == null) return
            val msgPayload = message.toString()
            Log.d(TAG_MQTT, "MQTT Message Arrived: Topic=$topic, Payload=$msgPayload")
            val newItem = MqttMessageItem(topic = topic, payload = msgPayload)
            lifecycleScope.launch {
                _receivedMqttMessages.update { list -> (listOf(newItem) + list).take(MAX_MQTT_LOG_SIZE) }
            }
            if (topic == MQTT_SUBSCRIBE_TOPIC) {
                lifecycleScope.launch {
                    Log.d(TAG_MQTT, "Forwarding MQTT message to Hub for BLE: $msgPayload")
                    CommunicationHub.emitMqttToBle(msgPayload)
                }
            }
        }
        override fun deliveryComplete(token: IMqttDeliveryToken?) { /* Log delivery */ }
    }

    private fun listenForBleMessages() {
        lifecycleScope.launch {
            CommunicationHub.bleToMqttFlow.collect { message ->
                Log.i(TAG_MQTT, "Service received message from Hub (BLE->MQTT): $message")
                if (_mqttUiState.value.isConnected) {
                    Log.d(TAG_MQTT, "Service publishing BLE message to MQTT topic $MQTT_PUBLISH_TOPIC")
                    publishMqtt(MQTT_PUBLISH_TOPIC, message)
                } else {
                    Log.w(TAG_MQTT, "Service cannot publish BLE message: MQTT not connected.")
                }
            }
        }
    }

    private fun listenForMqttMessages() {
        lifecycleScope.launch {
            CommunicationHub.mqttToBleFlow.collect { message ->
                Log.i(TAG_BLE, "Service received message from Hub (MQTT->BLE): $message")
                val trimmedMessage = message.trim()
                var eventDescription: String? = null
                when (trimmedMessage.lowercase()) {
                    "siren" -> eventDescription = "사이렌 감지됨 (MQTT)"
                    "horn" -> eventDescription = "경적 감지됨 (MQTT)"
                    "boom" -> eventDescription = "폭발음 감지됨 (MQTT)"
                }
                if (eventDescription != null) { addDetectionEvent(eventDescription) }
                if (connectedThread != null && _bleUiState.value.connectedDeviceName != null) {
                    Log.d(TAG_BLE, "Service forwarding MQTT message to BLE device.")
                    connectedThread?.write(message.toByteArray()) // write 함수 참조 오류 가능성
                } else {
                    Log.w(TAG_BLE, "Service cannot forward MQTT message to BLE: Not connected.")
                }
            }
        }
    }

    private fun updateNotificationCombined() {
        val bleStatus = _bleUiState.value.connectedDeviceName ?: "끊김"
        val mqttStatus = if (_mqttUiState.value.isConnected) "연결됨" else "끊김"
        val contentText = "BLE: $bleStatus, MQTT: $mqttStatus"
        updateNotification(contentText)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "MQBL 통신 서비스"
            val descriptionText = "백그라운드 BLE 및 MQTT 연결 상태 알림"
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
                bluetoothAdapter?.cancelDiscovery() // Service 멤버 변수 접근
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

} // CommunicationServic