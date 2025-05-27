package com.example.mqbl

// --- Android SDK ---
import android.Manifest
import android.app.Activity
import android.content.Intent
// import android.graphics.Color as AndroidGraphicsColor
import android.os.Build
import android.os.Bundle
import android.util.Log
// import android.view.WindowManager
// --- Activity ---
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
// --- Core KTX for WindowInsetsControllerCompat ---
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
// --- Compose UI & Foundation ---
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // Compose 색상 정의용
import androidx.compose.ui.platform.LocalView // SideEffect에서 View를 가져오기 위해
import androidx.compose.ui.tooling.preview.Preview
// --- Lifecycle & ViewModel ---
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
// --- Navigation ---
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
// --- Coroutines ---
import kotlinx.coroutines.flow.collectLatest
// --- Project Specific ---
import com.example.mqbl.navigation.Screen
import com.example.mqbl.navigation.bottomNavItems
import com.example.mqbl.service.CommunicationService
import com.example.mqbl.ui.ble.BleScreen
import com.example.mqbl.ui.ble.BleViewModel
// --- 수정: MQTT 관련 import를 TCP/IP 관련 import로 변경 ---
import com.example.mqbl.ui.tcp.TcpScreen // MqttScreen -> TcpScreen
import com.example.mqbl.ui.tcp.TcpViewModel // MqttViewModel -> TcpViewModel
// --- 수정 끝 ---
import com.example.mqbl.ui.settings.SettingsScreen
// import com.example.mqbl.ui.theme.MQBLTheme // 필요시 테마 import


// --- 다크 모드 및 라이트 모드 색상 정의 ---
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBB86FC),
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFF3700B3),
    background = Color(0xFF121212), // 어두운 배경
    surface = Color(0xFF1E1E1E),   // 컴포넌트 표면 (로그 창 배경보다 약간 밝게)
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = Color.White,    // 배경 위 텍스트
    onSurface = Color.White        // 표면 위 텍스트
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6200EE),
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFF3700B3),
    background = Color(0xFFFFFFFF), // 밝은 배경
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = Color.Black,    // 배경 위 텍스트
    onSurface = Color.Black        // 표면 위 텍스트
)

// --- 앱 테마 함수 정의 ---
@Composable
fun MQBLTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorScheme
    } else {
        LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !darkTheme
            // 상태 표시줄 배경색은 테마의 surface 색상을 따르도록 하거나,
            // themes.xml의 android:statusBarColor 설정을 따르도록 할 수 있습니다.
            // 여기서는 아이콘 색상만 제어합니다.
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// 기본 Typography
val Typography = Typography()


class MainActivity : ComponentActivity() {

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Log.d("MainActivity", "Permission Result Received: $permissions")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        // 상태 표시줄 아이콘 색상은 MQBLTheme 내부에서 동적으로 제어

        startCommunicationService()
        setContent {
            MQBLTheme {
                // --- 추가: 최상위 Surface로 전체 배경색 적용 ---
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background // 테마의 배경색 사용
                ) {
                    MainAppNavigation(
                        requestPermissions = { permissionsToRequest ->
                            requestMultiplePermissionsLauncher.launch(permissionsToRequest)
                        }
                    )
                }
                // --- 추가 끝 ---
            }
        }
    }

    private fun startCommunicationService() {
        val serviceIntent = Intent(this, CommunicationService::class.java)
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
fun MainAppNavigation(requestPermissions: (Array<String>) -> Unit) {
    val navController = rememberNavController()

    Scaffold( // Scaffold 자체는 배경색을 갖지만, 그 위의 Surface가 전체를 덮음
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                bottomNavItems.forEach { screen ->
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
            startDestination = Screen.Notifications.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Notifications.route) {
                val bleViewModel: BleViewModel = viewModel()
                val uiState by bleViewModel.uiState.collectAsStateWithLifecycle()
                val bondedDevices by bleViewModel.bondedDevices.collectAsStateWithLifecycle()
                val detectionLog by bleViewModel.detectionEventLog.collectAsStateWithLifecycle()

                BleScreen(
                    uiState = uiState,
                    bondedDevices = bondedDevices,
                    detectionLog = detectionLog,
                    onDeviceSelected = { /* SettingsScreen에서 처리 */ },
                    onSendValue = { /* SettingsScreen으로 이동 */ },
                    onRequestPermissions = { /* SettingsScreen에서 처리 */ },
                    onDisconnect = { /* SettingsScreen에서 처리 */ }
                )
            }
            composable(Screen.Tcp.route) { // TCP 탭
                val tcpViewModel: TcpViewModel = viewModel()
                val tcpUiState by tcpViewModel.tcpUiState.collectAsStateWithLifecycle()
                val receivedTcpMessages by tcpViewModel.receivedTcpMessages.collectAsStateWithLifecycle()
                val currentServerIp by tcpViewModel.serverIp.collectAsStateWithLifecycle()
                val currentServerPort by tcpViewModel.serverPort.collectAsStateWithLifecycle()

                TcpScreen(
                    uiState = tcpUiState,
                    receivedMessages = receivedTcpMessages,
                    currentServerIp = currentServerIp,
                    currentServerPort = currentServerPort,
                    onServerIpChange = tcpViewModel::updateServerIp,
                    onServerPortChange = tcpViewModel::updateServerPort,
                    onConnect = { /* SettingsScreen에서 처리 */ },
                    onDisconnect = { /* SettingsScreen에서 처리 */ },
                    onSendMessage = tcpViewModel::sendMessage
                )
            }
            composable(Screen.Settings.route) {
                val bleViewModel: BleViewModel = viewModel()
                val bleUiState by bleViewModel.uiState.collectAsStateWithLifecycle()
                val bondedDevices by bleViewModel.bondedDevices.collectAsStateWithLifecycle()

                val tcpViewModel: TcpViewModel = viewModel()
                val tcpUiState by tcpViewModel.tcpUiState.collectAsStateWithLifecycle()
                val currentServerIp by tcpViewModel.serverIp.collectAsStateWithLifecycle()
                val currentServerPort by tcpViewModel.serverPort.collectAsStateWithLifecycle()


                LaunchedEffect(key1 = bleViewModel) {
                    bleViewModel.permissionRequestEvent.collectLatest {
                        Log.d("SettingsScreen", "Permission request event received, launching dialog.")
                        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                            )
                        } else {
                            emptyArray()
                        }
                        if (permissionsToRequest.isNotEmpty()) {
                            requestPermissions(permissionsToRequest)
                        }
                    }
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, bleViewModel) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            Log.d("SettingsScreen", "ON_RESUME detected, re-checking BLE permissions.")
                            bleViewModel.checkOrRequestPermissions()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                SettingsScreen(
                    bleUiState = bleUiState,
                    bondedDevices = bondedDevices,
                    onDeviceSelected = bleViewModel::connectToDevice,
                    onRequestBlePermissions = bleViewModel::checkOrRequestPermissions,
                    onBleDisconnect = bleViewModel::disconnect,
                    onSendVibrationValue = bleViewModel::sendValue,
                    tcpUiState = tcpUiState,
                    currentServerIp = currentServerIp,
                    currentServerPort = currentServerPort,
                    onServerIpChange = tcpViewModel::updateServerIp,
                    onServerPortChange = tcpViewModel::updateServerPort,
                    onTcpConnect = tcpViewModel::connect,
                    onTcpDisconnect = tcpViewModel::disconnect
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Light Mode Preview")
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark Mode Preview")
@Composable
fun DefaultPreview() {
    MQBLTheme {
        MainAppNavigation(requestPermissions = {})
    }
}
