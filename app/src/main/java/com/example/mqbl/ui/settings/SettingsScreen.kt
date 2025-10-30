package com.example.mqbl.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mqbl.R
import com.example.mqbl.ui.main.MainUiState
import com.example.mqbl.ui.tcp.TcpUiState

@Composable
fun SettingsScreen(
    // App Settings
    settingsUiState: SettingsUiState,
    onBackgroundExecutionToggled: (Boolean) -> Unit,

    customKeywords: String,
    onCustomKeywordsChange: (String) -> Unit,
    onSaveCustomKeywords: () -> Unit,

    // ESP32 TCP
    mainUiState: MainUiState,
    esp32ServerIp: String,
    esp32ServerPort: String,
    onEsp32ServerIpChange: (String) -> Unit,
    onEsp32ServerPortChange: (String) -> Unit,
    onEsp32Connect: () -> Unit,
    onEsp32Disconnect: () -> Unit,
    onSendVibrationValue: (Int) -> Unit,
    onSendCommand: (String) -> Unit,

    // Audio Recording
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,

    // PC Server TCP/IP
    serverTcpUiState: TcpUiState,
    serverIp: String,
    serverPort: String,
    onServerIpChange: (String) -> Unit,
    onServerPortChange: (String) -> Unit,
    onSaveServerSettings: () -> Unit,
    onServerConnect: () -> Unit,
    onServerDisconnect: () -> Unit,
) {
    var currentVibrationValue by remember { mutableIntStateOf(5) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("연결 및 장치 설정", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
        }

        // --- ESP32 연결 설정 섹션 ---
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("넥밴드 연결 설정", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = esp32ServerIp,
                            onValueChange = onEsp32ServerIpChange,
                            label = { Text("넥밴드 IP 주소") },
                            placeholder = { Text("예시: 10.12.12.123") },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            enabled = !mainUiState.isEspConnected && !mainUiState.isConnecting
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = esp32ServerPort,
                            onValueChange = onEsp32ServerPortChange,
                            label = { Text("포트") },
                            placeholder = { Text("예시:1234") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !mainUiState.isEspConnected && !mainUiState.isConnecting
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = mainUiState.status)
                    mainUiState.connectError?.let { Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(onClick = onEsp32Connect, enabled = !mainUiState.isEspConnected && !mainUiState.isConnecting) { Text("넥밴드 연결") }
                        Button(onClick = onEsp32Disconnect, enabled = mainUiState.isEspConnected || mainUiState.isConnecting) { Text("연결 해제") }
                    }
                }
            }
        }

        // --- 진동 제어 및 오디오 녹음 섹션 ---
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("진동 강도 조절 (0 ~ 10)", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(onClick = { if (currentVibrationValue > 0) { currentVibrationValue--; onSendVibrationValue(currentVibrationValue) } }, enabled = currentVibrationValue > 0 && mainUiState.isEspConnected) { Text(text = "-") }
                            Text(text = currentVibrationValue.toString(), style = MaterialTheme.typography.headlineMedium)
                            Button(onClick = { if (currentVibrationValue < 10) { currentVibrationValue++; onSendVibrationValue(currentVibrationValue) } }, enabled = currentVibrationValue < 10 && mainUiState.isEspConnected) { Text(text = "+") }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { onSendCommand("VIBRATE_TRIGGER") }, enabled = mainUiState.isEspConnected) {
                            Text("진동 테스트 (수동 신호)")
                        }
                    }

                    HorizontalDivider()

                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("오디오 녹음 테스트", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "넥밴드와 연결된 상태에서 녹음/중지 시, 오디오가 .wav 파일로 저장됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = onStartRecording,
                                enabled = !settingsUiState.isRecording && mainUiState.isEspConnected
                            ) { Text("녹음 시작") }
                            Button(
                                onClick = onStopRecording,
                                enabled = settingsUiState.isRecording
                            ) { Text("중지 및 저장") }
                        }
                    }
                }
            }
        }


        // --- 감지 단어 설정 섹션 ---
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
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

        // --- PC 서버 연결 설정 섹션 ---
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("서버 연결 설정", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = serverIp,
                            onValueChange = onServerIpChange,
                            label = { Text("서버 IP 주소") },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            enabled = !serverTcpUiState.isConnected && !serverTcpUiState.connectionStatus.contains("연결 중")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = serverPort,
                            onValueChange = onServerPortChange,
                            label = { Text("포트") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !serverTcpUiState.isConnected && !serverTcpUiState.connectionStatus.contains("연결 중")
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onSaveServerSettings,
                        modifier = Modifier.align(Alignment.End),
                        enabled = !serverTcpUiState.isConnected && !serverTcpUiState.connectionStatus.contains("연결 중")
                    ) {
                        Text("서버 주소 저장")
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                    Text(text = serverTcpUiState.connectionStatus)
                    serverTcpUiState.errorMessage?.let { Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(onClick = onServerConnect, enabled = !serverTcpUiState.isConnected && !serverTcpUiState.connectionStatus.contains("연결 중")) { Text("서버 연결") }
                        Button(onClick = onServerDisconnect, enabled = serverTcpUiState.isConnected || serverTcpUiState.connectionStatus.contains("연결 중")) { Text("연결 해제") }
                    }
                }
            }
        }

        // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
        // 사라졌던 '앱 설정' UI 섹션을 여기에 복원했습니다.
        // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
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
    }
}


@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen(
            settingsUiState = SettingsUiState(isBackgroundExecutionEnabled = true, isRecording = false),
            onBackgroundExecutionToggled = {},
            mainUiState = MainUiState(status = "스마트 넥밴드: 연결됨", espDeviceName = "스마트 넥밴드 (Preview)", isEspConnected = true),
            onSendVibrationValue = {},
            onSendCommand = {},
            onStartRecording = {},
            onStopRecording = {},
            serverTcpUiState = TcpUiState(connectionStatus = "서버: 연결됨 (미리보기)", isConnected = true),
            serverIp = "192.168.0.10",
            serverPort = "6789",
            onServerIpChange = {},
            onServerPortChange = {},
            onSaveServerSettings = {},
            onServerConnect = {},
            onServerDisconnect = {},
            customKeywords = "fire, help",
            onCustomKeywordsChange = {},
            onSaveCustomKeywords = {},
            esp32ServerIp = "192.168.43.101",
            esp32ServerPort = "8080",
            onEsp32ServerIpChange = {},
            onEsp32ServerPortChange = {},
            onEsp32Connect = {},
            onEsp32Disconnect = {}
        )
    }
}