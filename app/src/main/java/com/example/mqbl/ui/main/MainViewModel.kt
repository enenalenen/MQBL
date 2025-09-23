package com.example.mqbl.ui.main

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mqbl.service.CommunicationService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

private const val TAG = "MainViewModel"

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _binder = MutableStateFlow<CommunicationService.LocalBinder?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "CommunicationService Connected")
            _binder.value = service as? CommunicationService.LocalBinder
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "CommunicationService Disconnected unexpectedly")
            _binder.value = null
        }
    }

    private val defaultMainUiState = MainUiState(status = "서비스 연결 대기 중...")
    private val defaultDetectionLog = emptyList<DetectionEvent>()
    private val defaultCustomSoundLog = emptyList<CustomSoundEvent>()

    val uiState: StateFlow<MainUiState> = _binder.flatMapLatest { binder ->
        binder?.getMainUiStateFlow() ?: flowOf(defaultMainUiState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultMainUiState
    )

    val detectionEventLog: StateFlow<List<DetectionEvent>> = _binder.flatMapLatest { binder ->
        binder?.getDetectionLogFlow() ?: flowOf(defaultDetectionLog)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultDetectionLog
    )

    val customSoundEventLog: StateFlow<List<CustomSoundEvent>> = _binder.flatMapLatest { binder ->
        binder?.getCustomSoundEventLogFlow() ?: flowOf(defaultCustomSoundLog)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = defaultCustomSoundLog
    )


    init {
        Log.d(TAG, "ViewModel Init: Binding to CommunicationService...")
        Intent(application, CommunicationService::class.java).also { intent ->
            application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
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