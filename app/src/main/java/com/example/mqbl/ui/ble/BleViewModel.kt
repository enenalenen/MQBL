package com.example.mqbl.ui.ble

import android.Manifest // 권한 이름 (check용)
import android.app.Application
import android.bluetooth.BluetoothDevice // connectToDevice 파라미터 타입
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager // 권한 결과 처리
import android.os.Build // API 레벨 확인
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat // 권한 확인
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mqbl.service.CommunicationService // 생성한 서비스 import
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// BleUiState와 DetectionEvent가 이 파일 또는 import 가능한 다른 파일에 정의되어 있어야 합니다.
// 예시: import com.example.mqbl.ui.ble.BleUiState
// 예시: import com.example.mqbl.ui.ble.DetectionEvent

private const val TAG = "BleViewModel" // ViewModel 로그 태그

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BleViewModel(application: Application) : AndroidViewModel(application) {

    // --- Service Binder 상태 ---
    // Service와 연결되면 Binder 객체를 저장, 연결 끊기면 null
    private val _binder = MutableStateFlow<CommunicationService.LocalBinder?>(null)

    // --- Service Connection 콜백 정의 ---
    private val serviceConnection = object : ServiceConnection {
        // Service에 성공적으로 바인딩되었을 때 호출
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "CommunicationService Connected")
            // IBinder를 Service의 LocalBinder 타입으로 캐스팅하여 StateFlow 업데이트
            _binder.value = service as? CommunicationService.LocalBinder
            // 서비스 연결 시 초기 상태 로드를 요청 (선택 사항)
            _binder.value?.getService()?.refreshBleState()
        }

        // Service 연결이 예기치 않게 끊겼을 때 호출 (예: Service 크래시)
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "CommunicationService Disconnected unexpectedly")
            _binder.value = null
            // TODO: Service 연결 끊김에 대한 UI 상태 처리 (예: 에러 메시지 표시)
        }
    }

    // --- UI에 노출할 상태 (Service의 StateFlow로부터 파생) ---

    // 서비스가 연결되지 않았을 때 표시할 기본 상태 값
    private val defaultBleUiState = BleUiState(status = "서비스 연결 대기 중...")
    private val defaultDeviceList = emptyList<BluetoothDevice>()
    private val defaultDetectionLog = emptyList<DetectionEvent>()

    // binder StateFlow의 변경을 감지하여 Service의 StateFlow를 구독하거나 기본값을 사용
    val uiState: StateFlow<BleUiState> = _binder.flatMapLatest { binder ->
        // 수정된 부분: binder에서 직접 flow getter 호출
        binder?.getBleUiStateFlow()
            ?: flowOf(defaultBleUiState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultBleUiState
    )

    val bondedDevices: StateFlow<List<BluetoothDevice>> = _binder.flatMapLatest { binder ->
        // 수정된 부분: binder에서 직접 flow getter 호출
        binder?.getBondedDevicesFlow()
            ?: flowOf(defaultDeviceList)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultDeviceList
    )

    val detectionEventLog: StateFlow<List<DetectionEvent>> = _binder.flatMapLatest { binder ->
        // 수정된 부분: binder에서 직접 flow getter 호출
        binder?.getDetectionLogFlow()
            ?: flowOf(defaultDetectionLog)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultDetectionLog
    )

    // --- 권한 요청 이벤트 (ViewModel이 UI와 상호작용하기 위해 유지) ---
    private val _permissionRequestEvent = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val permissionRequestEvent: SharedFlow<Unit> = _permissionRequestEvent.asSharedFlow()

    // --- ViewModel 초기화 ---
    init {
        Log.d(TAG, "ViewModel Init: Binding to CommunicationService...")
        // Application Context를 사용하여 Service에 바인딩 시작
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        // 초기 권한 확인 (필요시 Activity에 요청 이벤트 발생)
        checkOrRequestPermissions()
    }

    // --- 공개 함수 (UI 액션을 Service에 위임) ---

    /** 권한 확인 후 필요시 요청 이벤트 발생 (Service 상태 새로고침도 요청) */
    fun checkOrRequestPermissions() {
        if (!hasRequiredPermissions()) { // ViewModel에서 빠르게 로컬 확인
            Log.w(TAG, "Permissions missing. Emitting permission request event.")
            viewModelScope.launch { _permissionRequestEvent.emit(Unit) } // Activity에 요청 알림
        } else {
            // 로컬에서 권한 확인되면, Service에 상태 새로고침 요청
            Log.d(TAG, "Permissions check OK. Requesting service state refresh.")
            _binder.value?.getService()?.refreshBleState()
        }
    }

    /** Service에 BLE 기기 연결 요청 */
    fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "UI Action: Request connect to ${device.address}")
        val service = _binder.value?.getService()
        if (service != null) {
            service.requestBleConnect(device) // Service의 함수 호출
        } else {
            Log.e(TAG, "Cannot connect: Service not bound yet.")
            // TODO: 사용자에게 서비스 연결 안됨 알림
        }
    }

    /** Service에 BLE 연결 해제 요청 */
    fun disconnect() {
        Log.i(TAG, "UI Action: Request disconnect")
        _binder.value?.getService()?.requestBleDisconnect()
    }

    /** Service에 BLE 데이터 전송 요청 */
    fun sendValue(value: Int) {
        Log.d(TAG, "UI Action: Request send value $value")
        val service = _binder.value?.getService()
        if (service != null) {
            service.sendBleValue(value) // Service의 함수 호출
        } else {
            Log.e(TAG, "Cannot send value: Service not bound yet.")
            // TODO: 사용자에게 서비스 연결 안됨 알림
        }
    }

    // --- 로컬 권한 확인 헬퍼 (ViewModel에서 요청 이벤트 발생 전 확인용) ---
    private fun hasRequiredPermissions(): Boolean {
        val context = getApplication<Application>().applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true // API 31 미만 기본 권한은 Manifest 처리 가정
        }
    }

    // --- ViewModel 소멸 시 Service 언바인딩 ---
    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "ViewModel Cleared: Unbinding from CommunicationService...")
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Attempted to unbind ServiceConnection that was not registered.", e)
        }
        _binder.value = null // Binder 참조 제거
    }
} // BleViewModel 끝