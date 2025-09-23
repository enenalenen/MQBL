package com.example.mqbl.ui.main

import androidx.compose.foundation.BorderStroke
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
data class MainUiState(
    val status: String = "상태: 대기 중",
    val espDeviceName: String? = null,
    val isEspConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val connectError: String? = null
)

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
        if (uiState.isEspConnected) {
            Text(
                "연결됨: ${uiState.espDeviceName}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else if (uiState.isConnecting) {
            Text(
                "연결 중...",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        } else {
            Text(
                "연결되지 않음. '사용자 설정' 탭에서 ESP32에 연결하세요.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
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
            uiState = MainUiState(status = "상태", isEspConnected = true, espDeviceName = "ESP32 (Preview)"),
            detectionLog = listOf(DetectionEvent(description = "사이렌 감지됨 (미리보기)", timestamp = "12:34:56")),
            customSoundLog = listOf(CustomSoundEvent(description = "사용자 단어 감지됨 (미리보기)", timestamp = "12:35:00")),
        )
    }
}