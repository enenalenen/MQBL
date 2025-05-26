package com.example.mqbl.ui.ble // 패키지 경로는 실제 프로젝트 구조에 맞게 조정

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme // 다크 모드 감지
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
// import androidx.compose.foundation.shape.RoundedCornerShape // 진동 조절 버튼 제거로 미사용
import androidx.compose.material3.*
import androidx.compose.runtime.* // currentValue 제거
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID // DetectionEvent ID 타입

// --- 상태 표현을 위한 데이터 클래스 정의 ---
data class BleUiState(
    val status: String = "상태: 대기 중",
    val receivedDataLog: String = "데이터 로그:",
    val connectedDeviceName: String? = null,
    val isBluetoothSupported: Boolean = true,
    val isConnecting: Boolean = false,
    val connectError: String? = null
)

data class DetectionEvent(
    val id: UUID = UUID.randomUUID(),
    val description: String,
    val timestamp: String
)
// --- 상태 클래스 정의 끝 ---

@SuppressLint("MissingPermission")
@Composable
fun BleScreen(
    uiState: BleUiState,
    bondedDevices: List<BluetoothDevice>,
    detectionLog: List<DetectionEvent>, // 이 로그는 이제 '경고'로 간주
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onSendValue: (Int) -> Unit, // 이 콜백은 SettingsScreen으로 이동될 예정
    onRequestPermissions: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme() // 현재 테마가 다크 모드인지 확인

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // --- 연결 상태 안내 문구 ---
        if (uiState.connectedDeviceName != null) {
            Text(
                "연결됨: ${uiState.connectedDeviceName}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else if (uiState.isConnecting) {
            Text(
                "연결 중...",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else {
            Text(
                "연결되지 않음. '사용자 설정' 탭에서 기기를 연결하세요.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        // --- 연결 상태 안내 문구 끝 ---

        // --- 진동 강도 조절 UI 제거됨 ---

        // --- 최근 감지된 음성 로그 ---
        Text("최근 감지된 음성:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f), // 화면 절반 차지
            shape = MaterialTheme.shapes.medium,
            // --- 수정: 라이트 모드일 때 테두리 색상을 Color.Gray로 변경 ---
            border = BorderStroke(1.dp, if (isDarkTheme) Color.DarkGray else Color.Gray), // 테두리 색상 조정
            // --- 다크 모드일 때 배경색 변경 (더 어둡게) ---
            color = if (isDarkTheme) Color(0xFF202020) else MaterialTheme.colorScheme.surface
            // ------------------------------------
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("감지된 음성이 없습니다.") // 텍스트 색상은 테마의 onSurface를 따름
                    }
                }
            }
        }
        // --- 최근 감지된 음성 로그 끝 ---

        Spacer(modifier = Modifier.height(16.dp)) // 음성 로그와 경고 로그 사이 간격

        // --- 최근 감지된 경고 로그 (기존 '최근 감지된 소리') ---
        Text("최근 감지된 경고:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f), // 화면 절반 차지
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, if (isDarkTheme) Color.DarkGray else Color.Gray), // 테두리 색상 조정
            // --- 다크 모드일 때 배경색 변경 (더 어둡게) ---
            color = if (isDarkTheme) Color(0xFF202020) else MaterialTheme.colorScheme.surface
            // ------------------------------------
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                if (detectionLog.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("감지된 경고가 없습니다.") // 텍스트 색상은 테마의 onSurface를 따름
                        }
                    }
                } else {
                    items(items = detectionLog, key = { it.id }) { event ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                event.description,
                                fontSize = 15.sp
                                // color = MaterialTheme.colorScheme.onSurface // 테마 색상 사용
                            )
                            Text(
                                event.timestamp,
                                fontSize = 13.sp,
                                color = if (isDarkTheme) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else Color.Gray
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
        // --- 최근 감지된 경고 로그 끝 ---

    } // 최상단 Column 끝
}

// --- Composable Preview ---
@Preview(showBackground = true, name = "Light Mode")
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark Mode")
@Composable
fun BleScreenPreview() {
    val isDark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (isDark) BleDarkColorSchemePreview else BleLightColorSchemePreview
    ) {
        BleScreen(
            uiState = BleUiState(status = "BLE 상태", connectedDeviceName = null, isConnecting = false),
            bondedDevices = emptyList(),
            detectionLog = emptyList(),
            onDeviceSelected = {},
            onSendValue = {},
            onRequestPermissions = {},
            onDisconnect = {}
        )
    }
}

// 프리뷰용 다크/라이트 색상 스킴 (실제 앱 테마는 MainActivity.kt의 MQBLTheme 사용)
private val BleDarkColorSchemePreview = darkColorScheme(
    surface = Color(0xFF202020), // 더 어두운 회색
    onSurface = Color.White,
    outline = Color.DarkGray
)
private val BleLightColorSchemePreview = lightColorScheme(
    surface = Color.White,
    onSurface = Color.Black,
    outline = Color.Gray // 프리뷰에서도 일관성을 위해 Gray로 변경
)
