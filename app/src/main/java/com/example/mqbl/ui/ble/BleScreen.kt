package com.example.mqbl.ui.ble // 패키지 경로는 실제 프로젝트 구조에 맞게 조정

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID // DetectionEvent ID 타입
import androidx.compose.ui.tooling.preview.Preview

// --- 상태 표현을 위한 데이터 클래스 정의 ---
// (ViewModel 또는 별도 state 파일로 이동 가능)
data class BleUiState(
    val status: String = "상태: 대기 중",
    val receivedDataLog: String = "데이터 로그:", // 원본의 receivedData 역할 + 이름 변경
    val connectedDeviceName: String? = null,
    val isBluetoothSupported: Boolean = true,
    val isConnecting: Boolean = false, // 연결 시도 중 상태 추가
    val connectError: String? = null   // 연결 오류 메시지 상태 추가
)

data class DetectionEvent(
    val id: UUID = UUID.randomUUID(), // LazyColumn key 용 고유 ID
    val description: String,          // 감지 내용 ("사이렌 감지됨" 등)
    val timestamp: String             // 감지 시간 ("HH:mm:ss")
)
// --- 상태 클래스 정의 끝 ---


/**
 * BLE 기능을 위한 메인 화면 Composable.
 * ViewModel로부터 상태(State)를 전달받고, 사용자 액션(Action)을 ViewModel로 전달합니다.
 */
