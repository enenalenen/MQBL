package com.example.mqbl.ui.settings

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape // 버튼 모양
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mqbl.ui.ble.BleUiState
import com.example.mqbl.ui.mqtt.MqttUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    // BLE 관련 상태 및 액션
    bleUiState: BleUiState,
    bondedDevices: List<BluetoothDevice>,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onRequestBlePermissions: () -> Unit,
    onBleDisconnect: () -> Unit, // BLE 연결 해제 콜백 추가
    onSendVibrationValue: (Int) -> Unit, // 진동 값 전송 콜백 추가
    // MQTT 관련 상태 및 액션
    mqttUiState: MqttUiState,
    onMqttConnect: () -> Unit,
    onMqttDisconnect: () -> Unit,
) {
    var isBleDropdownExpanded by remember { mutableStateOf(false) }
    var selectedBleDeviceDisplay by remember { mutableStateOf("페어링된 BLE 기기 선택") }
    var currentVibrationValue by remember { mutableIntStateOf(5) } // 진동 값 로컬 상태

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
        Text("연결 및 장치 설정", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))

        // --- BLE 연결 설정 섹션 ---
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("블루투스(BLE) 설정", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = bleUiState.status)
                bleUiState.connectError?.let {
                    Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
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
                            DropdownMenuItem(text = { Text("페어링된 기기 없음") }, onClick = { isBleDropdownExpanded = false })
                            DropdownMenuItem(text = { Text("권한 확인/요청") }, onClick = { isBleDropdownExpanded = false; onRequestBlePermissions() })
                        } else {
                            bondedDevices.forEach { device ->
                                val deviceName = try { device.name ?: "이름 없음" } catch (e: SecurityException) { "이름 없음(권한 오류)" }
                                DropdownMenuItem(
                                    text = { Text("$deviceName\n${device.address}") },
                                    onClick = { selectedBleDeviceDisplay = deviceName; isBleDropdownExpanded = false; onDeviceSelected(device) }
                                )
                            }
                        }
                    }
                }
                // BLE 연결 해제 버튼 추가
                if (bleUiState.connectedDeviceName != null || bleUiState.isConnecting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onBleDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("BLE 연결 해제")
                    }
                }

                // --- 진동 강도 조절 UI 추가 ---
                if (bleUiState.connectedDeviceName != null) { // BLE 연결되었을 때만 표시
                    Spacer(modifier = Modifier.height(16.dp))
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
                                if (currentVibrationValue > 0) {
                                    currentVibrationValue--
                                    onSendVibrationValue(currentVibrationValue)
                                }
                            },
                            enabled = currentVibrationValue > 0,
                            modifier = Modifier.size(width = 96.dp, height = 48.dp),
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text(text = "—", fontSize = 30.sp)
                        }
                        Text(
                            text = currentVibrationValue.toString(),
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Button(
                            onClick = {
                                if (currentVibrationValue < 10) {
                                    currentVibrationValue++
                                    onSendVibrationValue(currentVibrationValue)
                                }
                            },
                            enabled = currentVibrationValue < 10,
                            modifier = Modifier.size(width = 96.dp, height = 48.dp),
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text(text = "+", fontSize = 30.sp)
                        }
                    }
                }
                // --- 진동 강도 조절 UI 추가 끝 ---
            }
        }
        // --- BLE 연결 설정 섹션 끝 ---

        // --- MQTT 연결 설정 섹션 ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("MQTT 설정", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = mqttUiState.connectionStatus)
                mqttUiState.errorMessage?.let {
                    Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(16.dp))
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
            bleUiState = BleUiState(status = "BLE 상태: 연결됨", connectedDeviceName = "미리보기 BLE 장치"),
            bondedDevices = emptyList(),
            onDeviceSelected = {},
            onRequestBlePermissions = {},
            onBleDisconnect = {},
            onSendVibrationValue = {},
            mqttUiState = MqttUiState(connectionStatus = "MQTT 상태: 미리보기", isConnected = true),
            onMqttConnect = {},
            onMqttDisconnect = {}
        )
    }
}
