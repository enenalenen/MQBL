package com.example.mqbl

// --- Android SDK ---
import android.Manifest // <--- Manifest.permission.* 사용 위해 필수
import android.os.Build // <--- Build.VERSION.* 사용 위해 필수
import android.os.Bundle
import android.util.Log // <--- Log.d 사용 위해 필수
// --- Activity ---
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts // 권한 요청용
// --- Compose UI & Foundation ---
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect // <--- DisposableEffect 사용 위해 필수
import androidx.compose.runtime.LaunchedEffect // SharedFlow 구독용
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner // <--- LocalLifecycleOwner 사용 위해 필수
import androidx.compose.ui.tooling.preview.Preview
// --- Lifecycle & ViewModel ---
import androidx.lifecycle.Lifecycle // Lifecycle 이벤트 사용
import androidx.lifecycle.LifecycleEventObserver // Lifecycle 이벤트 관찰
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
// --- Navigation ---
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
// --- Coroutines ---
import kotlinx.coroutines.flow.collectLatest // SharedFlow 구독용
// --- Project Specific ---
import com.example.mqbl.navigation.Screen
import com.example.mqbl.navigation.bottomNavItems
import com.example.mqbl.ui.ble.BleScreen
import com.example.mqbl.ui.ble.BleViewModel
import com.example.mqbl.ui.mqtt.MqttScreen
import com.example.mqbl.ui.mqtt.MqttViewModel
import com.example.mqbl.service.CommunicationService
// import com.example.mqbl.ui.theme.MQBLTheme // 필요시 테마 import
import android.content.Intent

class MainActivity : ComponentActivity() {

    // --- 권한 요청 결과 처리 런처 등록 ---
    // Activity 생성 시점에 등록되어야 함
    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // 결과가 오면 로그만 출력. 실제 처리는 ViewModel과 LifecycleEventObserver에서 수행.
            Log.d("MainActivity", "Permission Result Received: $permissions")
            // 여기서 ViewModel의 onPermissionsResult를 직접 호출하기 어려움 (올바른 ViewModel 인스턴스 접근 문제)
            // 대신 LifecycleEventObserver를 사용하여 화면 복귀 시 권한 상태를 재확인하도록 함.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startCommunicationService()

        setContent {
            MaterialTheme { // TODO: MQBLTheme 적용
                MainAppNavigation(
                    // Composable에 권한 요청 실행 함수 전달
                    requestPermissions = { permissionsToRequest ->
                        requestMultiplePermissionsLauncher.launch(permissionsToRequest)
                    }
                )
            }
        }
    }

    private fun startCommunicationService() {
        val serviceIntent = Intent(this, CommunicationService::class.java)
        // API 26 이상에서는 startForegroundService 사용
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.i("MainActivity", "CommunicationService started.")
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// 권한 요청 함수를 파라미터로 받도록 수정
fun MainAppNavigation(requestPermissions: (Array<String>) -> Unit) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                bottomNavItems.forEach { screen -> // bottomNavItems 리스트 사용 확인
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Ble.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Ble.route) {
                val bleViewModel: BleViewModel = viewModel()
                // ... (State 수집 코드는 동일) ...
                val uiState by bleViewModel.uiState.collectAsStateWithLifecycle()
                val bondedDevices by bleViewModel.bondedDevices.collectAsStateWithLifecycle()
                val detectionLog by bleViewModel.detectionEventLog.collectAsStateWithLifecycle()


                // --- ViewModel의 권한 요청 이벤트를 구독 ---
                // LaunchedEffect를 사용하여 Composable이 활성화될 때 이벤트 구독 시작
                LaunchedEffect(key1 = bleViewModel) { // key가 변경되지 않으면 재실행되지 않음
                    bleViewModel.permissionRequestEvent.collectLatest { // 이벤트 발생 시 실행
                        Log.d("BleScreen", "Permission request event received, launching dialog.")
                        // 필요한 권한 목록 정의 (API 레벨별)
                        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                            )
                        } else {
                            // API 31 미만에서는 기본 블루투스 권한은 Manifest에서 처리됨
                            // 위치 권한이 필요하다면 여기에 추가: arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            emptyArray() // 현재는 위치 권한 요청 안 함
                        }

                        if (permissionsToRequest.isNotEmpty()) {
                            // MainActivity로부터 전달받은 함수를 호출하여 권한 요청 실행
                            requestPermissions(permissionsToRequest)
                        }
                    }
                }

                // --- 화면 Resume 시 권한 상태 재확인 로직 ---
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, bleViewModel) { // lifecycleOwner나 viewModel이 변경되면 재실행
                    val observer = LifecycleEventObserver { _, event ->
                        // 화면이 다시 활성화될 때 (예: 권한 설정 후 복귀)
                        if (event == Lifecycle.Event.ON_RESUME) {
                            Log.d("BleScreen", "ON_RESUME detected, re-checking permissions.")
                            // ViewModel의 함수를 호출하여 권한 상태를 다시 확인하고 필요한 작업 수행
                            bleViewModel.checkOrRequestPermissions()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)

                    // Composable이 해제될 때 observer 제거
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                // BleScreen 호출 (기존과 동일, onRequestPermissions는 checkOrRequestPermissions 연결)
                BleScreen(
                    uiState = uiState,
                    bondedDevices = bondedDevices,
                    detectionLog = detectionLog,
                    onDeviceSelected = bleViewModel::connectToDevice,
                    onSendValue = bleViewModel::sendValue,
                    // 권한 요청 버튼 등에 연결될 수 있음 (누르면 다시 권한 확인 및 요청 시도)
                    onRequestPermissions = bleViewModel::checkOrRequestPermissions,
                    onDisconnect = bleViewModel::disconnect
                )
            }
            composable(Screen.Mqtt.route) {
                val mqttViewModel: MqttViewModel = viewModel()
                val uiState by mqttViewModel.uiState.collectAsStateWithLifecycle()
                val receivedMessages by mqttViewModel.receivedMessages.collectAsStateWithLifecycle()

                MqttScreen(
                    uiState = uiState,
                    receivedMessages = receivedMessages,
                    onConnect = mqttViewModel::connect,
                    onDisconnect = mqttViewModel::disconnect,
                    onPublish = mqttViewModel::publish
                )
            }
        } // NavHost 끝
    } // Scaffold 끝
}


// --- 기본 프리뷰 (선택 사항) ---
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    MaterialTheme { // 프리뷰에서는 임시 테마 사용
        // MainAppNavigation 호출 시 requestPermissions 파라미터 전달 추가
        MainAppNavigation(requestPermissions = {})
    }
}

// --- 앱 테마 정의 (선택 사항) ---
// 별도의 ui/theme/Theme.kt 파일로 분리하는 것이 좋음
/*
package com.example.mqbl.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color // 예시 색상

// 예시 색상 팔레트 (실제 색상으로 교체)
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6200EE),
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFF03DAC6)
    // 필요에 따라 다른 색상들 정의
)

@Composable
fun MQBLTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        // typography = Typography, // 필요시 Typography 정의
        content = content
    )
}
*/