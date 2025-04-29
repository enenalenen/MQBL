package com.example.mqbl.ui.ble

import android.Manifest // 권한 이름 사용
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers // 명시적 Dispatchers 사용 제거 (viewModelScope 기본 활용)
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.channels.BufferOverflow // SharedFlow 설정용
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow


// 상수 정의
private const val TAG = "BleViewModel"
private val BT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID
private const val MAX_DETECTION_LOG_SIZE = 10 // 감지 로그 최대 크기

// ViewModel 정의 (Application Context 사용을 위해 AndroidViewModel 상속)
class BleViewModel(application: Application) : AndroidViewModel(application) {

    // Bluetooth 관련 시스템 서비스 및 어댑터 가져오기
    private val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    // 연결 및 통신 스레드 참조 변수
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    // 시간 포맷터
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // --- UI 상태 관리를 위한 StateFlow 정의 ---
    private val _uiState = MutableStateFlow(BleUiState()) // 내부 변경 가능 StateFlow
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow() // 외부 공개용 읽기 전용 StateFlow

    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()

    private val _detectionEventLog = MutableStateFlow<List<DetectionEvent>>(emptyList())
    val detectionEventLog: StateFlow<List<DetectionEvent>> = _detectionEventLog.asStateFlow()
    // --- StateFlow 정의 끝 ---

    // --- 권한 요청 트리거를 위한 SharedFlow ---
    private val _permissionRequestEvent = MutableSharedFlow<Unit>(
        replay = 0, // 놓친 이벤트는 다시 보내지 않음
        extraBufferCapacity = 1, // 버퍼 1개 허용
        onBufferOverflow = BufferOverflow.DROP_OLDEST // 버퍼 초과 시 이전 이벤트 버림
    )
    val permissionRequestEvent: SharedFlow<Unit> = _permissionRequestEvent.asSharedFlow() // 외부 공개용

    // --- ViewModel 초기화 로직 ---
    init {
        if (bluetoothAdapter == null) {
            _uiState.update { it.copy(status = "상태: 블루투스 미지원", isBluetoothSupported = false) }
        } else {
            // ViewModel 생성 시 권한 확인 및 기기 목록 로드 시도
            // (실제 권한 *요청*은 UI 계층(Activity/Fragment)에서 이루어져야 함)
            checkOrRequestPermissions()
            // 블루투스 활성화 상태 확인
            if (bluetoothAdapter?.isEnabled == false) {
                _uiState.update { it.copy(status = "상태: 블루투스 비활성화됨") }
                // TODO: UI에 블루투스 활성화 요청 트리거 상태/이벤트 전달 고려
            }
        }
    }

    // --- 공개 함수 (UI에서 호출) ---

    /** 권한을 확인하고, 없으면 Activity에 요청하도록 이벤트를 발생시킵니다. */
    fun checkOrRequestPermissions() {
        if (!hasRequiredPermissions()) {
            // 상태 업데이트
            _uiState.update { it.copy(
                status = "상태: 필수 권한 없음",
                connectError = "블루투스 연결/검색 권한이 필요합니다." // API 31+ 기준 메시지
            )}
            // Activity(UI)에 권한 요청이 필요함을 알리는 이벤트 발생
            viewModelScope.launch {
                Log.d(TAG, "Emitting permission request event.")
                _permissionRequestEvent.emit(Unit)
            }
        } else {
            // 권한이 이미 있다면 로그 기록 및 기기 로드 시도
            Log.d(TAG, "Permissions already granted.")
            if (bluetoothAdapter?.isEnabled == true) {
                loadBondedDevices()
            } else {
                // 권한은 있지만 BT가 꺼진 경우 상태 업데이트
                if (!_uiState.value.status.contains("연결됨")) { // 이미 연결된 상태면 유지
                    _uiState.update { it.copy(status = "상태: 블루투스 비활성화됨") }
                }
            }
        }
    }

