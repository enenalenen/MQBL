package com.example.mqbl.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isBackgroundExecutionEnabled = MutableStateFlow(isBackgroundExecutionEnabled())
    val isBackgroundExecutionEnabledFlow: StateFlow<Boolean> = _isBackgroundExecutionEnabled.asStateFlow()

    private val _customKeywords = MutableStateFlow(getCustomKeywords())
    val customKeywordsFlow: StateFlow<String> = _customKeywords.asStateFlow()

    private val _tcpServerIp = MutableStateFlow(getTcpServerIp())
    val tcpServerIpFlow: StateFlow<String> = _tcpServerIp.asStateFlow()

    private val _tcpServerPort = MutableStateFlow(getTcpServerPort())
    val tcpServerPortFlow: StateFlow<String> = _tcpServerPort.asStateFlow()

    private val _esp32Ip = MutableStateFlow(getEsp32Ip())
    val esp32IpFlow: StateFlow<String> = _esp32Ip.asStateFlow()

    private val _esp32Port = MutableStateFlow(getEsp32Port())
    val esp32PortFlow: StateFlow<String> = _esp32Port.asStateFlow()

    // ▼▼▼ 추가/수정된 코드 (마이크 민감도 Flow) ▼▼▼
    private val _micSensitivity = MutableStateFlow(getMicSensitivity())
    val micSensitivityFlow: StateFlow<Int> = _micSensitivity.asStateFlow()
    // ▲▲▲ 추가/수정된 코드 ▲▲▲

    // ▼▼▼ 추가/수정된 코드 (폰 마이크 모드 Flow) ▼▼▼
    private val _isPhoneMicModeEnabled = MutableStateFlow(isPhoneMicModeEnabled())
    val isPhoneMicModeEnabledFlow: StateFlow<Boolean> = _isPhoneMicModeEnabled.asStateFlow()
    // ▲▲▲ 추가/수정된 코드 ▲▲▲

    // ▼▼▼ 추가/수정된 코드 (폰 마이크 모드 Get/Set) ▼▼▼
    fun isPhoneMicModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_PHONE_MIC_MODE, false) // 기본값 false
    }

    fun setPhoneMicMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PHONE_MIC_MODE, enabled).apply()
        _isPhoneMicModeEnabled.value = enabled
    }
    // ▲▲▲ 추가/수정된 코드 ▲▲▲

    fun getEsp32Ip(): String {
        return prefs.getString(KEY_ESP32_IP, "192.168.43.101") ?: "192.168.43.101"
    }

    fun setEsp32Ip(ip: String) {
        prefs.edit().putString(KEY_ESP32_IP, ip).apply()
        _esp32Ip.value = ip
    }

    fun getEsp32Port(): String {
        return prefs.getString(KEY_ESP32_PORT, "8080") ?: "8080"
    }

    fun setEsp32Port(port: String) {
        prefs.edit().putString(KEY_ESP32_PORT, port).apply()
        _esp32Port.value = port
    }

    fun getTcpServerIp(): String {
        return prefs.getString(KEY_TCP_SERVER_IP, "192.168.0.1") ?: "192.168.0.1"
    }

    fun setTcpServerIp(ip: String) {
        prefs.edit().putString(KEY_TCP_SERVER_IP, ip).apply()
        _tcpServerIp.value = ip
    }

    fun getTcpServerPort(): String {
        return prefs.getString(KEY_TCP_SERVER_PORT, "6789") ?: "6789"
    }

    fun setTcpServerPort(port: String) {
        prefs.edit().putString(KEY_TCP_SERVER_PORT, port).apply()
        _tcpServerPort.value = port
    }

    fun getCustomKeywords(): String {
        return prefs.getString(KEY_CUSTOM_KEYWORDS, "") ?: ""
    }

    fun setCustomKeywords(keywords: String) {
        prefs.edit().putString(KEY_CUSTOM_KEYWORDS, keywords).apply()
        _customKeywords.value = keywords
    }

    fun isBackgroundExecutionEnabled(): Boolean {
        return prefs.getBoolean(KEY_BACKGROUND_EXECUTION, true)
    }

    fun setBackgroundExecution(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_EXECUTION, enabled).apply()
        _isBackgroundExecutionEnabled.value = enabled
    }

    // ▼▼▼ 추가/수정된 코드 (마이크 민감도 Get/Set) ▼▼▼
    fun getMicSensitivity(): Int {
        // 기본값을 5 (중간)로 설정
        return prefs.getInt(KEY_MIC_SENSITIVITY, 5)
    }

    fun setMicSensitivity(sensitivity: Int) {
        prefs.edit().putInt(KEY_MIC_SENSITIVITY, sensitivity).apply()
        _micSensitivity.value = sensitivity
    }
    // ▲▲▲ 추가/수정된 코드 ▲▲▲


    companion object {
        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = SettingsRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        private const val PREFS_NAME = "mqbl_settings"
        private const val KEY_BACKGROUND_EXECUTION = "background_execution_enabled"
        private const val KEY_CUSTOM_KEYWORDS = "custom_detection_keywords"
        private const val KEY_TCP_SERVER_IP = "tcp_server_ip"
        private const val KEY_TCP_SERVER_PORT = "tcp_server_port"
        private const val KEY_ESP32_IP = "esp32_ip"
        private const val KEY_ESP32_PORT = "esp32_port"
        private const val KEY_PHONE_MIC_MODE = "phone_mic_mode_enabled"

        // ▼▼▼ 추가/수정된 코드 (새 키) ▼▼▼
        private const val KEY_MIC_SENSITIVITY = "mic_sensitivity_vad"
        // ▲▲▲ 추가/수정된 코드 ▲▲▲
    }
}