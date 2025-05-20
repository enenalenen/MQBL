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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID // DetectionEvent ID 타입

// --- 상태 표현을 위한 데이터 클래스 정의 ---
// (ViewModel 또는 별도 state 파일로 이동 가능)
data class BleUiState(
    val status: String = "상태: 대기 중",
    val receivedDataLog: String = "데이터 로그:", // 이 필드는 더 이상 UI에 직접 표시되지 않음
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
    detectionLog: List<DetectionEvent>,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onSendValue: (Int) -> Unit,
    onRequestPermissions: () -> Unit,
    onDisconnect: () -> Unit
) {
    var currentValue by remember { mutableIntStateOf(5) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var selectedDeviceDisplay by remember { mutableStateOf("페어링된 기기 선택") }

    LaunchedEffect(uiState.connectedDeviceName) {
        selectedDeviceDisplay = uiState.connectedDeviceName ?: "페어링된 기기 선택"
    }
    LaunchedEffect(uiState.status) {
        if (uiState.status.contains("실패") || uiState.status.contains("끊김")) {
            selectedDeviceDisplay = "페어링된 기기 선택"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 상단 상태 표시
        Text(text = uiState.status, style = MaterialTheme.typography.titleMedium)
        uiState.connectError?.let {
            Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 페어링된 기기 선택 드롭다운
        Box {
            OutlinedButton(
                onClick = { isDropdownExpanded = true },
                modifier = Modifier.fillMaxWidth(),
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
                    DropdownMenuItem(
                        text = { Text("권한 확인/요청") },
                        onClick = {
                            isDropdownExpanded = false
                            onRequestPermissions()
                        }
                    )
                } else {
                    bondedDevices.forEach { device ->
                        val deviceName = try { device.name ?: "이름 없음" } catch (e: SecurityException) { "이름 없음(권한 오류)" }
                        val deviceAddress = device.address
                        DropdownMenuItem(
                            text = { Text("$deviceName\n$deviceAddress") },
                            onClick = {
                                selectedDeviceDisplay = deviceName
                                isDropdownExpanded = false
                                onDeviceSelected(device)
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
            Button(
                onClick = {
                    if (currentValue > 0) {
                        currentValue--
                        onSendValue(currentValue)
                    }
                },
                enabled = uiState.connectedDeviceName != null && currentValue > 0,
                modifier = Modifier.size(width = 96.dp, height = 48.dp),
                shape = RoundedCornerShape(percent = 50)
            ) {
                Text(text = "—", fontSize = 30.sp)
            }
            Text(
                text = currentValue.toString(),
                style = MaterialTheme.typography.headlineMedium
            )
            Button(
                onClick = {
                    if (currentValue < 10) {
                        currentValue++
                        onSendValue(currentValue)
                    }
                },
                enabled = uiState.connectedDeviceName != null && currentValue < 10,
                modifier = Modifier.size(width = 96.dp, height = 48.dp),
                shape = RoundedCornerShape(percent = 50)
            ) {
                Text(text = "+", fontSize = 30.sp)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // 감지 이벤트 로그
        Text("최근 감지된 소리:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // --- 수정: 남은 세로 공간을 모두 차지하도록 weight(1f) 추가 ---
                .weight(1f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, Color.Gray)
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                if (detectionLog.isEmpty()) {
                    item { Text("감지된 소리가 없습니다.") }
                } else {
                    items(items = detectionLog, key = { it.id }) { event ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // --- 수정: 글자 크기 1sp 증가 ---
                            Text(event.description, fontSize = 15.sp) // 기존 14.sp
                            Text(event.timestamp, fontSize = 13.sp, color = Color.Gray) // 기존 12.sp
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }

        // --- "데이터 로그" 관련 Text 및 Surface 제거 ---
        // Spacer(modifier = Modifier.height(16.dp)) // 관련 Spacer도 제거
        // Text("데이터 로그:", style = MaterialTheme.typography.titleSmall) // 제거
        // Spacer(modifier = Modifier.height(8.dp)) // 제거
        // Surface( // 제거 시작
        //     modifier = Modifier
        //         .fillMaxWidth()
        //         .weight(1f),
        //     shape = MaterialTheme.shapes.medium,
        //     border = BorderStroke(1.dp, Color.LightGray)
        // ) {
        //    LazyColumn(modifier = Modifier.padding(8.dp)) {
        //        item {
        //             Text(uiState.receivedDataLog, fontSize = 10.sp)
        //         }
        //     }
        // } // 제거 끝
        // --- "데이터 로그" 관련 UI 제거 끝 ---

    } // 최상단 Column 끝
}


// --- Composable Preview ---
@Preview(showBackground = true)
@Composable
fun BleScreenPreview() {
    val previewState = BleUiState(
        status = "상태: 미리보기 기기에 연결됨",
        connectedDeviceName = "미리보기 기기",
        // receivedDataLog는 더 이상 UI에 직접 표시되지 않지만, 상태 객체에는 남아있음
        receivedDataLog = "데이터 로그:\n-> 5\n<- siren\n-> 6",
        isConnecting = false,
        connectError = null
    )
    val previewBondedDevices = emptyList<BluetoothDevice>()
    val previewDetectionLog = listOf(
        DetectionEvent(description = "사이렌 감지됨", timestamp = "14:05:10"),
        DetectionEvent(description = "경적 감지됨", timestamp = "14:05:05")
    )

    MaterialTheme {
        BleScreen(
            uiState = previewState,
            bondedDevices = previewBondedDevices,
            detectionLog = previewDetectionLog,
            onDeviceSelected = {},
            onSendValue = {},
            onRequestPermissions = {},
            onDisconnect = {}
        )
    }
}