    /** Activity에서 권한 요청 결과를 받았을 때 호출됩니다. */
    @SuppressLint("MissingPermission")
    fun onPermissionsResult(grantedPermissions: Map<String, Boolean>) {
        Log.d(TAG, "Received permissions result: $grantedPermissions")
        // 필요한 권한이 모두 승인되었는지 다시 확인
        if (hasRequiredPermissions()) {
            Log.i(TAG, "Required permissions GRANTED after request.")
            _uiState.update { it.copy(connectError = null) } // 권한 오류 메시지 제거
            // 블루투스가 켜져 있으면 기기 목록 로드
            if (bluetoothAdapter?.isEnabled == true) {
                loadBondedDevices()
            } else {
                _uiState.update { it.copy(status = "상태: 블루투스 비활성화됨") }
            }
        } else {
            Log.w(TAG, "Required permissions DENIED after request.")
            _uiState.update { it.copy(
                status = "상태: 필수 권한 거부됨",
                connectError = "앱 기능 사용을 위해 블루투스 권한이 반드시 필요합니다. 앱 설정에서 권한을 허용해주세요." // 사용자 안내 강화
            )}
            // TODO: 앱 설정 화면으로 안내하는 버튼 등을 UI에 표시하도록 상태 추가 고려
        }
    }


    @SuppressLint("MissingPermission")
    private fun loadBondedDevices() {
        // 로드 직전 권한 및 BT 상태 재확인
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Permissions check failed before loading bonded devices!")
            _uiState.update { it.copy(status = "상태: 필수 권한 없음", connectError = "블루투스 권한이 필요합니다.") }
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth disabled before loading bonded devices!")
            _uiState.update { it.copy(status = "상태: 블루투스 비활성화됨") }
            return
        }

        Log.d(TAG,"Loading bonded devices...")
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        val deviceList = pairedDevices?.toList() ?: emptyList()
        _bondedDevices.value = deviceList

