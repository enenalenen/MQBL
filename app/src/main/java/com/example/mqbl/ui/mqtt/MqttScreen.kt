package com.example.mqbl.ui.mqtt // 실제 패키지 경로 확인

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// import com.example.mqbl.ui.theme.MQBLTheme // 앱 테마 import (필요시)

// ViewModel에서 정의한 데이터 클래스 import 확인
// (같은 패키지에 있거나, 별도 파일에 있다면 해당 경로 import 필요)
// import com.example.mqbl.ui.mqtt.MqttUiState
// import com.example.mqbl.ui.mqtt.MqttMessageItem

/**
 * MQTT 기능을 위한 메인 화면 Composable.
 * ViewModel로부터 상태를 전달받고, 사용자 액션을 ViewModel로 전달합니다.
 */
@Composable
fun MqttScreen(
    // State Hoisting: ViewModel로부터 주입받을 상태 값들
    uiState: MqttUiState,
    receivedMessages: List<MqttMessageItem>, // 수신 메시지 목록

    // State Hoisting: ViewModel로 전달할 사용자 액션 콜백 함수들
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSubscribe: (topic: String) -> Unit,
    onPublish: (topic: String, payload: String) -> Unit
) {
    // --- UI 내부에서 사용하는 상태 (TextField 입력 값 등) ---
    var subscribeTopic by remember { mutableStateOf("test/mqbl/status") } // 구독 토픽 기본값
    var publishTopic by remember { mutableStateOf("test/mqbl/command") }   // 발행 토픽 기본값
    var publishMessage by remember { mutableStateOf("Hello MQBL!") } // 발행 메시지 기본값
    val listState = rememberLazyListState() // 메시지 목록 스크롤 상태

    // --- 상태 변경에 따른 부가 효과 ---
    // 새 메시지 수신 시 목록 맨 위로 스크롤 (reverseLayout=true 이므로 최신 메시지)
    LaunchedEffect(receivedMessages.size) {
        if (receivedMessages.isNotEmpty()) {
            listState.animateScrollToItem(index = 0)
        }
    }

    // --- UI 레이아웃 ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp) // 전체 화면 패딩
    ) {
        // 상단 연결 상태 표시
        Text(text = uiState.connectionStatus, style = MaterialTheme.typography.titleMedium)
        // 오류 메시지 표시
        uiState.errorMessage?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "오류: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 연결 / 연결 해제 버튼
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onConnect, enabled = !uiState.isConnected) {
                Text("Connect")
            }
            Button(onClick = onDisconnect, enabled = uiState.isConnected) {
                Text("Disconnect")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 구독 영역
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = subscribeTopic,
                onValueChange = { subscribeTopic = it },
                label = { Text("구독 토픽") }, // 한글 라벨 예시
                modifier = Modifier.weight(1f), // 남은 너비 채우기
                singleLine = true,
                enabled = uiState.isConnected // 연결 상태일 때만 활성화
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { onSubscribe(subscribeTopic) }, enabled = uiState.isConnected) {
                Text("구독") // 한글 버튼 텍스트 예시
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 발행 영역
        OutlinedTextField(
            value = publishTopic,
            onValueChange = { publishTopic = it },
            label = { Text("발행 토픽") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = uiState.isConnected // 연결 상태일 때만 활성화
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = publishMessage,
            onValueChange = { publishMessage = it },
            label = { Text("발행 메시지") },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.isConnected // 연결 상태일 때만 활성화
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onPublish(publishTopic, publishMessage) },
            enabled = uiState.isConnected,
            modifier = Modifier.align(Alignment.End) // 버튼 오른쪽 정렬
        ) {
            Text("발행")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 수신 메시지 로그
        Text("수신 메시지:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f), // 남은 세로 공간 차지
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, Color.Gray)
        ) {
            LazyColumn(
                modifier = Modifier.padding(8.dp),
                state = listState,
                reverseLayout = true // 새 메시지가 위(리스트 0번 인덱스)에 추가되고, 화면상으로는 아래에서 위로 쌓임
            ) {
                if (receivedMessages.isEmpty()) {
                    item {
                        // 메시지가 없을 때 중앙에 텍스트 표시
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
                                text = "Topic: ${messageItem.topic}",
                                fontSize = 12.sp,
                                color = Color.DarkGray
                            )
                            Text(
                                text = messageItem.payload,
                                fontSize = 14.sp
                            )
                            Text(
                                text = messageItem.timestamp,
                                fontSize = 10.sp,
                                color = Color.Gray,
                                modifier = Modifier.align(Alignment.End) // 타임스탬프 오른쪽 정렬
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray) // 메시지 구분선
                    }
                }
            }
        }
    }
} // 최상단 Column 끝

// --- Composable Preview ---
@Preview(showBackground = true, locale = "ko") // 한국어 로케일 프리뷰
@Composable
fun MqttScreenPreview() {
// Preview에서 사용할 임시 상태 데이터
    val previewUiState = MqttUiState(
        connectionStatus = "상태: 연결됨 (미리보기)",
        isConnected = true,
        errorMessage = null // 또는 "미리보기 오류 메시지"
    )
    val previewMessages = listOf(
        MqttMessageItem(topic = "test/topic/1", payload = "안녕하세요! MQTT!"),
        MqttMessageItem(topic = "test/topic/2", payload = "다른 메시지입니다."),
        MqttMessageItem(topic = "test/topic/1", payload = "더 많은 데이터...")
    ).reversed() // reverseLayout=true 이므로 미리보기 데이터도 뒤집어 최신이 위로 오게 함

    MaterialTheme { // 앱 테마 또는 기본 MaterialTheme 사용
        MqttScreen(
            uiState = previewUiState,
            receivedMessages = previewMessages,
            onConnect = {},
            onDisconnect = {},
            onSubscribe = {},
            onPublish = { _, _ -> } // 파라미터 2개 받는 람다
        )
    }
}