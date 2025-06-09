package com.example.mqbl.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 앱 설정을 SharedPreferences에 저장하고 관리하는 클래스.
 * 백그라운드 실행 여부 설정을 담당합니다.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isBackgroundExecutionEnabled = MutableStateFlow(isBackgroundExecutionEnabled())
    val isBackgroundExecutionEnabledFlow: StateFlow<Boolean> = _isBackgroundExecutionEnabled.asStateFlow()

    /**
     * 현재 백그라운드 실행이 활성화되어 있는지 확인합니다.
     * 기본값은 true (활성화) 입니다.
     */
    fun isBackgroundExecutionEnabled(): Boolean {
        return prefs.getBoolean(KEY_BACKGROUND_EXECUTION, true)
    }

    /**
     * 백그라운드 실행 설정을 변경합니다.
     */
    fun setBackgroundExecution(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_EXECUTION, enabled).apply()
        _isBackgroundExecutionEnabled.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "mqbl_settings"
        private const val KEY_BACKGROUND_EXECUTION = "background_execution_enabled"
    }
}
