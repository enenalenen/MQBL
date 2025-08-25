package com.example.mqbl.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 앱 설정을 SharedPreferences에 저장하고 관리하는 클래스.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isBackgroundExecutionEnabled = MutableStateFlow(isBackgroundExecutionEnabled())
    val isBackgroundExecutionEnabledFlow: StateFlow<Boolean> = _isBackgroundExecutionEnabled.asStateFlow()

    // --- ▼▼▼ 감지 단어 저장을 위한 코드 추가 ▼▼▼ ---
    private val _customKeywords = MutableStateFlow(getCustomKeywords())
    val customKeywordsFlow: StateFlow<String> = _customKeywords.asStateFlow()

    /**
     * 저장된 감지 단어를 불러옵니다. 기본값은 빈 문자열입니다.
     * 단어들은 쉼표(,)로 구분됩니다.
     */
    fun getCustomKeywords(): String {
        return prefs.getString(KEY_CUSTOM_KEYWORDS, "") ?: ""
    }

    /**
     * 감지 단어를 저장합니다.
     */
    fun setCustomKeywords(keywords: String) {
        prefs.edit().putString(KEY_CUSTOM_KEYWORDS, keywords).apply()
        _customKeywords.value = keywords
    }
    // --- ▲▲▲ 감지 단어 저장을 위한 코드 추가 끝 ▲▲▲ ---

    fun isBackgroundExecutionEnabled(): Boolean {
        return prefs.getBoolean(KEY_BACKGROUND_EXECUTION, true)
    }

    fun setBackgroundExecution(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_EXECUTION, enabled).apply()
        _isBackgroundExecutionEnabled.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "mqbl_settings"
        private const val KEY_BACKGROUND_EXECUTION = "background_execution_enabled"
        // --- ▼▼▼ 감지 단어 저장을 위한 키 추가 ▼▼▼ ---
        private const val KEY_CUSTOM_KEYWORDS = "custom_detection_keywords"
        // --- ▲▲▲ 감지 단어 저장을 위한 키 추가 끝 ▲▲▲ ---
    }
}
