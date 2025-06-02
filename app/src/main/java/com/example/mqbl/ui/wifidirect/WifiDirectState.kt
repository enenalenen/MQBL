package com.example.mqbl.ui.wifidirect

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo

/**
 * Wi-Fi Direct 관련 UI 상태를 나타내는 데이터 클래스.
 */
data class WifiDirectUiState(
    val isWifiDirectEnabled: Boolean = false,
    val peers: List<WifiDirectPeerItem> = emptyList(),
    val connectionInfo: WifiP2pInfo? = null,
    val statusText: String = "Wi-Fi Direct: 대기 중",
    val connectedDeviceName: String? = null,
    val isConnecting: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    val errorMessage: String? = null,
    val receivedDataLog: List<String> = emptyList() // Wi-Fi Direct 통해 수신된 데이터 로그
)

/**
 * 검색된 Wi-Fi Direct 피어 정보를 담는 데이터 클래스.
 */
data class WifiDirectPeerItem(
    val deviceAddress: String,
    val deviceName: String,
    val status: Int, // WifiP2pDevice.AVAILABLE, CONNECTED 등
    val rawDevice: WifiP2pDevice // 원본 WifiP2pDevice 객체 (연결 시 필요)
) {
    fun getStatusString(): String {
        return when (status) {
            WifiP2pDevice.AVAILABLE -> "사용 가능"
            WifiP2pDevice.INVITED -> "초대됨"
            WifiP2pDevice.CONNECTED -> "연결됨"
            WifiP2pDevice.FAILED -> "실패"
            WifiP2pDevice.UNAVAILABLE -> "사용 불가"
            else -> "알 수 없음"
        }
    }
}