        // Update status only if not connecting/connected
        _uiState.update { currentState ->
            if (currentState.connectedDeviceName == null && !currentState.isConnecting) {
                if (deviceList.isEmpty()) {
                    currentState.copy(status = "상태: 페어링된 기기 없음")
                } else {
                    currentState.copy(status = "상태: 기기 목록 로드됨")
                }
            } else {
                currentState // Keep current status if already connected/connecting
            }
        }
        Log.d(TAG, "Bonded devices loaded: ${deviceList.size}")
    }


    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        // --- 연결 시도 전 권한 확인 ---
        if (!hasRequiredPermissions()) {
            _uiState.update { it.copy(status = "상태: 연결 권한 없음", connectError = "연결 전 권한 승인이 필요합니다.") }
            // 권한 요청 이벤트 다시 발생시키기
            checkOrRequestPermissions()
            return
        }
        // --- 블루투스 활성화 확인 ---
        if (bluetoothAdapter?.isEnabled != true) {
            _uiState.update { it.copy(status = "상태: 블루투스 비활성화됨", connectError = "연결 전 블루투스 활성화가 필요합니다.") }
            // TODO: UI에 활성화 요청 이벤트 전달
            return
        }
        // --- 연결 상태 확인 ---
        if (_uiState.value.isConnecting || _uiState.value.connectedDeviceName != null) {
            Log.w(TAG, "Connection attempt ignored: Already connecting or connected.")
            return
        }

        // --- 연결 시도 로직 (기존과 유사) ---
        val deviceName = try { device.name ?: "알 수 없는 기기" } catch (e: SecurityException) { "알 수 없는 이름" }
        Log.d(TAG, "Attempting to connect to ${device.address}")
        _uiState.update { it.copy(status = "상태: ${deviceName}에 연결 중...", isConnecting = true, connectError = null) }
        disconnect() // 이전 연결 정리
        connectThread = ConnectThread(device)
        connectThread?.start()
    }

    // --- Helper Functions ---
    /** 필요한 권한들을 확인합니다. (API 레벨에 따라 다름) */
    private fun hasRequiredPermissions(): Boolean {
        val context = getApplication<Application>().applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (API 31) 이상: CONNECT 와 SCAN 모두 필요
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 11 (API 30) 이하: Manifest에 선언된 BLUETOOTH (+ ADMIN) 권한으로 충분함.
            // 단, 주변 기기 스캔을 하려면 ACCESS_FINE_LOCATION 런타임 권한이 필요함.
            // 현재는 스캔 기능이 없으므로 기본 권한만 있다고 가정.
            true // Manifest 권한은 설치 시 부여되므로 런타임 체크는 true로 간주 (위치 권한 제외)
        }
    }

    /** 현재 연결 해제 */
    fun disconnect() {
        Log.d(TAG, "Disconnecting request.")
        connectThread?.cancel() // 연결 시도 중이었다면 취소
        connectThread = null
        connectedThread?.cancel() // 연결된 상태였다면 취소
        connectedThread = null

        // 이미 끊긴 상태가 아니라면 상태 업데이트
        if (_uiState.value.connectedDeviceName != null || _uiState.value.isConnecting) {
            _uiState.update {
                it.copy(
                    status = "상태: 연결 해제됨",
                    connectedDeviceName = null,
                    isConnecting = false,
                    connectError = null
                )
            }
        }
    }

    /** 지정된 값을 문자열로 변환하여 연결된 기기로 전송 */
    fun sendValue(value: Int) {
        if (connectedThread == null) {
            Log.w(TAG, "Cannot send data, not connected.")
            viewModelScope.launch { _uiState.update { it.copy(connectError = "메시지 전송 실패: 연결 안됨") } }
            return
        }
        val message = value.toString()
        connectedThread?.write(message.toByteArray())
    }


    /** 소켓 연결 성공 시 호출되는 콜백 */
    @SuppressLint("MissingPermission")
    private fun manageConnectedSocket(socket: BluetoothSocket) {
        val deviceName = try { socket.remoteDevice.name ?: "알 수 없는 기기" } catch (e: SecurityException) { "알 수 없는 기기" }
        Log.d(TAG, "Connection successful with $deviceName.")

        // viewModelScope를 사용하여 메인 스레드에서 상태 업데이트 보장
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = "상태: ${deviceName}에 연결됨",
                    connectedDeviceName = deviceName,
                    isConnecting = false,
                    connectError = null
                )
            }
        }

        // 이전 통신 스레드 정리 후 새 스레드 시작
        connectedThread?.cancel()
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
    }

    /** 소켓 연결 실패 시 호출되는 콜백 */
    private fun connectionFailed(errorMsg: String = "기기 연결에 실패했습니다.") {
        Log.e(TAG, "Connection failed: $errorMsg")
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = "상태: 연결 실패",
                    connectedDeviceName = null,
                    isConnecting = false,
                    connectError = errorMsg // 구체적인 오류 메시지 전달
                )
            }
        }
        // 스레드 참조 정리
        connectThread = null // 연결 시도 스레드는 여기서 종료됨
        connectedThread?.cancel()
        connectedThread = null
    }

    /** 기기 연결 끊김 시 호출되는 콜백 */
    private fun connectionLost() {
        Log.w(TAG, "Connection lost.")
        // 이미 끊긴 상태가 아니라면 상태 업데이트
        if (_uiState.value.connectedDeviceName != null || _uiState.value.status.contains("연결됨")) {
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        status = "상태: 연결 끊김",
                        connectedDeviceName = null,
                        isConnecting = false,
                        connectError = "기기와의 연결이 끊어졌습니다."
                    )
                }
            }
        }
        // 통신 스레드 참조 정리
        connectedThread = null
    }

    /** 수신된 메시지 처리 및 로그 업데이트 */
    private fun processReceivedMessage(message: String) {
        updateDataLog("<- $message") // 전체 로그에 추가

        val trimmedMessage = message.trim()
        var eventDescription: String? = null
        when (trimmedMessage.lowercase()) { // 소문자로 변환하여 비교
            "siren" -> eventDescription = "사이렌 감지됨"
            "horn" -> eventDescription = "경적 감지됨"
            "boom" -> eventDescription = "폭발음 감지됨"
        }

        // 특정 이벤트 감지 시 로그 추가
        if (eventDescription != null) {
            addDetectionEvent(eventDescription)
        }
    }

    /** 데이터 로그 StateFlow 업데이트 (송/수신 데이터 추가) */
    private fun updateDataLog(logEntry: String) {
        viewModelScope.launch {
            val currentLog = _uiState.value.receivedDataLog
            // 로그 최대 길이 제한 (예: 마지막 1000자)
            val newLog = "$currentLog\n$logEntry".takeLast(1000)
            _uiState.update { it.copy(receivedDataLog = newLog) }
        }
    }

    /** 감지 이벤트 로그 StateFlow 업데이트 */
    private fun addDetectionEvent(description: String) {
        viewModelScope.launch {
            val currentTime = timeFormatter.format(Date())
            val newEvent = DetectionEvent(description = description, timestamp = currentTime)

            // 현재 리스트에 새 이벤트를 맨 앞에 추가하고 최대 크기 제한
            val currentList = _detectionEventLog.value
            val updatedList = (listOf(newEvent) + currentList).take(MAX_DETECTION_LOG_SIZE)
            _detectionEventLog.value = updatedList

            Log.d(TAG, "Event Added: $description at $currentTime. Count: ${updatedList.size}")
        }
    }


    // --- ViewModel 소멸 시 자원 정리 ---
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared. Disconnecting and cleaning up resources.")
        disconnect() // 연결 해제 및 스레드 정리
    }


    // --- Bluetooth 통신 스레드 (Inner Class) ---
    // (코드 가독성을 위해 별도 파일로 분리하는 것을 권장)

    @SuppressLint("MissingPermission")
    private inner class ConnectThread(device: BluetoothDevice) : Thread() {
        // 스레드 안전성을 위해 lazy 초기화 사용 및 실패 처리 강화
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            if (!hasRequiredPermissions()) { // 시작 전 권한 확인하지만 여기서도 방어
                Log.e(TAG, "ConnectThread: Permission missing for socket creation.")
                viewModelScope.launch { connectionFailed("소켓 생성 권한 오류") }
                return@lazy null
            }
            try {
                // RFCOMM 소켓 생성
                device.createRfcommSocketToServiceRecord(BT_UUID)
            } catch (e: IOException) {
                Log.e(TAG, "ConnectThread: Socket create failed", e)
                viewModelScope.launch { connectionFailed("소켓 생성 실패: ${e.message}") }
                null // 실패 시 null 반환
            } catch (e: SecurityException) {
                Log.e(TAG, "ConnectThread: Socket create security error", e)
                viewModelScope.launch { connectionFailed("소켓 생성 권한 오류: ${e.message}") }
                null // 실패 시 null 반환
            }
        }

        override fun run() {
            // 소켓 생성 실패 시 즉시 종료
            if (mmSocket == null) {
                Log.e(TAG, "ConnectThread: Socket is null. Aborting.")
                // connectionFailed는 lazy 초기화 중 이미 호출되었을 것임
                connectThread = null // 스레드 참조 정리
                return
            }

            // 연결 시도 전 기기 검색 중지 (연결 속도 향상)
            bluetoothAdapter?.cancelDiscovery()

            mmSocket?.let { socket ->
                try {
                    Log.i(TAG, "ConnectThread: Connecting to socket...")
                    socket.connect() // 블로킹 호출
                    Log.i(TAG, "ConnectThread: Connection successful.")
                    // 연결 성공 시 소켓 관리 함수 호출
                    manageConnectedSocket(socket)
                } catch (connectException: Exception) { // IOException, SecurityException 등 모든 예외 처리
                    Log.e(TAG, "ConnectThread: Connection failed", connectException)
                    viewModelScope.launch { connectionFailed("연결 실패: ${connectException.message}") }
                    try {
                        socket.close() // 실패 시 소켓 닫기 시도
                    } catch (closeException: IOException) {
                        Log.e(TAG, "ConnectThread: Could not close socket on connection failure", closeException)
                    }
                }
            }
            // 스레드 종료 시 참조 정리
            connectThread = null
        }

        // 스레드 취소 (소켓 닫기)
        fun cancel() {
            try {
                mmSocket?.close()
                Log.d(TAG, "ConnectThread: Socket cancelled and closed.")
            } catch (e: IOException) {
                Log.e(TAG, "ConnectThread: Could not close socket on cancel", e)
            }
        }
    }


    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream? = try { mmSocket.inputStream } catch (e: IOException) { Log.e(TAG, "ConnectedThread: Error getting input stream", e); viewModelScope.launch { connectionLost() }; null }
        private val mmOutStream: OutputStream? = try { mmSocket.outputStream } catch (e: IOException) { Log.e(TAG, "ConnectedThread: Error getting output stream", e); viewModelScope.launch { connectionLost() }; null }
        private var isRunning = true // 스레드 실행 상태 플래그

        override fun run() {
            Log.i(TAG, "ConnectedThread: Started listening.")
            val buffer = ByteArray(1024)
            var numBytes: Int
            val messageBuilder = StringBuilder()

            while (isRunning) {
                if (mmInStream == null) { Log.e(TAG, "ConnectedThread: InputStream is null."); viewModelScope.launch { connectionLost() }; break; }
                try {
                    numBytes = mmInStream.read(buffer) // 블로킹 호출 (데이터 수신 대기)
                    val readChunk = String(buffer, 0, numBytes)
                    messageBuilder.append(readChunk)

                    // 줄바꿈 문자 기준으로 메시지 처리 (여러 줄 동시 수신 가능성 고려)
                    var newlineIndex = messageBuilder.indexOf('\n')
                    while (newlineIndex >= 0) {
                        val line = messageBuilder.substring(0, newlineIndex)
                        // 캐리지 리턴(\r) 제거
                        val finalMessage = if (line.endsWith('\r')) line.dropLast(1) else line

                        Log.d(TAG, "Received line: '$finalMessage'")
                        // viewModelScope 사용하여 메인 스레드에서 메시지 처리
                        viewModelScope.launch {
                            processReceivedMessage(finalMessage)
                        }

                        // 처리된 부분 삭제
                        messageBuilder.delete(0, newlineIndex + 1)
                        // 다음 줄바꿈 문자 검색
                        newlineIndex = messageBuilder.indexOf('\n')
                    }

                } catch (e: IOException) {
                    // 연결 끊김 (소켓 닫힘 등)
                    Log.w(TAG, "ConnectedThread: InputStream read failed, likely disconnected.", e)
                    viewModelScope.launch { connectionLost() }
                    isRunning = false // 루프 종료
                }
            }
            Log.i(TAG, "ConnectedThread: Finished.")
        }

        // 데이터 전송 함수
        fun write(bytes: ByteArray) {
            if (mmOutStream == null) { Log.e(TAG, "ConnectedThread: OutputStream is null."); viewModelScope.launch { connectionLost() }; return; }
            try {
                mmOutStream.write(bytes)
                Log.d(TAG, "Data sent: ${String(bytes)}")
                // viewModelScope 사용하여 메인 스레드에서 로그 업데이트
                viewModelScope.launch { updateDataLog("-> ${String(bytes)}") }
            } catch (e: IOException) {
                Log.e(TAG, "ConnectedThread: Error writing data", e)
                viewModelScope.launch { connectionLost() }
            }
        }

        // 스레드 및 소켓 정리 함수
        fun cancel() {
            isRunning = false // 루프 중단
            try {
                mmSocket.close()
                Log.d(TAG, "ConnectedThread: Socket closed.")
            } catch (e: IOException) {
                Log.e(TAG, "ConnectedThread: Could not close socket", e)
            }
        }
    } // ConnectedThread 끝

} // BleViewModel 끝