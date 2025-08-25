package com.example.mqbl.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.mqbl.MainActivity
import com.example.mqbl.R
import com.example.mqbl.common.CommunicationHub
import com.example.mqbl.data.SettingsRepository
import com.example.mqbl.ui.ble.BleUiState
import com.example.mqbl.ui.ble.DetectionEvent
import com.example.mqbl.ui.tcp.TcpMessageItem
import com.example.mqbl.ui.tcp.TcpUiState
import com.example.mqbl.ui.wifidirect.WifiDirectPeerItem
import com.example.mqbl.ui.wifidirect.WifiDirectUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.example.mqbl.ui.ble.CustomSoundEvent
import kotlinx.coroutines.flow.first

// --- 상수 정의 ---
private const val TAG_SERVICE = "CommService"
private const val TAG_BLE = "CommService_BLE"
private const val TAG_TCP = "CommService_TCP"
private const val TAG_WIFI_DIRECT = "CommService_WD"
private val BT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SerialPortService ID
private const val MAX_DETECTION_LOG_SIZE = 10
private const val DEFAULT_TCP_SERVER_IP = "192.168.0.18" // 기본 TCP 서버 IP
private const val DEFAULT_TCP_SERVER_PORT = 12345       // 기본 TCP 서버 포트
private const val MAX_TCP_LOG_SIZE = 50
private const val WIFI_DIRECT_SERVER_PORT = 8888       // Wi-Fi Direct 소켓 통신 포트
private const val MAX_WIFI_DIRECT_LOG_SIZE = 20
private const val ALERT_NOTIFICATION_CHANNEL_ID = "MQBL_Alert_Channel" // 긴급 알림용 채널 ID
private const val ALERT_NOTIFICATION_ID = 2 // 긴급 알림용 ID (기존 알림 ID와 달라야 함)
private const val NOTIFICATION_CHANNEL_ID = "MQBL_Communication_Channel"
private const val NOTIFICATION_ID = 1
private const val SOCKET_TIMEOUT = 5000 // 소켓 연결 타임아웃 (ms)
// -----------------

class CommunicationService : LifecycleService() {

    companion object {
        const val ACTION_START_FOREGROUND = "com.example.mqbl.action.START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.example.mqbl.action.STOP_FOREGROUND"
    }

    inner class LocalBinder : Binder() {
        fun getService(): CommunicationService = this@CommunicationService
        fun getCustomSoundEventLogFlow(): StateFlow<List<CustomSoundEvent>> = _customSoundEventLog.asStateFlow()
        // BLE
        fun getBleUiStateFlow(): StateFlow<BleUiState> = _bleUiState.asStateFlow()
        fun getBondedDevicesFlow(): StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()
        fun getDetectionLogFlow(): StateFlow<List<DetectionEvent>> = _detectionEventLog.asStateFlow()
        fun getScannedDevicesFlow(): StateFlow<List<BluetoothDevice>> = _scannedDevices.asStateFlow()
        // TCP
        fun getTcpUiStateFlow(): StateFlow<TcpUiState> = _tcpUiState.asStateFlow()
        fun getReceivedTcpMessagesFlow(): StateFlow<List<TcpMessageItem>> = _receivedTcpMessages.asStateFlow()
        // Wi-Fi Direct
        fun getWifiDirectUiStateFlow(): StateFlow<WifiDirectUiState> = _wifiDirectUiState.asStateFlow()
    }
    private val binder = LocalBinder()

    private lateinit var settingsRepository: SettingsRepository

    private val _customSoundEventLog = MutableStateFlow<List<CustomSoundEvent>>(emptyList())
    private var customKeywords = listOf<String>()

    // BLE
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private val _bleUiState = MutableStateFlow(BleUiState())
    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    private val _detectionEventLog = MutableStateFlow<List<DetectionEvent>>(emptyList())

    // BLE 스캔 관련
    private var bleScanner: BluetoothLeScanner? = null
    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    private var isScanning = false

