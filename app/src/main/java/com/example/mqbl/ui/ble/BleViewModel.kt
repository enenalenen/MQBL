package com.example.mqbl.ui.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mqbl.service.CommunicationService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "BleViewModel"

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val _binder = MutableStateFlow<CommunicationService.LocalBinder?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "CommunicationService Connected")
            _binder.value = service as? CommunicationService.LocalBinder
            _binder.value?.getService()?.refreshBleState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "CommunicationService Disconnected unexpectedly")
            _binder.value = null
        }
    }

    private val defaultBleUiState = BleUiState(status = "서비스 연결 대기 중...")
    private val defaultDeviceList = emptyList<BluetoothDevice>()
    private val defaultDetectionLog = emptyList<DetectionEvent>()
    private val defaultCustomSoundLog = emptyList<CustomSoundEvent>() // 기본값 추가

    val uiState: StateFlow<BleUiState> = _binder.flatMapLatest { binder ->
        binder?.getBleUiStateFlow()
            ?: flowOf(defaultBleUiState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultBleUiState
    )

    val bondedDevices: StateFlow<List<BluetoothDevice>> = _binder.flatMapLatest { binder ->
        binder?.getBondedDevicesFlow()
            ?: flowOf(defaultDeviceList)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultDeviceList
    )

    val scannedDevices: StateFlow<List<BluetoothDevice>> = _binder.flatMapLatest { binder ->
        binder?.getScannedDevicesFlow() ?: flowOf(defaultDeviceList)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultDeviceList
    )

    val detectionEventLog: StateFlow<List<DetectionEvent>> = _binder.flatMapLatest { binder ->
        binder?.getDetectionLogFlow()
            ?: flowOf(defaultDetectionLog)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultDetectionLog
    )

    // --- ▼▼▼ '감지된 음성' 로그를 위한 StateFlow 추가 ▼▼▼ ---
    val customSoundEventLog: StateFlow<List<CustomSoundEvent>> = _binder.flatMapLatest { binder ->
        binder?.getCustomSoundEventLogFlow()
            ?: flowOf(defaultCustomSoundLog)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultCustomSoundLog
    )
    // --- ▲▲▲ '감지된 음성' 로그를 위한 StateFlow 추가 끝 ▲▲▲ ---

    private val _permissionRequestEvent = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val permissionRequestEvent: SharedFlow<Unit> = _permissionRequestEvent.asSharedFlow()

    init {
        Log.d(TAG, "ViewModel Init: Binding to CommunicationService...")
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        checkOrRequestPermissions()
    }

    fun checkOrRequestPermissions() {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Permissions missing. Emitting permission request event.")
            viewModelScope.launch { _permissionRequestEvent.emit(Unit) }
        } else {
            Log.d(TAG, "Permissions check OK. Requesting service state refresh.")
            _binder.value?.getService()?.refreshBleState()
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "UI Action: Request connect to ${device.address}")
        val service = _binder.value?.getService()
        if (service != null) {
            service.requestBleConnect(device)
        } else {
            Log.e(TAG, "Cannot connect: Service not bound yet.")
        }
    }

    fun disconnect() {
        Log.i(TAG, "UI Action: Request disconnect")
        _binder.value?.getService()?.requestBleDisconnect()
    }

    fun sendValue(value: Int) {
        Log.d(TAG, "UI Action: Request send value $value")
        val service = _binder.value?.getService()
        if (service != null) {
            service.sendBleValue(value)
        } else {
            Log.e(TAG, "Cannot send value: Service not bound yet.")
        }
    }

    fun sendBleCommand(command: String) {
        Log.d(TAG, "UI Action: Request send command '$command'")
        _binder.value?.getService()?.sendBleString(command) ?: run {
            Log.e(TAG, "Cannot send command: Service not bound yet.")
        }
    }

    fun startScan() {
        Log.i(TAG, "UI Action: Request start BLE scan")
        _binder.value?.getService()?.startBleScan()
    }

    fun stopScan() {
        Log.i(TAG, "UI Action: Request stop BLE scan")
        _binder.value?.getService()?.stopBleScan()
    }

    fun pairWithDevice(device: BluetoothDevice) {
        Log.i(TAG, "UI Action: Request pair with ${device.address}")
        _binder.value?.getService()?.pairDevice(device)
    }

    private fun hasRequiredPermissions(): Boolean {
        val context = getApplication<Application>().applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "ViewModel Cleared: Unbinding from CommunicationService...")
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Attempted to unbind ServiceConnection that was not registered.", e)
        }
        _binder.value = null
    }
}
