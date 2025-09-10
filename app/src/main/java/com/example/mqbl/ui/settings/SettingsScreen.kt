package com.example.mqbl.ui.settings

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mqbl.R // R 클래스 import
import com.example.mqbl.ui.ble.BleUiState
import com.example.mqbl.ui.tcp.TcpUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    // App Settings
    settingsUiState: SettingsUiState,
    onBackgroundExecutionToggled: (Boolean) -> Unit,

    customKeywords: String,
    onCustomKeywordsChange: (String) -> Unit,
    onSaveCustomKeywords: () -> Unit,

    // BLE
    bleUiState: BleUiState,
    bondedDevices: List<BluetoothDevice>, // 파라미터는 유지하되 UI에서 사용 안 함
    scannedDevices: List<BluetoothDevice>,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onRequestBlePermissions: () -> Unit,
    onBleDisconnect: () -> Unit,
    onSendVibrationValue: (Int) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPairDevice: (BluetoothDevice) -> Unit,

    onSendCommand: (String) -> Unit,

    // TCP/IP
    tcpUiState: TcpUiState,
    tcpServerIp: String,
    tcpServerPort: String,
    onTcpServerIpChange: (String) -> Unit,
    onTcpServerPortChange: (String) -> Unit,
    onSaveTcpSettings: () -> Unit,
    onTcpConnect: () -> Unit,
    onTcpDisconnect: () -> Unit,
) {
    var currentVibrationValue by remember { mutableIntStateOf(5) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Text("연결 및 장치 설정", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("감지 단어 설정", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "쉼표(,)로 단어를 구분하여 입력하세요. 서버에 단어 목록이 업데이트됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customKeywords,
                        onValueChange = onCustomKeywordsChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("예: 불이야, 저기요, 홍길동씨") }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onSaveCustomKeywords,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("단어 저장")
                    }
                }
            }
        }

        // --- 앱 설정 섹션 ---
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(id = R.string.app_settings_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(id = R.string.background_execution_title), style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(id = R.string.background_execution_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = settingsUiState.isBackgroundExecutionEnabled,
                            onCheckedChange = onBackgroundExecutionToggled
                        )
                    }
                }
            }
        }
        // --- 앱 설정 섹션 끝 ---

        // --- BLE 연결 상태 표시 섹션 ---
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("블루투스(BLE) 상태", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    // 현재 연결 상태 및 오류 메시지 표시
                    Text(text = bleUiState.status)
                    bleUiState.connectError?.let {
                        Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    // 연결되었을 때만 '연결 해제' 버튼과 '진동 조절' UI 표시
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
                            Button(onClick = { if (currentVibrationValue > 0) { currentVibrationValue--; onSendVibrationValue(currentVibrationValue) } }, enabled = currentVibrationValue > 0, modifier = Modifier.size(width = 96.dp, height = 48.dp), shape = RoundedCornerShape(percent = 50) ) { Text(text = "—", fontSize = 30.sp) }
                            Text(text = currentVibrationValue.toString(), style = MaterialTheme.typography.headlineMedium)
                            Button(onClick = { if (currentVibrationValue < 10) { currentVibrationValue++; onSendVibrationValue(currentVibrationValue) } }, enabled = currentVibrationValue < 10, modifier = Modifier.size(width = 96.dp, height = 48.dp), shape = RoundedCornerShape(percent = 50) ) { Text(text = "+", fontSize = 30.sp) }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // --- ▼▼▼ 진동 테스트 버튼 수정 ▼▼▼ ---
                        Text(
                            "진동 테스트 (수동 신호 전송)",
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Button(onClick = { onSendCommand("VIBRATE_TRIGGER") }) {
                                Text("진동")
                            }
                        }
                        // --- ▲▲▲ 수정 끝 ▲▲▲ ---
                    }
                }
            }
        }
        // --- BLE 연결 상태 표시 섹션 끝 ---

        // --- 주변 기기 검색 섹션 ---
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("주변 기기 검색 (BLE)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (bleUiState.isScanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    Button(
                        onClick = { if (bleUiState.isScanning) onStopScan() else onStartScan() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (bleUiState.isScanning) "검색 중지" else "주변 기기 검색 시작")
                    }

                    if (scannedDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("검색된 기기:", style = MaterialTheme.typography.titleSmall)
                        Column(modifier = Modifier.heightIn(max = 200.dp)) {
                            scannedDevices.forEach { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        val deviceName = try { device.name ?: "이름 없음" } catch (e: SecurityException) { "권한 오류" }
                                        Text(deviceName, fontWeight = FontWeight.Bold)
                                        Text(device.address, fontSize = 12.sp)
                                    }
                                    Button(onClick = { onDeviceSelected(device) }) {
                                        Text("연결")
                                    }
                                }
                            }
                        }
                    } else if (!bleUiState.isScanning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("검색된 기기가 없습니다. 권한을 확인하거나, 검색 버튼을 눌러주세요.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        // --- 주변 기기 검색 섹션 끝 ---

        // --- TCP/IP 연결 설정 섹션 ---
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("STT 서버 설정", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = tcpServerIp,
                            onValueChange = onTcpServerIpChange,
                            label = { Text("서버 IP 주소") },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            enabled = !tcpUiState.isConnected && !tcpUiState.connectionStatus.contains("연결 중")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = tcpServerPort,
                            onValueChange = onTcpServerPortChange,
                            label = { Text("포트") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !tcpUiState.isConnected && !tcpUiState.connectionStatus.contains("연결 중")
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onSaveTcpSettings,
                        modifier = Modifier.align(Alignment.End),
                        enabled = !tcpUiState.isConnected && !tcpUiState.connectionStatus.contains("연결 중")
                    ) {
                        Text("서버 주소 저장")
                    }

                    Divider(modifier = Modifier.padding(vertical = 16.dp))

                    Text(text = tcpUiState.connectionStatus)
                    tcpUiState.errorMessage?.let { Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(onClick = onTcpConnect, enabled = !tcpUiState.isConnected && !tcpUiState.connectionStatus.contains("연결 중")) { Text("서버 연결") }
                        Button(onClick = onTcpDisconnect, enabled = tcpUiState.isConnected || tcpUiState.connectionStatus.contains("연결 중")) { Text("연결 해제") }
                    }
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
            settingsUiState = SettingsUiState(isBackgroundExecutionEnabled = true),
            onBackgroundExecutionToggled = {},
            bleUiState = BleUiState(status = "BLE 상태: 연결됨", connectedDeviceName = "미리보기 BLE 장치", isScanning = false),
            bondedDevices = emptyList(),
            scannedDevices = emptyList(),
            onDeviceSelected = {},
            onRequestBlePermissions = {},
            onBleDisconnect = {},
            onSendVibrationValue = {},
            onStartScan = {},
            onStopScan = {},
            onPairDevice = {},
            onSendCommand = {},
            tcpUiState = TcpUiState(connectionStatus = "TCP/IP 상태: 미리보기", isConnected = true),
            tcpServerIp = "192.168.0.10",
            tcpServerPort = "6789",
            onTcpServerIpChange = {},
            onTcpServerPortChange = {},
            onSaveTcpSettings = {},
            onTcpConnect = {},
            onTcpDisconnect = {},
            customKeywords = "fire, help",
            onCustomKeywordsChange = {},
            onSaveCustomKeywords = {},
        )
    }
}

