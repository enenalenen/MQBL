package com.example.mqbl.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

// --- 상태 표현을 위한 데이터 클래스 정의 ---
// ▼▼▼ 추가/수정된 코드 (데이터 클래스 수정) ▼▼▼
data class MainUiState(
    val status: String = "음성 인식 비활성화", // ViewModel이 이 텍스트를 설정함
    val isRecognitionActive: Boolean = false, // 원의 색상을 결정함

    // 기존 필드 (참조용)
    val espDeviceName: String? = null,
    val isEspConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val connectError: String? = null
)
// ▲▲▲ 추가/수정된 코드 ▲▲▲

data class DetectionEvent(
    val id: UUID = UUID.randomUUID(),
    val description: String,
    val timestamp: String
)

data class CustomSoundEvent(
    val id: UUID = UUID.randomUUID(),
    val description: String,
    val timestamp: String
)

@Composable
fun MainScreen(
    uiState: MainUiState,
    detectionLog: List<DetectionEvent>,
    customSoundLog: List<CustomSoundEvent>,
) {
    val isDarkTheme = isSystemInDarkTheme()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // ▼▼▼ 추가/수정된 코드 (상태 표시줄 UI 변경) ▼▼▼
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- 초록색/빨간색 상태 원 ---
            Canvas(modifier = Modifier.size(14.dp)) {
                drawCircle(
                    color = if (uiState.isRecognitionActive) Color(0xFF4CAF50) /* 초록색 */ else Color(0xFFF44336) /* 빨간색 */
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // --- 상태 텍스트 ---
            Text(
                text = uiState.status,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 0.dp) // 하단 패딩 제거
            )
        }
        // ▲▲▲ 추가/수정된 코드 ▲▲▲

        Spacer(modifier = Modifier.height(16.dp))

        // --- '최근 감지된 음성' 로그 ---
        Text("최근 감지된 음성:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, if (isDarkTheme) Color.DarkGray else Color.Gray),
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                if (customSoundLog.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("감지된 음성이 없습니다.")
                        }
                    }
                } else {
                    items(items = customSoundLog, key = { it.id }) { event ->
                        LogItem(event.description, event.timestamp, isDarkTheme)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // --- '최근 감지된 경고' 로그 ---
        Text("최근 감지된 경고:", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, if (isDarkTheme) Color.DarkGray else Color.Gray),
        ) {
            LazyColumn(modifier = Modifier.padding(8.dp)) {
                if (detectionLog.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("감지된 경고가 없습니다.")
                        }
                    }
                } else {
                    items(items = detectionLog, key = { it.id }) { event ->
                        LogItem(event.description, event.timestamp, isDarkTheme)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItem(description: String, timestamp: String, isDarkTheme: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            description,
            fontSize = 15.sp
        )
        Text(
            timestamp,
            fontSize = 13.sp,
            color = if (isDarkTheme) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else Color.Gray
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}


@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MaterialTheme {
        MainScreen(
            // ▼▼▼ 미리보기 UI 상태 수정 ▼▼▼
            uiState = MainUiState(
                status = "음성 인식 활성화 : 넥밴드 모드",
                isRecognitionActive = true,
                isEspConnected = true,
                espDeviceName = "스마트 넥밴드 (Preview)"
            ),
            // ▲▲▲ 미리보기 UI 상태 수정 ▲▲▲
            detectionLog = listOf(DetectionEvent(description = "사이렌 감지됨 (미리보기)", timestamp = "12:34:56")),
            customSoundLog = listOf(CustomSoundEvent(description = "사용자 단어 감지됨 (미리보기)", timestamp = "12:35:00")),
        )
    }
}