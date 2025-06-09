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
        // BLE
        fun getBleUiStateFlow(): StateFlow<BleUiState> = _bleUiState.asStateFlow()
        fun getBondedDevicesFlow(): StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()
        fun getDetectionLogFlow(): StateFlow<List<DetectionEvent>> = _detectionEventLog.asStateFlow()
        // TCP
        fun getTcpUiStateFlow(): StateFlow<TcpUiState> = _tcpUiState.asStateFlow()
        fun getReceivedTcpMessagesFlow(): StateFlow<List<TcpMessageItem>> = _receivedTcpMessages.asStateFlow()
        // Wi-Fi Direct
        fun getWifiDirectUiStateFlow(): StateFlow<WifiDirectUiState> = _wifiDirectUiState.asStateFlow()
    }
    private val binder = LocalBinder()

    private lateinit var settingsRepository: SettingsRepository

    // BLE
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private val _bleUiState = MutableStateFlow(BleUiState())
    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    private val _detectionEventLog = MutableStateFlow<List<DetectionEvent>>(emptyList())

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
        createNotificationChannel()
        initializeBle()
        initializeWifiDirect() // Wi-Fi Direct 초기화
        listenForTcpToBleMessages()
        listenForBleToTcpMessages()
        listenForWifiDirectToTcpMessages() // Wi-Fi Direct -> TCP Hub 리스너
        listenForTcpToWifiDirectMessages() // TCP -> Wi-Fi Direct Hub 리스너
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.i(TAG_SERVICE, "Service onStartCommand Received with action: $action")

        when (action) {
            ACTION_START_FOREGROUND -> {
                // 포그라운드 서비스 시작 명령
                startForeground(NOTIFICATION_ID, createNotification("서비스 실행 중..."))
                Log.i(TAG_SERVICE, "Foreground service started explicitly.")
            }
            ACTION_STOP_FOREGROUND -> {
                // 포그라운드 서비스 중지 및 서비스 완전 종료 명령
                Log.i(TAG_SERVICE, "Stopping foreground service and self...")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf() // 서비스 자체를 종료
            }
            else -> {
                // 앱 시작 시 또는 다른 이유로 서비스가 시작될 때
                // 저장된 설정 값을 확인하여 포그라운드 여부 결정
                if (settingsRepository.isBackgroundExecutionEnabled()) {
                    startForeground(NOTIFICATION_ID, createNotification("서비스 실행 중..."))
                    Log.i(TAG_SERVICE, "Service started, background execution is ENABLED.")
                } else {
                    Log.i(TAG_SERVICE, "Service started, background execution is DISABLED.")
                    // 포그라운드로 시작하지 않음. 앱이 메모리에서 해제되면 서비스도 종료될 수 있음.
                }
            }
        }

        return START_STICKY // 서비스가 강제 종료되어도 시스템이 다시 시작하려고 시도
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Log.i(TAG_SERVICE, "Service onBind")
        return binder
    }
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG_SERVICE, "Service onUnbind")
        return true // 재바인딩 허용
    }

    override fun onDestroy() {
        Log.w(TAG_SERVICE, "Service onDestroy")
        disconnectBle()
        disconnectTcpInternal(userRequested = false)
        unregisterWifiDirectReceiver() // Wi-Fi Direct 리시버 해제
        disconnectWifiDirect(notifyUi = false) // 서비스 종료 시 UI 알림은 불필요할 수 있음
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
        if (!hasWifiDirectPermissions(checkOnly = true)) { // 권한만 확인
            _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 권한 필요", errorMessage = "Wi-Fi Direct 기능을 사용하려면 권한이 필요합니다.") }
            return
        }
        wifiP2pChannel?.let { channel -> // Null safety 추가
            wifiP2pManager.requestConnectionInfo(channel, wifiDirectConnectionInfoListener)
        }
        // requestPeers는 discoverPeers 후 PEERS_CHANGED_ACTION에서 호출되므로 여기서는 생략 가능
    }

    @SuppressLint("MissingPermission")
    fun discoverWifiDirectPeers() {
        if (!hasWifiDirectPermissions()) { // 여기서 실제 권한 체크 및 UI 업데이트
            return
        }
        if (!_wifiDirectUiState.value.isWifiDirectEnabled) {
            _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 비활성화됨", errorMessage = "Wi-Fi Direct가 꺼져 있습니다.") }
            return
        }
        _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 피어 검색 중...", peers = emptyList(), errorMessage = null) }
        updateNotificationCombined()
        wifiP2pChannel?.let { channel -> // Null safety 추가
            wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG_WIFI_DIRECT, "Peer discovery initiated successfully.")
                    // 성공 시 UI 상태는 PeerListListener에서 업데이트
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
            // groupOwnerIntent = 0 // 기본값 사용 또는 필요시 설정
        }
        _wifiDirectUiState.update { it.copy(isConnecting = true, statusText = "Wi-Fi Direct: ${device.deviceName}에 연결 시도 중...", errorMessage = null) }
        updateNotificationCombined()

        wifiP2pChannel?.let { channel -> // Null safety 추가
            wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG_WIFI_DIRECT, "Successfully initiated connection to ${device.deviceName}")
                    // 실제 연결 성공은 WIFI_P2P_CONNECTION_CHANGED_ACTION 에서 처리
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

        wifiP2pChannel?.let { channel -> // Null safety 추가
            // 진행 중인 연결 시도 취소
            wifiP2pManager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG_WIFI_DIRECT, "cancelConnect success") }
                override fun onFailure(reason: Int) { Log.d(TAG_WIFI_DIRECT, "cancelConnect failed: ${getWifiP2pFailureReason(reason)}") }
            })

            // 그룹 제거
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
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
                        _wifiDirectUiState.update { // 이미 연결 해제된 상태일 수 있으므로, 상태를 명확히 정리
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
        // 데이터 전송 스레드 중단 및 소켓 정리
        wifiDirectDataTransferThread?.cancel()
        wifiDirectDataTransferThread = null
        // UI 상태는 BroadcastReceiver 또는 위의 콜백에서 최종적으로 업데이트될 것임
    }


    // ViewModel에서 호출될 public 함수
    fun sendWifiDirectData(message: String) {
        sendWifiDirectDataInternal(message)
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
        // 기존 연결 해제
        disconnectBle() // 연결 시도 전에 기존 연결을 확실히 해제
        connectThread = ConnectThread(device)
        connectThread?.start()
    }

    private fun disconnectBle() {
        if (connectThread == null && connectedThread == null && !_bleUiState.value.isConnecting && _bleUiState.value.connectedDeviceName == null) return // 이미 끊겼거나 연결 시도 중이 아니면 반환
        Log.i(TAG_BLE, "Disconnecting BLE connection...")
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        // 연결 상태였거나 연결 중이었다면 상태 업데이트
        if (_bleUiState.value.connectedDeviceName != null || _bleUiState.value.isConnecting) {
            _bleUiState.update {
                it.copy(
                    status = "상태: 연결 해제됨",
                    connectedDeviceName = null,
                    isConnecting = false,
                    connectError = null // 연결 해제 시에는 이전 오류 메시지 초기화
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
        val message = value.toString() // 정수를 문자열로 변환
        connectedThread?.write(message.toByteArray()) // 문자열을 바이트 배열로 변환하여 전송
    }

    @SuppressLint("MissingPermission")
    private fun manageConnectedSocket(socket: BluetoothSocket) {
        val deviceName = try { socket.remoteDevice.name ?: "알 수 없는 기기" } catch (e: SecurityException) { "알 수 없는 기기" }
        Log.i(TAG_BLE, "BLE Connection successful with $deviceName.")
        // UI 업데이트는 메인 스레드에서 수행하도록 launch 사용
        lifecycleScope.launch {
            _bleUiState.update {
                it.copy(
                    status = "상태: ${deviceName}에 연결됨",
                    connectedDeviceName = deviceName,
                    isConnecting = false, // 연결 성공 시 연결 중 상태 해제
                    connectError = null
                )
            }
            updateNotificationCombined()
        }
        // 기존 ConnectedThread가 있다면 취소
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
                    connectedDeviceName = null, // 연결 실패 시 이름 null 처리
                    isConnecting = false,       // 연결 중 상태 해제
                    connectError = errorMsg
                )
            }
            updateNotificationCombined()
        }
        connectThread = null // 실패한 ConnectThread 정리
        connectedThread?.cancel() // 혹시 모를 ConnectedThread 정리
        connectedThread = null
    }

    private fun connectionLost() {
        Log.w(TAG_BLE, "BLE Connection lost.")
        // 연결된 상태였거나, 명시적으로 연결 해제 중이 아닐 때만 '연결 끊김'으로 처리
        if (_bleUiState.value.connectedDeviceName != null || _bleUiState.value.status.contains("연결됨")) {
            lifecycleScope.launch {
                _bleUiState.update {
                    it.copy(
                        status = "상태: 연결 끊김",
                        connectedDeviceName = null,
                        isConnecting = false, // 연결 끊김 시 연결 중 상태도 해제
                        connectError = "기기와의 연결이 끊어졌습니다."
                    )
                }
                updateNotificationCombined()
            }
        }
        connectedThread = null // 연결 끊김 시 ConnectedThread 정리
    }

    private fun processBleMessage(message: String) {
        updateBleDataLog("<- $message (BLE)") // 함수명 변경 및 로그 방향 표시
        lifecycleScope.launch {
            Log.d(TAG_BLE, "Forwarding message from BLE to Hub for TCP: $message")
            CommunicationHub.emitBleToTcp(message)
        }
        // 특정 메시지에 따른 이벤트 처리 (예: "siren", "horn", "boom")
        val trimmedMessage = message.trim()
        var eventDescription: String? = null
        when (trimmedMessage.lowercase()) {
            "siren" -> eventDescription = "사이렌 감지됨 (BLE)"
            "horn" -> eventDescription = "경적 감지됨 (BLE)"
            "boom" -> eventDescription = "폭발음 감지됨 (BLE)"
            // 필요시 더 많은 케이스 추가
        }
        if (eventDescription != null) {
            addDetectionEvent(eventDescription)
        }
    }

    private fun updateBleDataLog(logEntry: String) { // 함수명 변경
        lifecycleScope.launch {
            val currentLog = _bleUiState.value.receivedDataLog
            // 로그 형식 변경: 새 로그가 위로, 최대 길이 제한
            val newLog = "$logEntry\n$currentLog".take(1000) // 최대 1000자 제한 예시
            _bleUiState.update { it.copy(receivedDataLog = newLog) }
        }
    }

    private fun addDetectionEvent(description: String) {
        lifecycleScope.launch {
            val currentTime = timeFormatter.format(Date())
            val newEvent = DetectionEvent(description = description, timestamp = currentTime)
            val currentList = _detectionEventLog.value
            val updatedList = (listOf(newEvent) + currentList).take(MAX_DETECTION_LOG_SIZE) // 새 이벤트가 맨 위로
            _detectionEventLog.value = updatedList
            Log.i(TAG_BLE, "Detection Event Added: $description")
        }
    }

    private fun hasRequiredBlePermissions(): Boolean {
        // Android S (API 31) 이상에서는 BLUETOOTH_CONNECT, BLUETOOTH_SCAN 권한 필요
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // API 30 이하에서는 BLUETOOTH, BLUETOOTH_ADMIN 권한이 Manifest에 선언되어 있으면 됨
            // ACCESS_FINE_LOCATION은 스캔에 필요할 수 있음 (특히 클래식 블루투스)
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
        lifecycleScope.launch(Dispatchers.IO) { // 네트워크 작업은 IO 스레드에서
            try {
                _tcpUiState.update { it.copy(connectionStatus = "TCP/IP: 연결 중...", errorMessage = null) }
                updateNotificationCombined()

                tcpSocket = Socket() // 새 소켓 생성
                // 연결 타임아웃 설정 (예: 5초)
                tcpSocket?.connect(InetSocketAddress(currentServerIp, currentServerPort), SOCKET_TIMEOUT)

                if (tcpSocket?.isConnected == true) {
                    // 스트림 초기화
                    tcpPrintWriter = PrintWriter(tcpSocket!!.getOutputStream(), true) // autoFlush true
                    tcpBufferedReader = BufferedReader(InputStreamReader(tcpSocket!!.getInputStream()))
                    _tcpUiState.update { it.copy(isConnected = true, connectionStatus = "TCP/IP: 연결됨", errorMessage = null) }
                    Log.i(TAG_TCP, "TCP Connected to $currentServerIp:$currentServerPort")
                    updateNotificationCombined()
                    startTcpReceiveLoop() // 연결 성공 시 수신 루프 시작
                } else {
                    // 이 경우는 connect에서 예외가 발생하지 않았지만 연결되지 않은 드문 상황
                    throw IOException("Socket connect failed post-attempt without exception")
                }
            } catch (e: Exception) { // IOException, SocketTimeoutException 등 모든 예외 처리
                Log.e(TAG_TCP, "TCP Connection Error to $currentServerIp:$currentServerPort", e)
                _tcpUiState.update { it.copy(isConnected = false, connectionStatus = "TCP/IP: 연결 실패", errorMessage = "연결 오류: ${e.message}") }
                updateNotificationCombined()
                closeTcpSocketResources() // 실패 시 자원 정리
            }
        }
    }

    private fun startTcpReceiveLoop() {
        tcpReceiveJob?.cancel() // 기존 작업이 있다면 취소
        tcpReceiveJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG_TCP, "Starting TCP receive loop...")
            try {
                while (isActive && tcpSocket?.isConnected == true && tcpBufferedReader != null) {
                    val line = tcpBufferedReader?.readLine() // 라인 단위로 읽기
                    if (line != null) {
                        Log.i(TAG_TCP, "TCP Received: $line")
                        val newItem = TcpMessageItem(source = "$currentServerIp:$currentServerPort", payload = line)
                        _receivedTcpMessages.update { list -> (listOf(newItem) + list).take(MAX_TCP_LOG_SIZE) } // 새 메시지를 맨 앞에 추가

                        // 수신된 TCP 메시지를 BLE로 전달 (Hub 사용)
                        Log.d(TAG_TCP, "Forwarding TCP message to Hub for BLE: $line")
                        CommunicationHub.emitTcpToBle(line)
                        // 수신된 TCP 메시지를 Wi-Fi Direct로 전달 (Hub 사용)
                        Log.d(TAG_TCP, "Forwarding TCP message to Hub for Wi-Fi Direct: $line")
                        CommunicationHub.emitTcpToWifiDirect(line)
                    } else {
                        // readLine()이 null을 반환하면 서버가 연결을 닫은 것일 수 있음
                        Log.w(TAG_TCP, "TCP readLine returned null, server might have closed connection.")
                        break // 루프 종료
                    }
                }
            } catch (e: IOException) {
                // 소켓이 닫혔거나 네트워크 문제 발생 시
                Log.e(TAG_TCP, "TCP Receive Loop IOException (Connection likely lost)", e)
            } catch (e: Exception) {
                Log.e(TAG_TCP, "TCP Receive Loop Exception", e)
            } finally {
                Log.d(TAG_TCP, "TCP receive loop ended.")
                // 루프가 정상 종료되지 않았고 (예: 예외 발생), 서비스가 아직 활성 상태라면 연결 끊김 처리
                if (isActive) { // isActive는 코루틴 스코프의 활성 상태
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
        lifecycleScope.launch(Dispatchers.IO) { // 네트워크 작업은 IO 스레드
            try {
                tcpPrintWriter?.println(message) // 메시지 전송 (println은 개행 문자 추가)
                // tcpPrintWriter?.flush() // PrintWriter 생성 시 autoFlush=true로 설정했으면 생략 가능
                Log.i(TAG_TCP, "TCP Sent: $message")
                val sentItem = TcpMessageItem(source = "클라이언트 -> 서버", payload = message)
                _receivedTcpMessages.update { list -> (listOf(sentItem) + list).take(MAX_TCP_LOG_SIZE) }
            } catch (e: Exception) {
                Log.e(TAG_TCP, "TCP Send Error", e)
                _tcpUiState.update { it.copy(errorMessage = "TCP 메시지 전송 오류: ${e.message}") }
                disconnectTcpInternal(userRequested = false, "전송 중 연결 끊김") // 전송 오류 시 연결 상태 재확인 및 정리
            }
        }
    }

    private fun disconnectTcpInternal(userRequested: Boolean, reason: String? = null) {
        // 이미 연결 해제 상태이거나, 연결 시도 중이 아닐 때 중복 호출 방지
        if (tcpSocket == null && !_tcpUiState.value.isConnected && !_tcpUiState.value.connectionStatus.contains("연결 중")) {
            Log.d(TAG_TCP, "TCP Disconnect ignored: Already disconnected or not initialized.")
            return
        }

        Log.i(TAG_TCP, "Disconnecting TCP (User requested: $userRequested, Reason: $reason)...")
        tcpReceiveJob?.cancel() // 수신 루프 중단
        tcpReceiveJob = null
        closeTcpSocketResources() // 소켓 및 스트림 자원 정리

        val statusMessage = reason ?: if (userRequested) "연결 해제됨" else "연결 끊김"
        _tcpUiState.update {
            it.copy(
                isConnected = false,
                connectionStatus = "TCP/IP: $statusMessage",
                errorMessage = if (reason != null && !userRequested) reason else null // 사용자 요청이 아닐 때만 reason을 에러 메시지로
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
            // 인텐트 필터 설정
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
        // 초기 Wi-Fi 상태 확인
        wifiP2pChannel?.let { channel -> // Null safety 추가
            wifiP2pManager.requestP2pState(channel) { state -> // requestP2pState 추가
                _wifiDirectUiState.update { it.copy(isWifiDirectEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) }
            }
        }
        refreshWifiDirectState() // 초기 상태 확인
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
                // 검색 중 상태였다면 "검색 완료"로 변경, 아니면 기존 상태 유지 (연결 상태 등 덮어쓰지 않도록)
                statusText = if (it.statusText.contains("피어 검색 중")) {
                    if (refreshedPeers.isEmpty()) "Wi-Fi Direct: 주변 기기 없음" else "Wi-Fi Direct: 검색 완료"
                } else {
                    // 이미 연결되었거나 다른 상태 메시지가 있다면 유지
                    if (it.connectedDeviceName == null && refreshedPeers.isEmpty() && !it.isConnecting) "Wi-Fi Direct: 주변 기기 없음" else it.statusText
                }
            )
        }
    }

    private val wifiDirectConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        Log.d(TAG_WIFI_DIRECT, "Connection info available: GroupFormed=${info.groupFormed}, IsGO=${info.isGroupOwner}, GOAddress=${info.groupOwnerAddress}")
        if (info.groupFormed) {
            // 연결된 상대방 기기 이름 찾기 (Peers 목록에서)
            val remoteDeviceAddress = if (info.isGroupOwner) {
                // 내가 GO일 때, 연결된 클라이언트의 주소를 찾아야 함. WifiP2pGroup에서 가져올 수 있으나, 여기서는 info만 사용.
                // WifiP2pGroup group = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
                // clientList = group.getClientList();
                // 여기서는 임시로 "클라이언트" 또는 IP로 표시
                // 실제 클라이언트 주소는 WifiP2pGroup.getClientList()를 통해 얻어야 함.
                // 현재는 연결된 피어 목록에서 GO가 아닌 연결된 장치를 찾으려고 시도.
                _wifiDirectUiState.value.peers.find { peer ->
                    // WifiP2pDevice.CONNECTED 상태이고, GO 주소와 다른 주소를 가진 피어
                    peer.status == WifiP2pDevice.CONNECTED && peer.deviceAddress != info.groupOwnerAddress?.hostAddress
                }?.deviceName ?: "클라이언트" // 못 찾으면 "클라이언트"
            } else { // 내가 클라이언트일 때
                _wifiDirectUiState.value.peers.find { it.deviceAddress == info.groupOwnerAddress?.hostAddress }?.deviceName ?: "그룹 소유자"
            }

            _wifiDirectUiState.update {
                it.copy(
                    connectionInfo = info,
                    isConnecting = false, // 연결 성공 시 '연결 중' 상태 해제
                    statusText = "Wi-Fi Direct: ${remoteDeviceAddress}에 연결됨",
                    connectedDeviceName = remoteDeviceAddress,
                    isGroupOwner = info.isGroupOwner,
                    groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                )
            }
            updateNotificationCombined()
            // 데이터 전송 스레드 시작
            wifiDirectDataTransferThread?.cancel() // 기존 스레드가 있다면 취소
            wifiDirectDataTransferThread = WifiDirectDataTransferThread(info)
            wifiDirectDataTransferThread?.start()
        } else {
            // 그룹이 해체되었거나 연결 실패 시
            Log.i(TAG_WIFI_DIRECT, "Wi-Fi Direct group not formed or connection lost.")
            if (_wifiDirectUiState.value.connectedDeviceName != null || _wifiDirectUiState.value.isConnecting) { // 이전에 연결 시도 또는 연결된 상태였다면
                _wifiDirectUiState.update {
                    it.copy(
                        connectedDeviceName = null,
                        connectionInfo = null,
                        isGroupOwner = false,
                        groupOwnerAddress = null,
                        isConnecting = false, // 연결 시도 중 실패 포함
                        statusText = "Wi-Fi Direct: 연결 끊김"
                    )
                }
                updateNotificationCombined()
            }
            wifiDirectDataTransferThread?.cancel()
            wifiDirectDataTransferThread = null
        }
    }

    // 내부 데이터 전송 함수
    private fun sendWifiDirectDataInternal(message: String) {
        wifiDirectDataTransferThread?.write(message) ?: run {
            Log.w(TAG_WIFI_DIRECT, "Cannot send Wi-Fi Direct data: DataTransferThread is null.")
            _wifiDirectUiState.update { it.copy(errorMessage = "Wi-Fi Direct 메시지 전송 실패: 통신 채널 준비 안됨") }
        }
    }


    private fun addWifiDirectLog(logEntry: String) {
        lifecycleScope.launch {
            val currentLog = _wifiDirectUiState.value.receivedDataLog
            val updatedLog = (listOf(logEntry) + currentLog).take(MAX_WIFI_DIRECT_LOG_SIZE) // 새 로그를 맨 위에 추가
            _wifiDirectUiState.update { it.copy(receivedDataLog = updatedLog) }
        }
    }

    private fun hasWifiDirectPermissions(checkOnly: Boolean = false): Boolean {
        val context: Context = this
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        // ACCESS_WIFI_STATE, CHANGE_WIFI_STATE는 일반 권한으로 Manifest에 선언되어 있으면 충분

        if (permissionsToRequest.isEmpty()) {
            return true
        } else {
            if (!checkOnly) { // checkOnly가 false일 때만 UI 상태 업데이트 (실제 권한 요청은 Activity/Fragment에서)
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
            WifiP2pManager.ERROR -> "일반 오류" // General error
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P 미지원" // P2P is unsupported on the device
            WifiP2pManager.BUSY -> "장치 사용 중" // The framework is busy and unable to service the request
            WifiP2pManager.NO_SERVICE_REQUESTS -> "활성 요청 없음" // No service discovery requests are active
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

                if (connectedThread != null && _bleUiState.value.connectedDeviceName != null) {
                    Log.d(TAG_BLE, "Service forwarding TCP message to BLE device.")
                    connectedThread?.write(message.toByteArray())
                } else {
                    Log.w(TAG_BLE, "Service cannot forward TCP message to BLE: Not connected.")
                }
            }
        }
    }

    private fun listenForWifiDirectToTcpMessages() { // Wi-Fi Direct -> TCP
        lifecycleScope.launch {
            CommunicationHub.wifiDirectToTcpFlow.collect { message ->
                Log.i(TAG_TCP, "Service received message from Hub (Wi-Fi Direct -> TCP): $message")
                if (_tcpUiState.value.isConnected) {
                    Log.d(TAG_TCP, "Service sending Wi-Fi Direct message via TCP")
                    sendTcpData(message) // 기존 TCP 전송 함수 사용
                } else {
                    Log.w(TAG_TCP, "Service cannot send Wi-Fi Direct message via TCP: Not connected.")
                    // 필요시 메시지 큐잉 또는 사용자 알림
                }
            }
        }
    }

    private fun listenForTcpToWifiDirectMessages() { // TCP -> Wi-Fi Direct
        lifecycleScope.launch {
            CommunicationHub.tcpToWifiDirectFlow.collect { message ->
                Log.i(TAG_WIFI_DIRECT, "Service received message from Hub (TCP -> Wi-Fi Direct): $message")
                // wifiDirectDataTransferThread가 null이 아니고, 스레드가 살아있는지(isAlive) 확인
                if (_wifiDirectUiState.value.connectionInfo?.groupFormed == true && wifiDirectDataTransferThread?.isAlive == true) {
                    Log.d(TAG_WIFI_DIRECT, "Service forwarding TCP message to Wi-Fi Direct peer.")
                    sendWifiDirectDataInternal(message) // Wi-Fi Direct 데이터 전송 내부 함수 사용
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
        val tcpStatusText = _tcpUiState.value.connectionStatus.replace("TCP/IP: ", "") // "TCP/IP: " 부분 제거
        val wdUiState = _wifiDirectUiState.value
        val wdStatus = wdUiState.connectedDeviceName ?: wdUiState.statusText.replace("Wi-Fi Direct: ", "") // "Wi-Fi Direct: " 부분 제거

        val contentText = "BLE: $bleStatus, TCP: $tcpStatusText, WD: $wdStatus"
        updateNotification(contentText)
    }

    private fun createNotificationChannel() {
            val name = "MQBL 통신 서비스"
            val descriptionText = "백그라운드 BLE, TCP/IP, Wi-Fi Direct 연결 상태 알림" // 설명에 Wi-Fi Direct 추가
            val importance = NotificationManager.IMPORTANCE_LOW // 중요도를 낮춰 사용자 방해 최소화
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG_SERVICE, "Notification channel created.")

    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        // PendingIntent 플래그 설정 (Android 12 이상에서는 FLAG_IMMUTABLE 또는 FLAG_MUTABLE 명시 필요)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MQBL 실행 중")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher) // 앱 아이콘 사용
            .setContentIntent(pendingIntent)    // 알림 클릭 시 MainActivity 실행
            .setOngoing(true)                   // 사용자가 알림을 지울 수 없도록 설정
            .setPriority(NotificationCompat.PRIORITY_LOW) // 알림 우선순위 낮음
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // --- BLE 통신 스레드 (Inner Class) ---
    @SuppressLint("MissingPermission") // 클래스 레벨에 어노테이션 추가
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            try {
                // UUID는 Bluetooth SPP(Serial Port Profile)의 표준 UUID 사용
                device.createRfcommSocketToServiceRecord(BT_UUID)
            } catch (e: IOException) {
                Log.e(TAG_BLE, "ConnectThread: Socket create failed", e)
                lifecycleScope.launch { connectionFailed("소켓 생성 실패: ${e.message}") }
                null
            } catch (e: SecurityException) { // 권한 문제 발생 시
                Log.e(TAG_BLE, "ConnectThread: Socket create security error", e)
                lifecycleScope.launch { connectionFailed("소켓 생성 권한 오류: ${e.message}") }
                null
            }
        }

        override fun run() {
            if (mmSocket == null) { // 소켓 생성 실패 시 스레드 종료
                connectThread = null // 현재 스레드 참조 제거
                return
            }
            // 블루투스 검색은 리소스를 많이 소모하므로 연결 시도 전에 중단
            // 권한 확인은 이미 hasRequiredBlePermissions() 또는 외부에서 수행되었다고 가정하고,
            // 여기서는 SecurityException을 catch하여 로깅.
            // 또는 명시적으로 한번 더 확인 후 호출.
            // Lint 경고를 피하기 위해 @SuppressLint를 클래스 레벨에 추가했으므로,
            // 여기서는 직접적인 권한 체크 없이 호출하되, 예외 처리는 유지.
            try {
                // bluetoothAdapter?.cancelDiscovery() 호출 시 BLUETOOTH_SCAN 권한 필요 (API 31+)
                // 또는 BLUETOOTH_ADMIN (API 30 이하)
                // 이미 권한 확인 로직이 외부(예: connectToDevice) 또는 hasRequiredBlePermissions 에 있다고 가정.
                // @SuppressLint("MissingPermission")이 클래스 레벨에 있으므로 Lint는 경고하지 않음.
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: SecurityException){
                Log.e(TAG_BLE, "cancelDiscovery failed due to SecurityException. Ensure permissions are granted.", e)
            }


            mmSocket?.let { socket ->
                try {
                    Log.i(TAG_BLE, "ConnectThread: Connecting to socket...")
                    socket.connect() // 연결 시도 (Blocking call)
                    Log.i(TAG_BLE, "ConnectThread: Connection successful.")
                    manageConnectedSocket(socket) // 연결된 소켓 관리
                } catch (e: Exception) { // IOException 등 다양한 예외 처리
                    Log.e(TAG_BLE, "ConnectThread: Connection failed", e)
                    lifecycleScope.launch { connectionFailed("연결 실패: ${e.message}") }
                    try {
                        socket.close() // 실패 시 소켓 닫기
                    } catch (ce: IOException) {
                        Log.e(TAG_BLE, "ConnectThread: Socket close failed after connection error", ce)
                    }
                }
            }
            connectThread = null // 스레드 종료 시 현재 스레드 참조 제거
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
            lifecycleScope.launch { connectionLost() } // UI 업데이트 및 상태 변경
            null
        }
        private val mmOutStream: OutputStream? = try {
            mmSocket.outputStream
        } catch (e: IOException) {
            Log.e(TAG_BLE, "ConnectedThread: Error getting output stream", e)
            lifecycleScope.launch { connectionLost() } // UI 업데이트 및 상태 변경
            null
        }
        private var isRunning = true // 스레드 실행 상태 플래그

        override fun run() {
            Log.i(TAG_BLE, "ConnectedThread: Listening for incoming data...")
            val buffer = ByteArray(1024) // 수신 버퍼
            val messageBuilder = StringBuilder() // 여러 조각으로 나뉘어 올 수 있는 메시지 조합용

            while (isRunning) {
                if (mmInStream == null) { // 입력 스트림이 없다면 스레드 종료
                    lifecycleScope.launch { connectionLost() }
                    break
                }
                try {
                    val numBytes = mmInStream.read(buffer) // 데이터 읽기 (Blocking call)
                    if (numBytes > 0) {
                        val readChunk = String(buffer, 0, numBytes, Charsets.UTF_8) // UTF-8로 디코딩
                        messageBuilder.append(readChunk)

                        // 개행 문자를 기준으로 메시지 분리 (또는 다른 종료 문자 사용 가능)
                        var newlineIndex = messageBuilder.indexOf('\n')
                        while (newlineIndex >= 0) {
                            val line = messageBuilder.substring(0, newlineIndex)
                            // 캐리지 리턴 문자(\r)가 있다면 제거
                            val finalMessage = if (line.endsWith('\r')) line.dropLast(1) else line
                            Log.d(TAG_BLE, "Received complete line: '$finalMessage'")
                            processBleMessage(finalMessage) // 완전한 메시지 처리
                            messageBuilder.delete(0, newlineIndex + 1) // 처리된 부분 삭제
                            newlineIndex = messageBuilder.indexOf('\n') // 다음 개행 문자 검색
                        }
                    } else if (numBytes == -1) { // 스트림의 끝에 도달 (연결 끊김)
                        Log.w(TAG_BLE, "ConnectedThread: End of stream reached. Connection closed by peer.")
                        isRunning = false // 루프 종료 플래그 설정
                        lifecycleScope.launch { connectionLost() }
                    }
                } catch (e: IOException) {
                    Log.w(TAG_BLE, "ConnectedThread: Read failed, disconnecting.", e)
                    isRunning = false // 예외 발생 시 루프 종료
                    lifecycleScope.launch { connectionLost() } // 연결 끊김 처리
                }
            }
            Log.i(TAG_BLE, "ConnectedThread: Finished.")
            // 스레드 종료 시점 (isRunning이 false가 되거나 루프 탈출)
            if (this@CommunicationService.connectedThread == this) { // 자기 자신이 현재 활성 스레드일 때만 정리
                this@CommunicationService.connectedThread = null
            }
        }

        fun write(bytes: ByteArray) {
            if (mmOutStream == null) { // 출력 스트림이 없다면 전송 불가
                lifecycleScope.launch { connectionLost() }
                return
            }
            try {
                mmOutStream.write(bytes)
                mmOutStream.flush() // 버퍼 비우기 (중요)
                Log.d(TAG_BLE, "Data sent: ${String(bytes, Charsets.UTF_8)}")
                updateBleDataLog("-> ${String(bytes, Charsets.UTF_8)} (BLE)") // 보낸 데이터 로그 기록
            } catch (e: IOException) {
                Log.e(TAG_BLE, "ConnectedThread: Write Error", e)
                lifecycleScope.launch { connectionLost() } // 쓰기 오류 시 연결 끊김 처리
            }
        }

        fun cancel() {
            isRunning = false // 루프 종료 플래그 설정
            try {
                mmSocket.close() // 소켓 닫기
                Log.d(TAG_BLE, "ConnectedThread: Socket closed on cancel.")
            } catch (e: IOException) {
                Log.e(TAG_BLE, "ConnectedThread: Socket close error on cancel", e)
            }
        }
    }


    // --- Wi-Fi Direct BroadcastReceiver (Inner Class) ---
    inner class WiFiDirectBroadcastReceiver(
        private val manager: WifiP2pManager,
        private val channel: WifiP2pManager.Channel, // Non-nullable로 유지 (초기화 시 null 체크 후 할당)
        private val service: CommunicationService // CommunicationService 인스턴스 참조
    ) : BroadcastReceiver() {

        @SuppressLint("MissingPermission") // BroadcastReceiver 내에서 권한 필요한 API 호출 시 사용될 수 있음
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
                    if (isEnabled) {
                        // service.discoverWifiDirectPeers() // 상태 변경 시 자동 검색은 상황에 따라 조절
                    } else {
                        service.disconnectWifiDirect(notifyUi = true) // 비활성화 시 연결 완전 해제
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG_WIFI_DIRECT, "P2P peers changed.")
                    // 권한 확인은 service.hasWifiDirectPermissions()에서 수행됨.
                    // manager.requestPeers는 권한이 필요한 API이므로, 호출 전에 확인 필요.
                    // WiFiDirectBroadcastReceiver 클래스 레벨에 @SuppressLint("MissingPermission")을 추가하거나,
                    // 호출 지점마다 권한 확인 후 호출 또는 try-catch SecurityException.
                    // 여기서는 서비스 내부 메서드가 권한을 확인한다고 가정.
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
                        // NetworkInfo는 참고용으로만 로깅하고, 주된 판단은 WifiP2pInfo.groupFormed로.
                        @Suppress("DEPRECATION") // NetworkInfo 클래스 자체가 deprecated
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
                    // 필요시 이 기기의 상태를 _wifiDirectUiState에 업데이트
                }
            }
        }
    }

    // WifiP2pDevice.status 값을 문자열로 변환하는 헬퍼 함수
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
        private val threadScope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // 스레드 자체 코루틴 스코프

        // 소켓 연결 상태 확인용 프로퍼티 (외부에서 직접 접근은 불가, 내부 로직용)
        val isSocketConnected: Boolean
            get() = clientSocket?.isConnected == true && clientSocket?.isClosed == false


        override fun run() {
            Log.d(TAG_WIFI_DIRECT, "WifiDirectDataTransferThread started. IsGO: ${p2pInfo.isGroupOwner}")
            try {
                if (p2pInfo.isGroupOwner) {
                    serverSocket = ServerSocket(WIFI_DIRECT_SERVER_PORT)
                    Log.d(TAG_WIFI_DIRECT, "GO: ServerSocket opened on port $WIFI_DIRECT_SERVER_PORT. Waiting for client...")
                    _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 클라이언트 연결 대기 중...") }
                    clientSocket = serverSocket!!.accept() // 블로킹 호출
                    Log.i(TAG_WIFI_DIRECT, "GO: Client connected: ${clientSocket?.inetAddress?.hostAddress}")
                    _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 클라이언트 연결됨", connectedDeviceName = "클라이언트 (${clientSocket?.inetAddress?.hostAddress})") }
                } else { // 클라이언트
                    clientSocket = Socket()
                    val hostAddress = p2pInfo.groupOwnerAddress.hostAddress
                    Log.d(TAG_WIFI_DIRECT, "Client: Connecting to GO at $hostAddress:$WIFI_DIRECT_SERVER_PORT...")
                    _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 그룹 소유자에게 연결 중...") }
                    clientSocket!!.connect(InetSocketAddress(hostAddress, WIFI_DIRECT_SERVER_PORT), SOCKET_TIMEOUT)
                    Log.i(TAG_WIFI_DIRECT, "Client: Connected to GO.")
                    _wifiDirectUiState.update { it.copy(statusText = "Wi-Fi Direct: 그룹 소유자에게 연결됨") }
                }
                updateNotificationCombined() // 연결 상태 알림 업데이트

                inputStream = clientSocket!!.getInputStream()
                outputStream = clientSocket!!.getOutputStream()
                val reader = BufferedReader(InputStreamReader(inputStream!!))

                // 데이터 수신 루프
                while (currentThread().isAlive && !currentThread().isInterrupted && clientSocket?.isConnected == true && !clientSocket!!.isClosed) {
                    try {
                        val line = reader.readLine() // 개행 문자로 구분된 메시지 읽기
                        if (line != null) {
                            Log.i(TAG_WIFI_DIRECT, "DataTransferThread Received: $line")
                            addWifiDirectLog("<- $line (Wi-Fi Direct)")
                            threadScope.launch { CommunicationHub.emitWifiDirectToTcp(line) }
                        } else {
                            Log.w(TAG_WIFI_DIRECT, "DataTransferThread: readLine returned null. Peer likely closed connection.")
                            break // 상대방이 연결을 닫음
                        }
                    } catch (e: IOException) {
                        if (currentThread().isInterrupted || clientSocket?.isClosed == true || clientSocket?.isConnected == false) {
                            Log.d(TAG_WIFI_DIRECT, "DataTransferThread: Socket closed or thread interrupted during read. ${e.message}")
                        } else {
                            Log.e(TAG_WIFI_DIRECT, "DataTransferThread: IOException during read", e)
                        }
                        break // 읽기 오류 시 루프 종료
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG_WIFI_DIRECT, "DataTransferThread: IOException during socket setup or accept", e)
                _wifiDirectUiState.update { it.copy(errorMessage = "Wi-Fi Direct 통신 오류: ${e.message}") }
            } catch (e: Exception) { // 예상치 못한 다른 예외 처리
                Log.e(TAG_WIFI_DIRECT, "DataTransferThread: Unexpected exception", e)
                _wifiDirectUiState.update { it.copy(errorMessage = "Wi-Fi Direct 예외: ${e.message}") }
            } finally {
                Log.d(TAG_WIFI_DIRECT, "DataTransferThread finishing.")
                cancelInternals() // 스레드 및 리소스 정리
            }
        }

        fun write(message: String) {
            if (outputStream != null && clientSocket?.isConnected == true && !clientSocket!!.isOutputShutdown) {
                threadScope.launch { // 코루틴 내에서 IO 작업 수행 (네트워크 작업이므로)
                    try {
                        outputStream?.write((message + "\n").toByteArray(Charsets.UTF_8)) // 개행 문자 추가하여 메시지 구분
                        outputStream?.flush() // 버퍼 강제 비우기
                        Log.i(TAG_WIFI_DIRECT, "DataTransferThread Sent: $message")
                        addWifiDirectLog("-> $message (Wi-Fi Direct)")
                    } catch (e: IOException) {
                        Log.e(TAG_WIFI_DIRECT, "DataTransferThread: IOException during write", e)
                        _wifiDirectUiState.update { it.copy(errorMessage = "Wi-Fi Direct 메시지 전송 실패: ${e.message}") }
                        // 쓰기 오류 시 연결 상태가 불안정할 수 있으므로 스레드 종료 또는 재연결 로직 고려
                        cancelInternals() // 쓰기 오류 시 스레드 내부 정리
                    }
                }
            } else {
                Log.w(TAG_WIFI_DIRECT, "DataTransferThread: Cannot write, outputStream is null or socket not connected/output shutdown.")
                _wifiDirectUiState.update { it.copy(errorMessage = "Wi-Fi Direct 메시지 전송 불가: 연결되지 않음") }
            }
        }

        // 스레드 외부에서 호출하기 위한 cancel
        fun cancel() {
            Log.d(TAG_WIFI_DIRECT, "DataTransferThread public cancel called.")
            interrupt() // 스레드 인터럽트 요청
            cancelInternals() // 내부 리소스 정리
        }

        // 실제 리소스 정리 로직
        private fun cancelInternals() {
            Log.d(TAG_WIFI_DIRECT, "DataTransferThread cancelInternals called.")
            threadScope.cancel() // 코루틴 스코프 취소
            try { inputStream?.close() } catch (e: IOException) { Log.e(TAG_WIFI_DIRECT, "Error closing WD input stream", e) }
            try { outputStream?.close() } catch (e: IOException) { Log.e(TAG_WIFI_DIRECT, "Error closing WD output stream", e) }
            try { clientSocket?.close() } catch (e: IOException) { Log.e(TAG_WIFI_DIRECT, "Error closing WD client socket", e) }
            try { serverSocket?.close() } catch (e: IOException) { Log.e(TAG_WIFI_DIRECT, "Error closing WD server socket", e) }
            inputStream = null
            outputStream = null
            clientSocket = null
            serverSocket = null

            // 서비스의 dataTransferThread 참조가 현재 인스턴스와 같을 때만 null로 설정
            if (this@CommunicationService.wifiDirectDataTransferThread == this) {
                this@CommunicationService.wifiDirectDataTransferThread = null
            }

            // 연결 종료 상태를 UI에 반영
            // 컴파일러가 이 조건이 항상 false라고 경고할 수 있으나 (line 682 경고의 원인),
            // 다양한 호출 경로를 고려하여 안전장치로 유지.
            if (_wifiDirectUiState.value.connectedDeviceName != null) {
                _wifiDirectUiState.update {
                    if (it.connectedDeviceName != null) { // UI가 여전히 연결된 것으로 표시하고 있다면
                        it.copy(
                            statusText = "Wi-Fi Direct: 연결 종료됨",
                            connectedDeviceName = null,
                            connectionInfo = null,
                            isGroupOwner = false,
                            groupOwnerAddress = null,
                            // 기존 에러 메시지가 없다면, 통신 채널 닫힘 메시지 설정
                            errorMessage = it.errorMessage ?: "통신 채널이 닫혔습니다."
                        )
                    } else it // 이미 UI가 업데이트 되었다면 그대로 둠
                }
                updateNotificationCombined()
            }
            Log.d(TAG_WIFI_DIRECT, "DataTransferThread resources cleaned up.")
        }
    }
}
