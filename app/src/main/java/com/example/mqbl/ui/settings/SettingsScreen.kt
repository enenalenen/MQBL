package com.example.mqbl.ui.settings

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mqbl.ui.ble.BleUiState // BLE 상태 클래스 import
import com.example.mqbl.ui.mqtt.MqttUiState // MQTT 상태 클래스 import

// 임시로 BleScreen에서 가져온 데이터 클래스 (실제로는 ViewModel에서 관리)
// import com.example.mqbl.ui.ble.DetectionEvent // SettingsScreen에서는 직접 사용 안 할 수 있음

/**
 * 사용자 설정 화면 Composable.
 * BLE 및 MQTT 연결 상태 표시 및 제어 UI를 포함합니다.
 */
@OptIn(ExperimentalMaterial3Api::class) // Card 등 실험적 API 사용
@Composable
fun SettingsScreen(
    // BLE 관련 상태 및 액션
    bleUiState: BleUiState,
    bondedDevices: List<BluetoothDevice>,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onRequestBlePermissions: () -> Unit, // BLE 권한 요청
    // MQTT 관련 상태 및 액션
    mqttUiState: MqttUiState,
    onMqttConnect: () -> Unit,
    onMqttDisconnect: () -> Unit,
) {
    // BLE 기기 선택 드롭다운 확장 상태
    var isBleDropdownExpanded by remember { mutableStateOf(false) }
    var selectedBleDeviceDisplay by remember { mutableStateOf("페어링된 BLE 기기 선택") }

    // BLE 연결 상태에 따라 드롭다운 텍스트 업데이트
    LaunchedEffect(bleUiState.connectedDeviceName) {
        selectedBleDeviceDisplay = bleUiState.connectedDeviceName ?: "페어링된 BLE 기기 선택"
    }
    LaunchedEffect(bleUiState.status) {
        if (bleUiState.status.contains("실패") || bleUiState.status.contains("끊김")) {
            selectedBleDeviceDisplay = "페어링된 BLE 기기 선택"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("연결 설정", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))

        // --- BLE 연결 설정 섹션 ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("블루투스(BLE) 설정", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // BLE 상태 표시
                Text(text = bleUiState.status)
                bleUiState.connectError?.let {
                    Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))

                // 페어링된 BLE 기기 선택 드롭다운
                Box {
                    OutlinedButton(
                        onClick = { isBleDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = bleUiState.isBluetoothSupported && bleUiState.connectedDeviceName == null && !bleUiState.isConnecting
                    ) {
                        Text(selectedBleDeviceDisplay)
                    }
                    DropdownMenu(
                        expanded = isBleDropdownExpanded,
                        onDismissRequest = { isBleDropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (bondedDevices.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("페어링된 기기 없음") },
                                onClick = { isBleDropdownExpanded = false }
                            )
                            DropdownMenuItem( // 권한 요청 옵션 추가
                                text = { Text("권한 확인/요청") },
                                onClick = {
                                    isBleDropdownExpanded = false
                                    onRequestBlePermissions()
                                }
                            )
                        } else {
                            bondedDevices.forEach { device ->
                                val deviceName = try { device.name ?: "이름 없음" } catch (e: SecurityException) { "이름 없음(권한 오류)" }
                                val deviceAddress = device.address
                                DropdownMenuItem(
                                    text = { Text("$deviceName\n$deviceAddress") },
                                    onClick = {
                                        selectedBleDeviceDisplay = deviceName
                                        isBleDropdownExpanded = false
                                        onDeviceSelected(device)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        // --- BLE 연결 설정 섹션 끝 ---

        Spacer(modifier = Modifier.height(24.dp))

        // --- MQTT 연결 설정 섹션 ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("MQTT 설정", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // MQTT 상태 표시
                Text(text = mqttUiState.connectionStatus)
                mqttUiState.errorMessage?.let {
                    Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(16.dp))

                // MQTT 연결/해제 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = onMqttConnect, enabled = !mqttUiState.isConnected) {
                        Text("MQTT Connect")
                    }
                    Button(onClick = onMqttDisconnect, enabled = mqttUiState.isConnected) {
                        Text("MQTT Disconnect")
                    }
                }
            }
        }
        // --- MQTT 연결 설정 섹션 끝 ---
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen(
            bleUiState = BleUiState(status = "BLE 상태: 미리보기", connectedDeviceName = "미리보기 BLE 장치"),
            bondedDevices = emptyList(),
            onDeviceSelected = {},
            onRequestBlePermissions = {},
            mqttUiState = MqttUiState(connectionStatus = "MQTT 상태: 미리보기", isConnected = true),
            onMqttConnect = {},
            onMqttDisconnect = {}
        )
    }
}