    // 페어링(Bonding) 상태 변화 감지 리시버
    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)

                val deviceName = device?.name ?: "알 수 없는 기기"
                Log.d(TAG_BLE, "Bond state changed for $deviceName: $previousBondState -> $bondState")

                when (bondState) {
                    BluetoothDevice.BOND_BONDING -> {
                        _bleUiState.update { it.copy(status = "상태: ${deviceName}과 페어링 중...") }
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        _bleUiState.update { it.copy(status = "상태: ${deviceName}과 페어링 성공") }
                        loadBondedDevices()
                        _scannedDevices.update { list -> list.filter { it.address != device?.address } }

                        device?.let {
                            lifecycleScope.launch {
                                Log.i(TAG_BLE, "Pairing successful. Waiting for 1 second before connecting...")
                                delay(1000)
                                Log.i(TAG_BLE, "Automatically attempting to connect to ${it.name}")
                                connectToDevice(it)
                            }
                        }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        if (previousBondState == BluetoothDevice.BOND_BONDING) {
                            _bleUiState.update { it.copy(status = "상태: 페어링 실패", connectError = "페어링에 실패했습니다. 다시 시도해주세요.") }
                        } else {
                            _bleUiState.update { it.copy(status = "상태: 페어링 해제됨") }
                        }
                    }
                }
            }
        }
    }

    // 스캔 결과 콜백
    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.device?.let { device ->
                if (device.name != null && _scannedDevices.value.none { it.address == device.address }) {
                    _scannedDevices.value += device
                }
            }
        }
    }

    // TCP/IP
    private var tcpSocket: Socket? = null
    private var tcpPrintWriter: PrintWriter? = null
    private var tcpBufferedReader: BufferedReader? = null
    private var tcpReceiveJob: Job? = null
    private var currentServerIp: String = DEFAULT_TCP_SERVER_IP
    private var currentServerPort: Int = DEFAULT_TCP_SERVER_PORT
    private val _tcpUiState = MutableStateFlow(TcpUiState(connectionStatus = "TCP/IP: 연결 끊김"))
    private val _receivedTcpMessages = MutableStateFlow<List<TcpMessageItem>>(emptyList())

    // Wi-Fi Direct
    private lateinit var wifiP2pManager: WifiP2pManager
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private val wifiDirectIntentFilter = IntentFilter()
    private var wifiDirectBroadcastReceiver: WiFiDirectBroadcastReceiver? = null
    private val _wifiDirectUiState = MutableStateFlow(WifiDirectUiState())
    private var wifiDirectDataTransferThread: WifiDirectDataTransferThread? = null


    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG_SERVICE, "Service onCreate")
        settingsRepository = SettingsRepository(this)

        lifecycleScope.launch {
            // 초기 값 로드
            customKeywords = settingsRepository.customKeywordsFlow.first()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            Log.d(TAG_SERVICE, "Initial custom keywords loaded: $customKeywords")

            // 변경 사항 구독
            settingsRepository.customKeywordsFlow.collect { keywordsString ->
                customKeywords = keywordsString.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                Log.d(TAG_SERVICE, "Custom keywords updated: $customKeywords")
            }
        }

        createNotificationChannel()
        initializeBle()
        //initializeWifiDirect()
        listenForTcpToBleMessages()
        listenForBleToTcpMessages()
        listenForWifiDirectToTcpMessages()
        listenForTcpToWifiDirectMessages()


        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(bondStateReceiver, filter)
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
                Log.i(TAG_SERVICE, "Stopping foreground service and self...")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                if (settingsRepository.isBackgroundExecutionEnabled()) {
                    startForeground(NOTIFICATION_ID, createNotification("서비스 실행 중..."))
                    Log.i(TAG_SERVICE, "Service started, background execution is ENABLED.")
                } else {
                    Log.i(TAG_SERVICE, "Service started, background execution is DISABLED.")
                }
            }
        }

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
        //unregisterWifiDirectReceiver()
        //disconnectWifiDirect(notifyUi = false)

        unregisterReceiver(bondStateReceiver)
        stopBleScan()

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // --- BLE Public Methods ---
    fun requestBleConnect(device: BluetoothDevice) { connectToDevice(device) }
    fun requestBleDisconnect() { disconnectBle() }
    fun sendBleValue(value: Int) { sendValueInternal(value) }
    fun refreshBleState() {
        checkBlePermissionsAndLoadDevices()
        if (bluetoothAdapter?.isEnabled == false && _bleUiState.value.connectedDeviceName == null) {
            _bleUiState.update { it.copy(status = "상태: 블루투스 비활성화됨") }
        }
    }

    // --- TCP Public Methods ---
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

    // --- Wi-Fi Direct Public Methods ---
    fun refreshWifiDirectState() {
        if (!hasWifiDirectPermissions(checkOnly = true)) {
            _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 권한 필요", errorMessage = "Wi-Fi Direct 기능을 사용하려면 권한이 필요합니다.") }
            return
        }
        wifiP2pChannel?.let { channel ->
            wifiP2pManager.requestConnectionInfo(channel, wifiDirectConnectionInfoListener)
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverWifiDirectPeers() {
        if (!hasWifiDirectPermissions()) {
            return
        }
        if (!_wifiDirectUiState.value.isWifiDirectEnabled) {
            _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 비활성화됨", errorMessage = "Wi-Fi Direct가 꺼져 있습니다.") }
            return
        }
        _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 피어 검색 중...", peers = emptyList(), errorMessage = null) }
        updateNotificationCombined()
        wifiP2pChannel?.let { channel ->
            wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG_WIFI_DIRECT, "Peer discovery initiated successfully.")
                }
                override fun onFailure(reasonCode: Int) {
                    val reason = getWifiP2pFailureReason(reasonCode)
                    Log.e(TAG_WIFI_DIRECT, "Peer discovery initiation failed. Reason: $reason ($reasonCode)")
                    _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 피어 검색 시작 실패", errorMessage = "피어 검색 실패: $reason") }
                    updateNotificationCombined()
                }
            })
        } ?: Log.e(TAG_WIFI_DIRECT, "discoverPeers: wifiP2pChannel is null")
    }

    @SuppressLint("MissingPermission")
    fun connectToWifiDirectPeer(device: WifiP2pDevice) {
        if (!hasWifiDirectPermissions()) {
            return
        }
        if (_wifiDirectUiState.value.isConnecting || _wifiDirectUiState.value.connectedDeviceName != null) {
            Log.w(TAG_WIFI_DIRECT, "Connect request ignored: Already connecting or connected.")
            _wifiDirectUiState.update { it.copy(errorMessage = "이미 연결 중이거나 연결된 상태입니다.") }
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        _wifiDirectUiState.update { it.copy(isConnecting = true, statusText = "Wi-Fi Direct: ${device.deviceName}에 연결 시도 중...", errorMessage = null) }
        updateNotificationCombined()

        wifiP2pChannel?.let { channel ->
            wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG_WIFI_DIRECT, "Successfully initiated connection to ${device.deviceName}")
                    _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: ${device.deviceName}에 연결 요청됨...") }
                }
                override fun onFailure(reasonCode: Int) {
                    val reason = getWifiP2pFailureReason(reasonCode)
                    Log.e(TAG_WIFI_DIRECT, "Failed to initiate connection to ${device.deviceName}. Reason: $reason ($reasonCode)")
                    _wifiDirectUiState.update { it.copy(isConnecting = false, statusText = "Wi-Fi Direct: 연결 시작 실패", errorMessage = "연결 실패: $reason") }
                    updateNotificationCombined()
                }
            })
        } ?: Log.e(TAG_WIFI_DIRECT, "connectToWifiDirectPeer: wifiP2pChannel is null")
    }

    fun disconnectWifiDirect(notifyUi: Boolean = true) {
        Log.i(TAG_WIFI_DIRECT, "Requesting Wi-Fi Direct disconnection (notifyUi: $notifyUi)")

        wifiP2pChannel?.let { channel ->
            wifiP2pManager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG_WIFI_DIRECT, "cancelConnect success") }
                override fun onFailure(reason: Int) { Log.d(TAG_WIFI_DIRECT, "cancelConnect failed: ${getWifiP2pFailureReason(reason)}") }
            })

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            wifiP2pManager.requestGroupInfo(channel) { group ->
                if (group != null) {
                    wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG_WIFI_DIRECT, "Wi-Fi Direct group removed successfully.")
                            if (notifyUi) {
                                _wifiDirectUiState.update {
                                    it.copy(statusText = "Wi-Fi Direct: 그룹 해제됨", connectedDeviceName = null, connectionInfo = null, isGroupOwner = false, groupOwnerAddress = null, isConnecting = false)
                                }
                                updateNotificationCombined()
                            }
                        }
                        override fun onFailure(reasonCode: Int) {
                            val reason = getWifiP2pFailureReason(reasonCode)
                            Log.e(TAG_WIFI_DIRECT, "Failed to remove Wi-Fi Direct group. Reason: $reason ($reasonCode)")
                            if (notifyUi) {
                                _wifiDirectUiState.update { it.copy(errorMessage = "그룹 해제 실패: $reason", isConnecting = false) }
                            }
                        }
                    })
                } else {
                    Log.d(TAG_WIFI_DIRECT, "No active Wi-Fi Direct group to remove.")
                    if (notifyUi) {
                        _wifiDirectUiState.update {
                            it.copy(
                                connectedDeviceName = null,
                                connectionInfo = null,
                                isConnecting = false,
                                isGroupOwner = false,
                                groupOwnerAddress = null,
                                statusText = "Wi-Fi Direct: 연결 해제됨"
                            )
                        }
                        updateNotificationCombined()
                    }
                }
            }
        } ?: Log.e(TAG_WIFI_DIRECT, "disconnectWifiDirect: wifiP2pChannel is null")
        wifiDirectDataTransferThread?.cancel()
        wifiDirectDataTransferThread = null
    }

    fun sendWifiDirectData(message: String) {
        sendWifiDirectDataInternal(message)
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        if (isScanning || !hasRequiredBlePermissions()) return
        _scannedDevices.value = emptyList()
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        _bleUiState.update { it.copy(isScanning = true, status = "상태: 주변 기기 검색 중...") }
        isScanning = true
        lifecycleScope.launch {
            delay(15000)
            if(isScanning) {
                stopBleScan()
            }
        }
        bleScanner?.startScan(leScanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        if (!isScanning || !hasRequiredBlePermissions()) return
        bleScanner?.stopScan(leScanCallback)
        isScanning = false
        _bleUiState.update { it.copy(isScanning = false, status = "상태: 검색 중지됨") }
    }

    @SuppressLint("MissingPermission")
    fun pairDevice(device: BluetoothDevice) {
        if (!hasRequiredBlePermissions()) {
            _bleUiState.update { it.copy(status = "상태: 권한 없음", connectError = "페어링을 위해 블루투스 권한이 필요합니다.") }
            return
        }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            _bleUiState.update { it.copy(status = "상태: 이미 페어링된 기기입니다.") }
            return
        }

        if (isScanning) {
            stopBleScan()
        }

        Log.d(TAG_BLE, "Attempting to pair with device: ${device.name}")
        _bleUiState.update { it.copy(status = "상태: ${device.name}과 페어링 시도...") }
        device.createBond()
    }

    // --- BLE Private Methods ---
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
            if (currentState.connectedDeviceName == null && !currentState.isConnecting && currentState.connectError == null) {
                if (deviceList.isEmpty()) {
                    currentState.copy(status = "상태: 페어링된 기기 없음")
                } else {
                    if (currentState.status == "상태: 검색 중지됨" || currentState.status == "상태: 블루투스 활성화됨" || currentState.status == "상태: 대기 중") {
                        currentState.copy(status = "상태: 기기 목록 로드됨")
                    } else {
                        currentState
                    }
                }
            } else {
                currentState
            }
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
        updateBleDataLog("<- $message (BLE)")
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
        if (eventDescription != null) {
            addDetectionEvent(eventDescription)
            sendAlertNotification(eventDescription)
        }
    }

    private fun updateBleDataLog(logEntry: String) {
        lifecycleScope.launch {
            val currentLog = _bleUiState.value.receivedDataLog
            val newLog = "$logEntry\n$currentLog".take(1000)
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
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // --- TCP Private Methods ---
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
                tcpSocket?.connect(InetSocketAddress(currentServerIp, currentServerPort), SOCKET_TIMEOUT)

                if (tcpSocket?.isConnected == true) {
                    tcpPrintWriter = PrintWriter(tcpSocket!!.getOutputStream(), true)
                    tcpBufferedReader = BufferedReader(InputStreamReader(tcpSocket!!.getInputStream()))
                    _tcpUiState.update { it.copy(isConnected = true, connectionStatus = "TCP/IP: 연결됨", errorMessage = null) }
                    Log.i(TAG_TCP, "TCP Connected to $currentServerIp:$currentServerPort")
                    updateNotificationCombined()
                    startTcpReceiveLoop()
                } else {
                    throw IOException("Socket connect failed post-attempt without exception")
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
                        Log.d(TAG_TCP, "Forwarding TCP message to Hub for Wi-Fi Direct: $line")
                        CommunicationHub.emitTcpToWifiDirect(line)
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
        _tcpUiState.update {
            it.copy(
                isConnected = false,
                connectionStatus = "TCP/IP: $statusMessage",
                errorMessage = if (reason != null && !userRequested) reason else null
            )
        }
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

    // --- Wi-Fi Direct Private Methods ---
    @SuppressLint("MissingPermission")
    private fun initializeWifiDirect() {
        Log.d(TAG_WIFI_DIRECT, "Initializing Wi-Fi Direct...")
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        if (wifiP2pManager == null) {
            Log.e(TAG_WIFI_DIRECT, "Cannot get WifiP2pManager service.")
            _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 서비스 미지원", errorMessage = "이 기기는 Wi-Fi Direct를 지원하지 않을 수 있습니다.", isWifiDirectEnabled = false) }
            return
        }

        wifiP2pChannel = wifiP2pManager.initialize(this, Looper.getMainLooper(), null)
        wifiP2pChannel?.also { channel ->
            wifiDirectBroadcastReceiver = WiFiDirectBroadcastReceiver(wifiP2pManager, channel, this)
            wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)

            registerReceiver(wifiDirectBroadcastReceiver, wifiDirectIntentFilter)
            Log.d(TAG_WIFI_DIRECT, "Wi-Fi Direct Initialized and Receiver registered.")
        } ?: run {
            Log.e(TAG_WIFI_DIRECT, "Failed to initialize Wi-Fi Direct channel.")
            _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 채널 초기화 실패", errorMessage = "Wi-Fi Direct 채널을 초기화할 수 없습니다.", isWifiDirectEnabled = false) }
        }
        wifiP2pChannel?.let { channel ->
            wifiP2pManager.requestP2pState(channel) { state ->
                _wifiDirectUiState.update { it.copy(isWifiDirectEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) }
            }
        }
        refreshWifiDirectState()
    }

    private fun unregisterWifiDirectReceiver() {
        wifiDirectBroadcastReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG_WIFI_DIRECT, "Wi-Fi Direct BroadcastReceiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.e(TAG_WIFI_DIRECT, "Error unregistering Wi-Fi Direct receiver: ${e.message}")
            }
        }
        wifiDirectBroadcastReceiver = null
    }

    private val wifiDirectPeerListListener = WifiP2pManager.PeerListListener { peersList ->
        val refreshedPeers = peersList.deviceList.map { device ->
            WifiDirectPeerItem(
                deviceAddress = device.deviceAddress,
                deviceName = device.deviceName ?: "알 수 없는 기기",
                status = device.status,
                rawDevice = device
            )
        }
        Log.d(TAG_WIFI_DIRECT, "Peers updated. Found ${refreshedPeers.size} peers.")
        _wifiDirectUiState.update {
            it.copy(
                peers = refreshedPeers,
                statusText = if (it.statusText.contains("피어 검색 중")) {
                    if (refreshedPeers.isEmpty()) "Wi-Fi Direct: 주변 기기 없음" else "Wi-Fi Direct: 검색 완료"
                } else {
                    if (it.connectedDeviceName == null && refreshedPeers.isEmpty() && !it.isConnecting) "Wi-Fi Direct: 주변 기기 없음" else it.statusText
                }
            )
        }
    }

    private val wifiDirectConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        Log.d(TAG_WIFI_DIRECT, "Connection info available: GroupFormed=${info.groupFormed}, IsGO=${info.isGroupOwner}, GOAddress=${info.groupOwnerAddress}")
        if (info.groupFormed) {
            val remoteDeviceAddress = if (info.isGroupOwner) {
                _wifiDirectUiState.value.peers.find { peer ->
                    peer.status == WifiP2pDevice.CONNECTED && peer.deviceAddress != info.groupOwnerAddress?.hostAddress
                }?.deviceName ?: "클라이언트"
            } else {
                _wifiDirectUiState.value.peers.find { it.deviceAddress == info.groupOwnerAddress?.hostAddress }?.deviceName ?: "그룹 소유자"
            }

            _wifiDirectUiState.update {
                it.copy(
                    connectionInfo = info,
                    isConnecting = false,
                    statusText = "Wi-Fi Direct: ${remoteDeviceAddress}에 연결됨",
                    connectedDeviceName = remoteDeviceAddress,
                    isGroupOwner = info.isGroupOwner,
                    groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                )
            }
            updateNotificationCombined()
            wifiDirectDataTransferThread?.cancel()
            wifiDirectDataTransferThread = WifiDirectDataTransferThread(info)
            wifiDirectDataTransferThread?.start()
        } else {
            Log.i(TAG_WIFI_DIRECT, "Wi-Fi Direct group not formed or connection lost.")
            if (_wifiDirectUiState.value.connectedDeviceName != null || _wifiDirectUiState.value.isConnecting) {
                _wifiDirectUiState.update {
                    it.copy(
                        connectedDeviceName = null,
                        connectionInfo = null,
                        isGroupOwner = false,
                        groupOwnerAddress = null,
                        isConnecting = false,
                        statusText = "Wi-Fi Direct: 연결 끊김"
                    )
                }
                updateNotificationCombined()
            }
            wifiDirectDataTransferThread?.cancel()
            wifiDirectDataTransferThread = null
        }
    }

    private fun sendWifiDirectDataInternal(message: String) {
        wifiDirectDataTransferThread?.write(message) ?: run {
            Log.w(TAG_WIFI_DIRECT, "Cannot send Wi-Fi Direct data: DataTransferThread is null.")
            _wifiDirectUiState.update { it.copy(errorMessage = "Wi-Fi Direct 메시지 전송 실패: 통신 채널 준비 안됨") }
        }
    }


    private fun addWifiDirectLog(logEntry: String) {
        lifecycleScope.launch {
            val currentLog = _wifiDirectUiState.value.receivedDataLog
            val updatedLog = (listOf(logEntry) + currentLog).take(MAX_WIFI_DIRECT_LOG_SIZE)
            _wifiDirectUiState.update { it.copy(receivedDataLog = updatedLog) }
        }
    }

    private fun hasWifiDirectPermissions(checkOnly: Boolean = false): Boolean {
        val context: Context = this
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        if (permissionsToRequest.isEmpty()) {
            return true
        } else {
            if (!checkOnly) {
                Log.w(TAG_WIFI_DIRECT, "Required Wi-Fi Direct permissions missing: $permissionsToRequest")
                _wifiDirectUiState.update {
                    it.copy(
                        statusText = "Wi-Fi Direct: 권한 필요",
                        errorMessage = "다음 권한이 필요합니다: ${permissionsToRequest.joinToString()}"
                    )
                }
            }
            return false
        }
    }


    private fun getWifiP2pFailureReason(reasonCode: Int): String {
        return when (reasonCode) {
            WifiP2pManager.ERROR -> "일반 오류"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P 미지원"
            WifiP2pManager.BUSY -> "장치 사용 중"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "활성 요청 없음"
            else -> "알 수 없는 오류 ($reasonCode)"
        }
    }

    // --- Hub Listeners ---
    private fun listenForBleToTcpMessages() {
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

    private fun listenForTcpToBleMessages() {
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

                val detectedCustomKeyword = customKeywords.find { keyword ->
                    trimmedMessage.contains(keyword, ignoreCase = true)
                }
                if (detectedCustomKeyword != null) {
                    addCustomSoundEvent("'$detectedCustomKeyword' 단어 감지됨 (TCP)")
                }

                if (connectedThread != null && _bleUiState.value.connectedDeviceName != null) {
                    Log.d(TAG_BLE, "Service forwarding TCP message to BLE device.")
                    connectedThread?.write(message.toByteArray())
                } else {
                    Log.w(TAG_BLE, "Service cannot forward TCP message to BLE: Not connected.")
                }
            }
        }
    }

    private fun listenForWifiDirectToTcpMessages() {
        lifecycleScope.launch {
            CommunicationHub.wifiDirectToTcpFlow.collect { message ->
                Log.i(TAG_TCP, "Service received message from Hub (Wi-Fi Direct -> TCP): $message")
                if (_tcpUiState.value.isConnected) {
                    Log.d(TAG_TCP, "Service sending Wi-Fi Direct message via TCP")
                    sendTcpData(message)
                } else {
                    Log.w(TAG_TCP, "Service cannot send Wi-Fi Direct message via TCP: Not connected.")
                }
            }
        }
    }

    private fun listenForTcpToWifiDirectMessages() {
        lifecycleScope.launch {
            CommunicationHub.tcpToWifiDirectFlow.collect { message ->
                Log.i(TAG_WIFI_DIRECT, "Service received message from Hub (TCP -> Wi-Fi Direct): $message")
                if (_wifiDirectUiState.value.connectionInfo?.groupFormed == true && wifiDirectDataTransferThread?.isAlive == true) {
                    Log.d(TAG_WIFI_DIRECT, "Service forwarding TCP message to Wi-Fi Direct peer.")
                    sendWifiDirectDataInternal(message)
                } else {
                    Log.w(TAG_WIFI_DIRECT, "Service cannot forward TCP message to Wi-Fi Direct: Not connected or thread not ready.")
                }
            }
        }
    }

    // --- Notification ---
    private fun updateNotificationCombined() {
        if (!settingsRepository.isBackgroundExecutionEnabled()) return

        val bleStatus = _bleUiState.value.connectedDeviceName ?: "끊김"
        val tcpStatusText = _tcpUiState.value.connectionStatus.replace("TCP/IP: ", "")
        val wdUiState = _wifiDirectUiState.value
        val wdStatus = wdUiState.connectedDeviceName ?: wdUiState.statusText.replace("Wi-Fi Direct: ", "")

        val contentText = "BLE: $bleStatus, TCP: $tcpStatusText, WD: $wdStatus"
        updateNotification(contentText)
    }

    private fun createNotificationChannel() {
        val name = "MQBL 통신 서비스"
        val descriptionText = "백그라운드 BLE, TCP/IP, Wi-Fi Direct 연결 상태 알림"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }

        val alertChannelName = "MQBL 위험 감지 알림"
        val alertChannelDescription = "위험 상황(사이렌, 경적 등) 감지 시 알림"
        val alertImportance = NotificationManager.IMPORTANCE_HIGH
        val alertChannel = NotificationChannel(ALERT_NOTIFICATION_CHANNEL_ID, alertChannelName, alertImportance).apply {
            description = alertChannelDescription
        }

        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(alertChannel)
        Log.d(TAG_SERVICE, "Notification channels created.")

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
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // --- BLE 통신 스레드 (Inner Class) ---
    @SuppressLint("MissingPermission")
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            try {
                // --- ▼▼▼ 가장 중요한 수정 부분 ▼▼▼ ---
                // 보안 소켓 대신 비보안 소켓을 사용하여 호환성 문제를 해결합니다.
                Log.d(TAG_BLE, "Creating Insecure RFCOMM socket to service record...")
                device.createInsecureRfcommSocketToServiceRecord(BT_UUID)
                // --- ▲▲▲ 가장 중요한 수정 부분 끝 ▲▲▲ ---
            } catch (e: IOException) {
                Log.e(TAG_BLE, "ConnectThread: Insecure Socket create failed", e)
                lifecycleScope.launch { connectionFailed("소켓(insecure) 생성 실패: ${e.message}") }
                null
            } catch (e: SecurityException) {
                Log.e(TAG_BLE, "ConnectThread: Insecure Socket create security error", e)
                lifecycleScope.launch { connectionFailed("소켓(insecure) 생성 권한 오류: ${e.message}") }
                null
            }
        }

        override fun run() {
            if (mmSocket == null) {
                connectThread = null
                return
            }
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: SecurityException){
                Log.e(TAG_BLE, "cancelDiscovery failed due to SecurityException. Ensure permissions are granted.", e)
            }


            mmSocket?.let { socket ->
                try {
                    Log.i(TAG_BLE, "ConnectThread: Connecting to socket...")
                    socket.connect()
                    Log.i(TAG_BLE, "ConnectThread: Connection successful.")
                    manageConnectedSocket(socket)
                } catch (e: Exception) {
                    Log.e(TAG_BLE, "ConnectThread: Connection failed", e)
                    lifecycleScope.launch { connectionFailed("연결 실패: ${e.message}") }
                    try {
                        socket.close()
                    } catch (ce: IOException) {
                        Log.e(TAG_BLE, "ConnectThread: Socket close failed after connection error", ce)
                    }
                }
            }
            connectThread = null
        }

        fun cancel() {
            try {
                mmSocket?.close()
                Log.d(TAG_BLE, "ConnectThread: Socket closed on cancel.")
            } catch (e: IOException) {
                Log.e(TAG_BLE, "ConnectThread: Socket close failed on cancel", e)
            }
        }
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream? = try {
            mmSocket.inputStream
        } catch (e: IOException) {
            Log.e(TAG_BLE, "ConnectedThread: Error getting input stream", e)
            lifecycleScope.launch { connectionLost() }
            null
        }
        private val mmOutStream: OutputStream? = try {
            mmSocket.outputStream
        } catch (e: IOException) {
            Log.e(TAG_BLE, "ConnectedThread: Error getting output stream", e)
            lifecycleScope.launch { connectionLost() }
            null
        }
        private var isRunning = true

        override fun run() {
            Log.i(TAG_BLE, "ConnectedThread: Listening for incoming data...")
            val buffer = ByteArray(1024)
            val messageBuilder = StringBuilder()

            while (isRunning) {
                if (mmInStream == null) {
                    lifecycleScope.launch { connectionLost() }
                    break
                }
                try {
                    val numBytes = mmInStream.read(buffer)
                    if (numBytes > 0) {
                        val readChunk = String(buffer, 0, numBytes, Charsets.UTF_8)
                        messageBuilder.append(readChunk)

                        var newlineIndex = messageBuilder.indexOf('\n')
                        while (newlineIndex >= 0) {
                            val line = messageBuilder.substring(0, newlineIndex)
                            val finalMessage = if (line.endsWith('\r')) line.dropLast(1) else line
                            Log.d(TAG_BLE, "Received complete line: '$finalMessage'")
                            processBleMessage(finalMessage)
                            messageBuilder.delete(0, newlineIndex + 1)
                            newlineIndex = messageBuilder.indexOf('\n')
                        }
                    } else if (numBytes == -1) {
                        Log.w(TAG_BLE, "ConnectedThread: End of stream reached. Connection closed by peer.")
                        isRunning = false
                        lifecycleScope.launch { connectionLost() }
                    }
                } catch (e: IOException) {
                    Log.w(TAG_BLE, "ConnectedThread: Read failed, disconnecting.", e)
                    isRunning = false
                    lifecycleScope.launch { connectionLost() }
                }
            }
            Log.i(TAG_BLE, "ConnectedThread: Finished.")
            if (this@CommunicationService.connectedThread == this) {
                this@CommunicationService.connectedThread = null
            }
        }

        fun write(bytes: ByteArray) {
            if (mmOutStream == null) {
                lifecycleScope.launch { connectionLost() }
                return
            }
            try {
                mmOutStream.write(bytes)
                mmOutStream.flush()
                Log.d(TAG_BLE, "Data sent: ${String(bytes, Charsets.UTF_8)}")
                updateBleDataLog("-> ${String(bytes, Charsets.UTF_8)} (BLE)")
            } catch (e: IOException) {
                Log.e(TAG_BLE, "ConnectedThread: Write Error", e)
                lifecycleScope.launch { connectionLost() }
            }
        }

        fun cancel() {
            isRunning = false
            try {
                mmSocket.close()
                Log.d(TAG_BLE, "ConnectedThread: Socket closed on cancel.")
            } catch (e: IOException) {
                Log.e(TAG_BLE, "ConnectedThread: Socket close error on cancel", e)
            }
        }
    }

    private fun addCustomSoundEvent(description: String) {
        lifecycleScope.launch {
            val currentTime = timeFormatter.format(Date())
            val newEvent = CustomSoundEvent(description = description, timestamp = currentTime)
            val currentList = _customSoundEventLog.value
            val updatedList = (listOf(newEvent) + currentList).take(MAX_DETECTION_LOG_SIZE)
            _customSoundEventLog.value = updatedList
            Log.i(TAG_SERVICE, "Custom Sound Event Added: $description")
        }
    }

    // --- Wi-Fi Direct BroadcastReceiver (Inner Class) ---
    inner class WiFiDirectBroadcastReceiver(
        private val manager: WifiP2pManager,
        private val channel: WifiP2pManager.Channel,
        private val service: CommunicationService
    ) : BroadcastReceiver() {

        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action: String = intent.action ?: return
            Log.d(TAG_WIFI_DIRECT, "Receiver received action: $action")

            when (action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    service._wifiDirectUiState.update {
                        val newStatus = if (isEnabled) {
                            if (it.statusText == "Wi-Fi Direct: 비활성화됨" || it.statusText == "Wi-Fi Direct: 대기 중") "Wi-Fi Direct: 활성화됨" else it.statusText
                        } else "Wi-Fi Direct: 비활성화됨"
                        it.copy(
                            isWifiDirectEnabled = isEnabled,
                            statusText = newStatus,
                            peers = if (!isEnabled) emptyList() else it.peers,
                            connectionInfo = if (!isEnabled) null else it.connectionInfo,
                            connectedDeviceName = if (!isEnabled) null else it.connectedDeviceName,
                            isConnecting = if (!isEnabled) false else it.isConnecting
                        )
                    }
                    Log.i(TAG_WIFI_DIRECT, "P2P state changed. Enabled: $isEnabled. Current status: ${service._wifiDirectUiState.value.statusText}")
                    if (!isEnabled) {
                        service.disconnectWifiDirect(notifyUi = true)
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG_WIFI_DIRECT, "P2P peers changed.")
                    if (service.hasWifiDirectPermissions(checkOnly = true) && service._wifiDirectUiState.value.isWifiDirectEnabled) {
                        manager.requestPeers(channel, service.wifiDirectPeerListListener)
                    } else {
                        Log.w(TAG_WIFI_DIRECT, "Cannot request peers, permission missing or Wi-Fi Direct disabled.")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.d(TAG_WIFI_DIRECT, "P2P_CONNECTION_CHANGED_ACTION received.")
                    val p2pInfo: WifiP2pInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }
                    Log.d(TAG_WIFI_DIRECT, "WifiP2pInfo from intent: $p2pInfo")

                    if (p2pInfo?.groupFormed == true) {
                        Log.i(TAG_WIFI_DIRECT, "Connection successful (group formed). Requesting details.")
                        service._wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 연결 정보 확인 중...", isConnecting = true ) }
                        manager.requestConnectionInfo(channel, service.wifiDirectConnectionInfoListener)
                    } else {
                        @Suppress("DEPRECATION")
                        val networkInfo: NetworkInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                        } else {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }
                        Log.d(TAG_WIFI_DIRECT, "NetworkInfo (for logging only, deprecated): $networkInfo")

                        Log.i(TAG_WIFI_DIRECT, "Wi-Fi Direct group not formed or connection lost.")
                        service.wifiDirectDataTransferThread?.cancel()
                        service.wifiDirectDataTransferThread = null

                        val currentUiState = service._wifiDirectUiState.value
                        if (currentUiState.isConnecting || currentUiState.connectedDeviceName != null) {
                            service._wifiDirectUiState.update {
                                it.copy(
                                    isConnecting = false,
                                    statusText = "Wi-Fi Direct: 연결 해제됨",
                                    connectedDeviceName = null,
                                    connectionInfo = null,
                                    isGroupOwner = false,
                                    groupOwnerAddress = null,
                                    errorMessage = it.errorMessage ?: "연결이 종료되었습니다."
                                )
                            }
                        }
                        service.updateNotificationCombined()
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val thisDevice: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    val statusString = thisDevice?.status?.let { service.getWifiP2pDeviceStatusString(it) } ?: "알 수 없음"
                    Log.d(TAG_WIFI_DIRECT, "This device details changed: ${thisDevice?.deviceName}, Status: $statusString")
                }
            }
        }
    }

    private fun getWifiP2pDeviceStatusString(status: Int): String {
        return when (status) {
            WifiP2pDevice.AVAILABLE -> "사용 가능"
            WifiP2pDevice.INVITED -> "초대됨"
            WifiP2pDevice.CONNECTED -> "연결됨"
            WifiP2pDevice.FAILED -> "실패"
            WifiP2pDevice.UNAVAILABLE -> "사용 불가"
            else -> "알 수 없음 ($status)"
        }
    }


    // --- Wi-Fi Direct Data Transfer Thread (Inner Class) ---
    private inner class WifiDirectDataTransferThread(private val p2pInfo: WifiP2pInfo) : Thread() {
        private var serverSocket: ServerSocket? = null
        private var clientSocket: Socket? = null
        private var inputStream: InputStream? = null
        private var outputStream: OutputStream? = null
        private val threadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val isSocketConnected: Boolean
            get() = clientSocket?.isConnected == true && clientSocket?.isClosed == false


        override fun run() {
            Log.d(TAG_WIFI_DIRECT, "WifiDirectDataTransferThread started. IsGO: ${p2pInfo.isGroupOwner}")
            try {
                if (p2pInfo.isGroupOwner) {
                    serverSocket = ServerSocket(WIFI_DIRECT_SERVER_PORT)
                    Log.d(TAG_WIFI_DIRECT, "GO: ServerSocket opened on port $WIFI_DIRECT_SERVER_PORT. Waiting for client...")
                    _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 클라이언트 연결 대기 중...") }
                    clientSocket = serverSocket!!.accept()
                    Log.i(TAG_WIFI_DIRECT, "GO: Client connected: ${clientSocket?.inetAddress?.hostAddress}")
                    _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 클라이언트 연결됨", connectedDeviceName = "클라이언트 (${clientSocket?.inetAddress?.hostAddress})") }
                } else {
                    clientSocket = Socket()
                    val hostAddress = p2pInfo.groupOwnerAddress.hostAddress
                    Log.d(TAG_WIFI_DIRECT, "Client: Connecting to GO at $hostAddress:$WIFI_DIRECT_SERVER_PORT...")
                    _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 그룹 소유자에게 연결 중...") }
                    clientSocket!!.connect(InetSocketAddress(hostAddress, WIFI_DIRECT_SERVER_PORT), SOCKET_TIMEOUT)
                    Log.i(TAG_WIFI_DIRECT, "Client: Connected to GO.")
                    _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 그룹 소유자에게 연결됨") }
                }
                updateNotificationCombined()

                inputStream = clientSocket!!.getInputStream()
                outputStream = clientSocket!!.getOutputStream()
                val reader = BufferedReader(InputStreamReader(inputStream!!))

                while (currentThread().isAlive && !currentThread().isInterrupted && clientSocket?.isConnected == true && !clientSocket!!.isClosed) {
                    try {
                        val line = reader.readLine()
                        if (line != null) {
                            Log.i(TAG_WIFI_DIRECT, "DataTransferThread Received: $line")
                            addWifiDirectLog("<- $line (Wi-Fi Direct)")
                            threadScope.launch { CommunicationHub.emitWifiDirectToTcp(line) }
                        } else {
                            Log.w(TAG_WIFI_DIRECT, "DataTransferThread: readLine returned null. Peer likely closed connection.")
                            break
                        }
                    } catch (e: IOException) {
                        if (currentThread().isInterrupted || clientSocket?.isClosed == true || clientSocket?.isConnected == false) {
                            Log.d(TAG_WIFI_DIRECT, "DataTransferThread: Socket closed or thread interrupted during read. ${e.message}")
                        } else {
                            Log.e(TAG_WIFI_DIRECT, "DataTransferThread: IOException during read", e)
                        }
                        break
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG_WIFI_DIRECT, "DataTransferThread: IOException during socket setup or accept", e)
                _wifiDirectUiState.update { it.copy(errorMessage = "Wi-Fi Direct 통신 오류: ${e.message}") }
            } catch (e: Exception) {
                Log.e(TAG_WIFI_DIRECT, "DataTransferThread: Unexpected exception", e)
                _wifiDirectUiState.update { it.copy(errorMessage = "Wi-Fi Direct 예외: ${e.message}") }
            } finally {
                Log.d(TAG_WIFI_DIRECT, "DataTransferThread finishing.")
                cancelInternals()
            }
        }

        fun write(message: String) {
            if (outputStream != null && clientSocket?.isConnected == true && !clientSocket!!.isOutputShutdown) {
                threadScope.launch {
                    try {
                        outputStream?.write((message + "\n").toByteArray(Charsets.UTF_8))
                        outputStream?.flush()
                        Log.i(TAG_WIFI_DIRECT, "DataTransferThread Sent: $message")
                        addWifiDirectLog("-> $message (Wi-Fi Direct)")
                    } catch (e: IOException) {
                        Log.e(TAG_WIFI_DIRECT, "DataTransferThread: IOException during write", e)
                        _wifiDirectUiState.update { it.copy(errorMessage = "Wi-Fi Direct 메시지 전송 실패: ${e.message}") }
                        cancelInternals()
                    }
                }
            } else {
                Log.w(TAG_WIFI_DIRECT, "DataTransferThread: Cannot write, outputStream is null or socket not connected/output shutdown.")
                _wifiDirectUiState.update { it.copy(errorMessage = "Wi-Fi Direct 메시지 전송 불가: 연결되지 않음") }
            }
        }

        fun cancel() {
            Log.d(TAG_WIFI_DIRECT, "DataTransferThread public cancel called.")
            interrupt()
            cancelInternals()
        }

        private fun cancelInternals() {
            Log.d(TAG_WIFI_DIRECT, "DataTransferThread cancelInternals called.")
            threadScope.cancel()
            try { inputStream?.close() } catch (e: IOException) { Log.e(TAG_WIFI_DIRECT, "Error closing WD input stream", e) }
            try { outputStream?.close() } catch (e: IOException) { Log.e(TAG_WIFI_DIRECT, "Error closing WD output stream", e) }
            try { clientSocket?.close() } catch (e: IOException) { Log.e(TAG_WIFI_DIRECT, "Error closing WD client socket", e) }
            try { serverSocket?.close() } catch (e: IOException) { Log.e(TAG_WIFI_DIRECT, "Error closing WD server socket", e) }
            inputStream = null
            outputStream = null
            clientSocket = null
            serverSocket = null

            if (this@CommunicationService.wifiDirectDataTransferThread == this) {
                this@CommunicationService.wifiDirectDataTransferThread = null
            }
            if (_wifiDirectUiState.value.connectedDeviceName != null) {
                _wifiDirectUiState.update {
                    if (it.connectedDeviceName != null) {
                        it.copy(
                            statusText = "Wi-Fi Direct: 연결 종료됨",
                            connectedDeviceName = null,
                            connectionInfo = null,
                            isGroupOwner = false,
                            groupOwnerAddress = null,
                            errorMessage = it.errorMessage ?: "통신 채널이 닫혔습니다."
                        )
                    } else it
                }
                updateNotificationCombined()
            }
            Log.d(TAG_WIFI_DIRECT, "DataTransferThread resources cleaned up.")
        }
    }

    /**
     * 사용자에게 긴급 알림을 보냅니다.
     * @param contentText 알림에 표시될 메시지 (예: "사이렌이 감지되었습니다.")
     */
    private fun sendAlertNotification(contentText: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, ALERT_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("위험 감지!")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
        Log.i(TAG_SERVICE, "Alert notification sent: $contentText")
    }
}