@SuppressLint("MissingPermission") // 권한 확인은 ViewModel 또는 화면 진입 전에 이루어져야 함
@Composable
fun BleScreen(
    // State Hoisting: ViewModel로부터 주입받을 상태 값들
    uiState: BleUiState,
    bondedDevices: List<BluetoothDevice>, // 페어링된 기기 목록
    detectionLog: List<DetectionEvent>, // 감지 이벤트 로그 목록

    // State Hoisting: ViewModel로 전달할 사용자 액션 콜백 함수들
    onDeviceSelected: (BluetoothDevice) -> Unit, // 기기 선택 시 호출 (연결 시도)
    onSendValue: (Int) -> Unit, // 값 전송 버튼 클릭 시 호출
    onRequestPermissions: () -> Unit, // (선택) 권한 재요청 액션
    onDisconnect: () -> Unit // (선택) 연결 해제 액션 추가 고려
) {
    // --- UI 내부에서 사용하는 상태 ---
    var currentValue by remember { mutableIntStateOf(5) } // 진동 세기 조절 값 (UI 상태)
    var isDropdownExpanded by remember { mutableStateOf(false) } // 기기 선택 드롭다운 확장 여부
    var selectedDeviceDisplay by remember { mutableStateOf("페어링된 기기 선택") } // 드롭다운 버튼 텍스트

    // --- 상태 변경에 따른 부가 효과 (Side Effects) ---
    // 연결된 기기 이름이 변경되면 드롭다운 텍스트 업데이트
    LaunchedEffect(uiState.connectedDeviceName) {
        selectedDeviceDisplay = uiState.connectedDeviceName ?: "페어링된 기기 선택"
    }
    // 연결 실패/끊김 시 드롭다운 텍스트 초기화 (다시 선택 가능하도록)
    LaunchedEffect(uiState.status) {
        if (uiState.status.contains("실패") || uiState.status.contains("끊김")) {
            selectedDeviceDisplay = "페어링된 기기 선택"
        }
    }

    // --- UI 레이아웃 ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp) // 전체 화면에 패딩 적용
    ) {
        // 상단 상태 표시
        Text(text = uiState.status, style = MaterialTheme.typography.titleMedium)
        uiState.connectError?.let { // 연결 오류 메시지가 있다면 표시
            Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 페어링된 기기 선택 드롭다운
        Box {
            OutlinedButton(
                onClick = { isDropdownExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                // 블루투스 미지원, 연결됨, 연결 중 상태에서는 비활성화
                enabled = uiState.isBluetoothSupported
                        && uiState.connectedDeviceName == null
                        && !uiState.isConnecting
            ) {
                Text(selectedDeviceDisplay)
            }
            DropdownMenu(
                expanded = isDropdownExpanded,
                onDismissRequest = { isDropdownExpanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (bondedDevices.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("페어링된 기기 없음 (설정에서 페어링)") },
                        onClick = { isDropdownExpanded = false }
                    )
                    // (선택) 권한 확인/요청 버튼 추가
                    DropdownMenuItem(
                        text = { Text("권한 확인/요청") },
                        onClick = {
                            isDropdownExpanded = false
                            onRequestPermissions() // 권한 요청 콜백 호출
                        }
                    )
                } else {
                    bondedDevices.forEach { device ->
                        // 기기 이름 가져올 때 SecurityException 처리
                        val deviceName = try { device.name ?: "이름 없음" } catch (e: SecurityException) { "이름 없음(권한 오류)" }
                        val deviceAddress = device.address
                        DropdownMenuItem(
                            text = { Text("$deviceName\n$deviceAddress") },
                            onClick = {
                                selectedDeviceDisplay = deviceName // 즉시 선택된 이름 표시
                                isDropdownExpanded = false
                                onDeviceSelected(device) // ViewModel에 기기 선택 알림 (연결 시도)
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 진동 강도 조절 UI
        Text(
            "진동 강도 조절 (0 ~ 10)",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // 감소 버튼
            Button(
                onClick = {
                    if (currentValue > 0) {
                        currentValue--
                        onSendValue(currentValue) // ViewModel에 값 전송 요청
                    }
                },
                enabled = uiState.connectedDeviceName != null && currentValue > 0, // 연결 상태이고 0보다 클 때 활성화
                modifier = Modifier.size(width = 96.dp, height = 48.dp),
                shape = RoundedCornerShape(percent = 50)
            ) {
                Text(text = "—", fontSize = 30.sp)
            }

            // 현재 값 표시
            Text(
                text = currentValue.toString(),
                style = MaterialTheme.typography.headlineMedium
            )

            // 증가 버튼
            Button(
                onClick = {
                    if (currentValue < 10) {
                        currentValue++
                        onSendValue(currentValue) // ViewModel에 값 전송 요청
                    }
                },
                enabled = uiState.connectedDeviceName != null && currentValue < 10, // 연결 상태이고 10보다 작을 때 활성화
                modifier = Modifier.size(width = 96.dp, height = 48.dp),
                shape = RoundedCornerShape(percent = 50)
            ) {
                Text(text = "+", fontSize = 30.sp)
            }
        }
        Spacer(modifier = Modifier.height(24.dp)) // 간격 증가

        // 감지 이벤트 로그
        Text("최근 감지된 소리:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp), // 높이 조절
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, Color.Gray)
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                if (detectionLog.isEmpty()) {
                    item { Text("감지된 소리가 없습니다.") }
                } else {
                    // 고유 ID를 key로 사용하여 성능 최적화
                    items(items = detectionLog, key = { it.id }) { event ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(event.description, fontSize = 14.sp)
                            Text(event.timestamp, fontSize = 12.sp, color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 데이터 로그 (하단에 작은 영역 차지)
        Text("데이터 로그:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f), // 남은 세로 공간 모두 차지
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, Color.LightGray) // 테두리 추가
        ) {
            // 내용이 길어질 수 있으므로 LazyColumn 사용
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                item {
                    Text(uiState.receivedDataLog, fontSize = 10.sp) // 작은 폰트 사용
                }
            }
        }
    } // 최상단 Column 끝
}


// --- Composable Preview ---
@Preview(showBackground = true)
@Composable
fun BleScreenPreview() {
    // Preview에서 사용할 임시 상태 데이터
    val previewState = BleUiState(
        status = "상태: 미리보기 기기에 연결됨",
        connectedDeviceName = "미리보기 기기",
        receivedDataLog = "데이터 로그:\n-> 5\n<- siren\n-> 6",
        isConnecting = false,
        connectError = null
    )
    // Preview에서는 실제 블루투스 기기 목록을 가져올 수 없음
    val previewBondedDevices = emptyList<BluetoothDevice>()
    val previewDetectionLog = listOf(
        DetectionEvent(description = "사이렌 감지됨", timestamp = "14:05:10"),
        DetectionEvent(description = "경적 감지됨", timestamp = "14:05:05")
    )

    MaterialTheme { // Preview 테마 적용
        BleScreen(
            uiState = previewState,
            bondedDevices = previewBondedDevices,
            detectionLog = previewDetectionLog,
            onDeviceSelected = {}, // Preview에서는 빈 람다 전달
            onSendValue = {},      // Preview에서는 빈 람다 전달
            onRequestPermissions = {}, // Preview에서는 빈 람다 전달
            onDisconnect = {} // <-- 누락되었던 파라미터 추가
        )
    }
}