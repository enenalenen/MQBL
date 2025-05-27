package com.example.mqbl.ui.settings

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape // 버튼 모양
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mqbl.ui.ble.BleUiState
// --- 수정: MqttUiState import를 TcpUiState로 변경 ---
import com.example.mqbl.ui.tcp.TcpUiState // 실제 TcpUiState가 정의된 경로로 수정해야 함
// --- 수정 끝 ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    // BLE 관련 상태 및 액션
    bleUiState: BleUiState,
    bondedDevices: List<BluetoothDevice>,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onRequestBlePermissions: () -> Unit,
    onBleDisconnect: () -> Unit,
    onSendVibrationValue: (Int) -> Unit,

    // --- 수정: 파라미터 타입을 MqttUiState에서 TcpUiState로 변경 ---
    tcpUiState: TcpUiState, // 파라미터 이름도 tcpUiState로 변경 (MainActivity와 일치)
    // --- 수정 끝 ---
    // TCP/IP 서버 정보 (ViewModel로부터 받음)
    currentServerIp: String,
    currentServerPort: String,
    onServerIpChange: (String) -> Unit,
    onServerPortChange: (String) -> Unit,
    // --- 수정: 콜백 함수 이름 변경 ---
    onTcpConnect: () -> Unit,    // onMqttConnect -> onTcpConnect
    onTcpDisconnect: () -> Unit, // onMqttDisconnect -> onTcpDisconnect
    // --- 수정 끝 ---
) {
    var isBleDropdownExpanded by remember { mutableStateOf(false) }
    var selectedBleDeviceDisplay by remember { mutableStateOf("페어링된 BLE 기기 선택") }
    var currentVibrationValue by remember { mutableIntStateOf(5) }

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

        // --- BLE 연결 설정 섹션 (변경 없음) ---
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
                if (bleUiState.connectedDeviceName != null) {
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
                        ) { Text(text = "—", fontSize = 30.sp) }
                        Text(text = currentVibrationValue.toString(), style = MaterialTheme.typography.headlineMedium)
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
                        ) { Text(text = "+", fontSize = 30.sp) }
                    }
                }
            }
        }
        // --- BLE 연결 설정 섹션 끝 ---

        // --- TCP/IP 연결 설정 섹션 ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // --- 수정: 제목 변경 ---
                Text("TCP/IP 서버 설정", style = MaterialTheme.typography.titleMedium)
                // --- 수정 끝 ---
                Spacer(modifier = Modifier.height(8.dp))

                // --- 추가: 서버 IP 및 포트 입력 필드 ---
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = currentServerIp,
                        onValueChange = onServerIpChange,
                        label = { Text("서버 IP 주소") },
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        enabled = !tcpUiState.isConnected && !tcpUiState.connectionStatus.contains("연결 중")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = currentServerPort,
                        onValueChange = onServerPortChange,
                        label = { Text("포트") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !tcpUiState.isConnected && !tcpUiState.connectionStatus.contains("연결 중")
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // --- 추가 끝 ---

                // --- 수정: mqttUiState -> tcpUiState ---
                Text(text = tcpUiState.connectionStatus)
                tcpUiState.errorMessage?.let {
                    Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                // --- 수정 끝 ---
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // --- 수정: 콜백 및 버튼 텍스트 변경 ---
                    Button(onClick = onTcpConnect, enabled = !tcpUiState.isConnected && !tcpUiState.connectionStatus.contains("연결 중")) {
                        Text("TCP 연결")
                    }
                    Button(onClick = onTcpDisconnect, enabled = tcpUiState.isConnected || tcpUiState.connectionStatus.contains("연결 중")) {
                        Text("TCP 연결 해제")
                    }
                    // --- 수정 끝 ---
                }
            }
        }
        // --- TCP/IP 연결 설정 섹션 끝 ---
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
            // --- 수정: TcpUiState 사용 및 콜백 이름 변경 ---
            tcpUiState = TcpUiState(connectionStatus = "TCP/IP 상태: 미리보기", isConnected = true),
            currentServerIp = "192.168.0.100",
            currentServerPort = "12345",
            onServerIpChange = {},
            onServerPortChange = {},
            onTcpConnect = {},
            onTcpDisconnect = {}
            // --- 수정 끝 ---
        )
    }
}
