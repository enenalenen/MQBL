package com.example.mqbl.ui.settings

import android.bluetooth.BluetoothDevice
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo // Preview용 import
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource // stringResource import
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mqbl.R // R 클래스 import
import com.example.mqbl.ui.ble.BleUiState
import com.example.mqbl.ui.tcp.TcpUiState
import com.example.mqbl.ui.wifidirect.WifiDirectPeerItem
import com.example.mqbl.ui.wifidirect.WifiDirectUiState
import java.net.InetAddress // Preview용 import

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    // App Settings
    settingsUiState: SettingsUiState,
    onBackgroundExecutionToggled: (Boolean) -> Unit,

    // BLE
    bleUiState: BleUiState,
    bondedDevices: List<BluetoothDevice>,
    scannedDevices: List<BluetoothDevice>,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onRequestBlePermissions: () -> Unit,
    onBleDisconnect: () -> Unit,
    onSendVibrationValue: (Int) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPairDevice: (BluetoothDevice) -> Unit,

    // TCP/IP
    tcpUiState: TcpUiState,
    currentServerIp: String,
    currentServerPort: String,
    onServerIpChange: (String) -> Unit,
    onServerPortChange: (String) -> Unit,
    onTcpConnect: () -> Unit,
    onTcpDisconnect: () -> Unit,

    // --- ▼▼▼ Wi-Fi Direct 파라미터 주석 처리 ▼▼▼ ---
    /*
    wifiDirectUiState: WifiDirectUiState,
    onRequestWifiDirectPermissions: () -> Unit,
    onDiscoverWifiDirectPeers: () -> Unit,
    onConnectToWifiDirectPeer: (WifiP2pDevice) -> Unit,
    onDisconnectWifiDirect: () -> Unit,
    onSendWifiDirectMessage: (String) -> Unit
    */
) {
    var isBleDropdownExpanded by remember { mutableStateOf(false) }
    var selectedBleDeviceDisplay by remember { mutableStateOf("페어링된 BLE 기기 선택") }
    var currentVibrationValue by remember { mutableIntStateOf(5) }
    // var wifiDirectMessageToSend by remember { mutableStateOf("Hello Wi-Fi Direct!") } // 주석 처리


    LaunchedEffect(bleUiState.connectedDeviceName) {
        selectedBleDeviceDisplay = bleUiState.connectedDeviceName ?: "페어링된 BLE 기기 선택"
    }
    LaunchedEffect(bleUiState.status) {
        if (bleUiState.status.contains("실패") || bleUiState.status.contains("끊김")) {
            selectedBleDeviceDisplay = "페어링된 BLE 기기 선택"
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Text("연결 및 장치 설정", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
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

        // --- BLE 연결 설정 섹션 ---
        item {
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
                            Button(onClick = { if (currentVibrationValue > 0) { currentVibrationValue--; onSendVibrationValue(currentVibrationValue) } }, enabled = currentVibrationValue > 0, modifier = Modifier.size(width = 96.dp, height = 48.dp), shape = RoundedCornerShape(percent = 50) ) { Text(text = "—", fontSize = 30.sp) }
                            Text(text = currentVibrationValue.toString(), style = MaterialTheme.typography.headlineMedium)
                            Button(onClick = { if (currentVibrationValue < 10) { currentVibrationValue++; onSendVibrationValue(currentVibrationValue) } }, enabled = currentVibrationValue < 10, modifier = Modifier.size(width = 96.dp, height = 48.dp), shape = RoundedCornerShape(percent = 50) ) { Text(text = "+", fontSize = 30.sp) }
                        }
                    }
                }
            }
        }
        // --- BLE 연결 설정 섹션 끝 ---

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
                                    Button(onClick = { onPairDevice(device) }) {
                                        Text("페어링")
                                    }
                                }
                            }
                        }
                    } else if (!bleUiState.isScanning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("검색된 기기가 없습니다.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        // --- 주변 기기 검색 섹션 끝 ---

        // --- TCP/IP 연결 설정 섹션 ---
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("TCP/IP 서버 설정", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = currentServerIp, onValueChange = onServerIpChange, label = { Text("서버 IP 주소") }, modifier = Modifier.weight(2f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !tcpUiState.isConnected && !tcpUiState.connectionStatus.contains("연결 중"))
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(value = currentServerPort, onValueChange = onServerPortChange, label = { Text("포트") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !tcpUiState.isConnected && !tcpUiState.connectionStatus.contains("연결 중"))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = tcpUiState.connectionStatus)
                    tcpUiState.errorMessage?.let { Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Button(onClick = onTcpConnect, enabled = !tcpUiState.isConnected && !tcpUiState.connectionStatus.contains("연결 중")) { Text("TCP 연결") }
                        Button(onClick = onTcpDisconnect, enabled = tcpUiState.isConnected || tcpUiState.connectionStatus.contains("연결 중")) { Text("TCP 연결 해제") }
                    }
                }
            }
        }
        // --- TCP/IP 연결 설정 섹션 끝 ---


        // --- ▼▼▼ Wi-Fi Direct 설정 섹션 전체 주석 처리 ▼▼▼ ---
        /*
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = stringResource(R.string.wifi_direct_settings_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(text = wifiDirectUiState.statusText, style = MaterialTheme.typography.bodyLarge)
                    wifiDirectUiState.errorMessage?.let {
                        Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    if (!wifiDirectUiState.isWifiDirectEnabled && !wifiDirectUiState.statusText.contains("권한 필요")) {
                        Text("Wi-Fi Direct가 꺼져 있습니다. 시스템 설정에서 활성화해주세요.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onDiscoverWifiDirectPeers,
                            enabled = wifiDirectUiState.isWifiDirectEnabled && wifiDirectUiState.connectedDeviceName == null && !wifiDirectUiState.isConnecting
                        ) {
                            Text(stringResource(R.string.discover_wifi_direct_peers))
                        }
                        if (wifiDirectUiState.connectedDeviceName != null || wifiDirectUiState.isConnecting) {
                            Button(
                                onClick = onDisconnectWifiDirect,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(stringResource(R.string.disconnect_wifi_direct))
                            }
                        }
                    }

                    if (wifiDirectUiState.statusText.contains("권한 필요")) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onRequestWifiDirectPermissions) {
                            Text(stringResource(R.string.wifi_direct_request_permissions))
                        }
                    }

                    if (wifiDirectUiState.peers.isNotEmpty() && wifiDirectUiState.connectedDeviceName == null && !wifiDirectUiState.isConnecting) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("검색된 기기:", style = MaterialTheme.typography.titleSmall)
                        Column(modifier = Modifier.heightIn(max = 200.dp)) {
                            wifiDirectUiState.peers.forEach { peer ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable(enabled = peer.status != WifiP2pDevice.CONNECTED && !wifiDirectUiState.isConnecting) {
                                            onConnectToWifiDirectPeer(peer.rawDevice)
                                        },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(peer.deviceName, fontWeight = FontWeight.Medium)
                                            Text(peer.deviceAddress, fontSize = 12.sp, color = Color.Gray)
                                        }
                                        Text(peer.getStatusString(), fontSize = 12.sp, color = if (peer.status == WifiP2pDevice.AVAILABLE) MaterialTheme.colorScheme.primary else Color.Gray)
                                    }
                                }
                            }
                        }
                    }

                    if (wifiDirectUiState.connectedDeviceName != null && wifiDirectUiState.connectionInfo?.groupFormed == true) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("연결 정보:", style = MaterialTheme.typography.titleSmall)
                        Text("연결된 기기: ${wifiDirectUiState.connectedDeviceName}")
                        Text(if (wifiDirectUiState.isGroupOwner) "역할: 그룹 소유자 (서버)" else "역할: 클라이언트")
                        wifiDirectUiState.groupOwnerAddress?.let { Text("그룹 소유자 주소: $it") }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.send_wifi_direct_message), style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = wifiDirectMessageToSend,
                            onValueChange = { wifiDirectMessageToSend = it },
                            label = { Text(stringResource(R.string.message_to_send_wifi_direct)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onSendWifiDirectMessage(wifiDirectMessageToSend) },
                            modifier = Modifier.align(Alignment.End),
                            enabled = true
                        ) {
                            Text("WD 전송")
                        }
                    }

                    if (wifiDirectUiState.receivedDataLog.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.wifi_direct_logs_title), style = MaterialTheme.typography.titleSmall)
                        Surface(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 150.dp),
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            LazyColumn(modifier = Modifier.padding(8.dp), reverseLayout = true) {
                                items(wifiDirectUiState.receivedDataLog) { log ->
                                    Text(log, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        */
        // --- ▲▲▲ Wi-Fi Direct 설정 섹션 전체 주석 처리 끝 ▲▲▲ ---
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
            tcpUiState = TcpUiState(connectionStatus = "TCP/IP 상태: 미리보기", isConnected = true),
            currentServerIp = "192.168.0.100",
            currentServerPort = "12345",
            onServerIpChange = {},
            onServerPortChange = {},
            onTcpConnect = {},
            onTcpDisconnect = {},
            // --- ▼▼▼ Preview에서 Wi-Fi Direct 파라미터 주석 처리 ▼▼▼ ---
            /*
            wifiDirectUiState = WifiDirectUiState(
                isWifiDirectEnabled = true,
                statusText = "Wi-Fi Direct: 미리보기 연결됨",
                errorMessage = null,
                connectedDeviceName = "미리보기 WD 장치",
                peers = emptyList(),
                connectionInfo = WifiP2pInfo().apply {
                    groupFormed = true
                    isGroupOwner = false
                    try {
                        groupOwnerAddress = InetAddress.getByName("192.168.49.1")
                    } catch (e: Exception) {
                        groupOwnerAddress = null
                    }
                },
                receivedDataLog = listOf("<- Hello from peer!", "-> Hi there!")
            ),
            onRequestWifiDirectPermissions = {},
            onDiscoverWifiDirectPeers = {},
            onConnectToWifiDirectPeer = {},
            onDisconnectWifiDirect = {},
            onSendWifiDirectMessage = {}
            */
        )
    }
}