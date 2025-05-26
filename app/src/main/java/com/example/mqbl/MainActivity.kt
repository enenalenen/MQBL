package com.example.mqbl

// --- Android SDK ---
import android.Manifest
import android.content.Intent
// import android.graphics.Color as AndroidGraphicsColor // 더 이상 직접 사용 안 함
import android.os.Build
import android.os.Bundle
import android.util.Log
// import android.view.WindowManager // 테마에서 처리하므로 직접 플래그 설정 안 함
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
import androidx.compose.runtime.SideEffect // SideEffect로 상태 표시줄 아이콘 색상 동적 변경
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // Compose 색상 정의용
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
import com.example.mqbl.ui.mqtt.MqttScreen
import com.example.mqbl.ui.mqtt.MqttViewModel
import com.example.mqbl.ui.settings.SettingsScreen
import androidx.compose.ui.platform.LocalView // SideEffect에서 View를 가져오기 위해
import android.app.Activity // view.context를 Activity로 캐스팅하기 위해

// --- 다크 모드 및 라이트 모드 색상 정의 ---
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBB86FC),
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFF3700B3),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6200EE),
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFF3700B3),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black
)

// --- 앱 테마 함수 정의 ---
@Composable
fun MQBLTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // 시스템 설정 감지
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorScheme
    } else {
        LightColorScheme
    }

    // --- 상태 표시줄 아이콘 색상 동적 변경 ---
    // SideEffect를 사용하여 Composable이 리컴포지션될 때마다 실행
    // (또는 LaunchedEffect를 사용하여 darkTheme 값이 변경될 때만 실행 가능)
    val view = LocalView.current // Accompanist SystemUIController 대신 직접 WindowInsetsController 사용
    if (!view.isInEditMode) { // 프리뷰 모드가 아닐 때만 실행
        SideEffect {
            val window = (view.context as Activity).window
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !darkTheme
            // !darkTheme: 라이트 모드(darkTheme=false)일 때 true (어두운 아이콘),
            //             다크 모드(darkTheme=true)일 때 false (밝은 아이콘)
        }
    }
    // ------------------------------------

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

        // --- 시스템 상태 표시줄 기본 설정 ---
        // 앱 컨텐츠가 시스템 바 뒤로 그려지지 않도록 설정
        WindowCompat.setDecorFitsSystemWindows(window, true)
        // 상태 표시줄 배경색은 테마에서 android:statusBarColor로 설정하거나,
        // 여기서 window.statusBarColor = AndroidGraphicsColor.TRANSPARENT 등으로 설정 후
        // MQBLTheme 내부 또는 각 화면 최상단에 배경색을 가진 Composable을 배치하여 제어할 수도 있음.
        // 현재는 themes.xml의 android:statusBarColor = @android:color/black 설정을 우선 따름.
        // 아이콘 색상은 MQBLTheme 내부에서 동적으로 제어.
        // ---------------------------------

        startCommunicationService()
        setContent {
            MQBLTheme { // MQBLTheme이 상태 표시줄 아이콘 색상 제어
                MainAppNavigation(
                    requestPermissions = { permissionsToRequest ->
                        requestMultiplePermissionsLauncher.launch(permissionsToRequest)
                    }
                )
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

    Scaffold(
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
            composable(Screen.Mqtt.route) {
                val mqttViewModel: MqttViewModel = viewModel()
                val uiState by mqttViewModel.uiState.collectAsStateWithLifecycle()
                val receivedMessages by mqttViewModel.receivedMessages.collectAsStateWithLifecycle()

                MqttScreen(
                    uiState = uiState,
                    receivedMessages = receivedMessages,
                    onConnect = { /* SettingsScreen에서 처리 */ },
                    onDisconnect = { /* SettingsScreen에서 처리 */ },
                    onPublish = mqttViewModel::publish
                )
            }
            composable(Screen.Settings.route) {
                val bleViewModel: BleViewModel = viewModel()
                val bleUiState by bleViewModel.uiState.collectAsStateWithLifecycle()
                val bondedDevices by bleViewModel.bondedDevices.collectAsStateWithLifecycle()

                val mqttViewModel: MqttViewModel = viewModel()
                val mqttUiState by mqttViewModel.uiState.collectAsStateWithLifecycle()

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
                    mqttUiState = mqttUiState,
                    onMqttConnect = mqttViewModel::connect,
                    onMqttDisconnect = mqttViewModel::disconnect
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
