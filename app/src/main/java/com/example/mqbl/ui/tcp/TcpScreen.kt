package com.example.mqbl.ui.tcp // 패키지 경로 예시 (기존 ui/mqtt에서 변경)

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
// --- 수정: ui.tcp 패키지에서 직접 import ---
import com.example.mqbl.ui.tcp.TcpUiState // TcpState.kt 파일의 실제 경로로 수정
import com.example.mqbl.ui.tcp.TcpMessageItem // TcpState.kt 파일의 실제 경로로 수정
// --- 수정 끝 ---

// private const val DEFAULT_TCP_SERVER_IP_DISPLAY = "192.168.0.18" // 필요시 사용
// private const val DEFAULT_TCP_SERVER_PORT_DISPLAY = "12345" // 필요시 사용

@Composable
fun TcpScreen(
    // ViewModel로부터 받는 상태
    uiState: TcpUiState,
    receivedMessages: List<TcpMessageItem>,
    currentServerIp: String,
    currentServerPort: String,

    // ViewModel로 전달할 액션
    onServerIpChange: (String) -> Unit,
    onServerPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    var messageToSend by remember { mutableStateOf("Hello TCP Server!") }
    val listState = rememberLazyListState()
    val isDarkTheme = isSystemInDarkTheme()

    LaunchedEffect(receivedMessages.size) {
        if (receivedMessages.isNotEmpty()) {
            listState.animateScrollToItem(index = 0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("TCP/IP 서버 설정", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = currentServerIp,
                onValueChange = onServerIpChange,
                label = { Text("서버 IP 주소") },
                modifier = Modifier.weight(2f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !uiState.isConnected && !uiState.connectionStatus.contains("연결 중")
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = currentServerPort,
                onValueChange = onServerPortChange,
                label = { Text("포트") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !uiState.isConnected && !uiState.connectionStatus.contains("연결 중")
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Text(text = uiState.connectionStatus, style = MaterialTheme.typography.bodyLarge)
        uiState.errorMessage?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onConnect,
                enabled = !uiState.isConnected && !uiState.connectionStatus.contains("연결 중")
            ) {
                Text("서버 연결")
            }
            Button(
                onClick = onDisconnect,
                enabled = uiState.isConnected || uiState.connectionStatus.contains("연결 중")
            ) {
                Text("연결 해제")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("메시지 전송", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = messageToSend,
            onValueChange = { messageToSend = it },
            label = { Text("보낼 메시지") },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.isConnected
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onSendMessage(messageToSend) },
            enabled = uiState.isConnected,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("전송")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text("수신/송신 로그:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, if (isDarkTheme) Color.DarkGray else Color.Gray),
            color = if (isDarkTheme) Color(0xFF202020) else MaterialTheme.colorScheme.surface
        ) {
            LazyColumn(
                modifier = Modifier.padding(8.dp),
                state = listState,
                reverseLayout = true
            ) {
                if (receivedMessages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("수신된 메시지가 없습니다.")
                        }
                    }
                } else {
                    items(items = receivedMessages, key = { it.id }) { messageItem ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                // --- 수정: messageItem.topic -> messageItem.source ---
                                text = if (messageItem.source.startsWith("클라이언트")) "송신:" else "수신 (${messageItem.source}):",
                                // --- 수정 끝 ---
                                fontSize = 12.sp,
                                color = if (isDarkTheme) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f) else Color.DarkGray
                            )
                            Text(
                                text = messageItem.payload,
                                fontSize = 14.sp
                            )
                            Text(
                                text = messageItem.timestamp,
                                fontSize = 10.sp,
                                color = if (isDarkTheme) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else Color.Gray,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = if (isDarkTheme) MaterialTheme.colorScheme.outline.copy(alpha = 0.5f) else Color.LightGray
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Light Mode")
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark Mode")
@Composable
fun TcpScreenPreview() {
    val isDark = isSystemInDarkTheme()
    val previewUiState = TcpUiState( // TcpUiState 직접 사용
        connectionStatus = "TCP/IP: 연결됨 (미리보기)",
        isConnected = true,
        errorMessage = null
    )
    val previewMessages = listOf(
        // --- 수정: topic -> source, 값 변경 ---
        TcpMessageItem(source = "서버 (192.168.0.1)", payload = "서버로부터의 메시지"),
        TcpMessageItem(source = "클라이언트 -> 서버", payload = "클라이언트가 보낸 메시지")
        // --- 수정 끝 ---
    ).reversed()

    MaterialTheme(
        colorScheme = if (isDark) darkColorScheme(surface = Color(0xFF202020), onSurface = Color.White, outline = Color.DarkGray)
        else lightColorScheme(surface = Color.White, onSurface = Color.Black, outline = Color.LightGray)
    ) {
        TcpScreen(
            uiState = previewUiState,
            receivedMessages = previewMessages,
            currentServerIp = "192.168.0.100",
            currentServerPort = "12345",
            onServerIpChange = {},
            onServerPortChange = {},
            onConnect = {},
            onDisconnect = {},
            onSendMessage = {}
        )
    }
}

// 프리뷰용 다크/라이트 색상 스킴 (실제 앱 테마는 MainActivity.kt의 MQBLTheme 사용)
// 이 부분은 MqttScreen.kt 파일 내에 정의되어 있다면 그대로 두거나,
// 실제 앱 테마를 참조하도록 수정할 수 있습니다. 여기서는 MqttScreen.kt의 것을 유지한다고 가정합니다.
private val darkColorScheme = darkColorScheme(
    surface = Color(0xFF202020),
    onSurface = Color.White,
    outline = Color.DarkGray
)
private val lightColorScheme = lightColorScheme(
    surface = Color.White,
    onSurface = Color.Black,
    outline = Color.LightGray
)
