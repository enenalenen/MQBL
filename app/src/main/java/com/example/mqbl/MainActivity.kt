package com.example.mqbl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build // [신규 추가] Build import
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mqbl.navigation.Screen
import com.example.mqbl.navigation.bottomNavItems
import com.example.mqbl.service.CommunicationService
import com.example.mqbl.ui.main.MainScreen
import com.example.mqbl.ui.main.MainViewModel
import com.example.mqbl.ui.settings.SettingsScreen
import com.example.mqbl.ui.settings.SettingsViewModel
import com.example.mqbl.ui.tcp.TcpViewModel
import com.example.mqbl.ui.theme.MQBLTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // [수정 없음] 앱이 시작될 때 서비스 시작 (정상)
        startCommunicationService()
        setContent {
            MQBLTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppNavigation()
                }
            }
        }
    }

    // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
    // [수정] startForegroundService를 사용하도록 이 함수를 변경
    // ▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼▼
    private fun startCommunicationService() {
        val serviceIntent = Intent(this, CommunicationService::class.java)
        // 안드로이드 8.0 (Oreo) 이상인지 확인
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 포그라운드 서비스로 시작 (백그라운드 실행 보장)
            startForegroundService(serviceIntent)
            Log.i("MainActivity", "CommunicationService started (as ForegroundService).")
        } else {
            // 이전 버전에서는 startService 사용
            startService(serviceIntent)
            Log.i("MainActivity", "CommunicationService started (as Service).")
        }
    }
    // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲
}

@Composable
fun MainAppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val mainViewModel: MainViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val tcpViewModel: TcpViewModel = viewModel() // PC 서버 연결용

    // --- [수정 없음] 앱 시작 시 필요한 권한 요청 (기존 로직 유지) ---
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            permissions.entries.forEach {
                Log.d("MainActivity", "Permission: ${it.key}, Granted: ${it.value}")
            }
        }
    )
    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissionsToRequest.isNotEmpty()){
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }


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
            startDestination = Screen.Notifications.route, // [참고] 기존 코드의 Notifications.route를 유지
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Notifications.route) {
                val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
                val detectionLog by mainViewModel.detectionEventLog.collectAsStateWithLifecycle()
                val customSoundLog by mainViewModel.customSoundEventLog.collectAsStateWithLifecycle()

                MainScreen(
                    uiState = uiState,
                    detectionLog = detectionLog,
                    customSoundLog = customSoundLog,
                )
            }

            composable(Screen.Settings.route) {
                val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                val customKeywords by settingsViewModel.customKeywords.collectAsStateWithLifecycle()

                val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle()

                val serverTcpUiState by tcpViewModel.tcpUiState.collectAsStateWithLifecycle()
                val serverIp by settingsViewModel.serverIp.collectAsStateWithLifecycle()
                val serverPort by settingsViewModel.serverPort.collectAsStateWithLifecycle()

                val esp32Ip by settingsViewModel.esp32Ip.collectAsStateWithLifecycle()
                val esp32Port by settingsViewModel.esp32Port.collectAsStateWithLifecycle()

                SettingsScreen(
                    settingsUiState = settingsUiState,
                    onBackgroundExecutionToggled = settingsViewModel::toggleBackgroundExecution,
                    onPhoneMicModeToggled = settingsViewModel::togglePhoneMicMode,

                    // ▼▼▼ 추가/수정된 코드 (빠졌던 두 줄 추가) ▼▼T
                    onMicSensitivityChange = settingsViewModel::onMicSensitivityChange,
                    onMicSensitivityChangeFinished = settingsViewModel::onMicSensitivityChangeFinished,
                    // ▲▲▲ 추가/수정된 코드 ▲▲▲

                    customKeywords = customKeywords,
                    onCustomKeywordsChange = settingsViewModel::updateCustomKeywords,
                    onSaveCustomKeywords = settingsViewModel::saveCustomKeywords,

                    mainUiState = mainUiState,
                    onSendVibrationValue = settingsViewModel::sendVibrationValue,
                    onSendCommand = settingsViewModel::sendCommandToEsp32,

                    onStartRecording = settingsViewModel::startRecording,
                    onStopRecording = settingsViewModel::stopRecording,

                    serverTcpUiState = serverTcpUiState,
                    serverIp = serverIp,
                    serverPort = serverPort,
                    onServerIpChange = settingsViewModel::onServerIpChange,
                    onServerPortChange = settingsViewModel::onServerPortChange,
                    onSaveServerSettings = settingsViewModel::saveServerSettings,
                    onServerConnect = settingsViewModel::connectToServer,
                    onServerDisconnect = settingsViewModel::disconnectFromServer,

                    esp32ServerIp = esp32Ip,
                    esp32ServerPort = esp32Port,
                    onEsp32ServerIpChange = settingsViewModel::onEsp32IpChange,
                    onEsp32ServerPortChange = settingsViewModel::onEsp32PortChange,
                    onEsp32Connect = settingsViewModel::saveEsp32SettingsAndConnect,
                    onEsp32Disconnect = settingsViewModel::disconnectFromEsp32
                )
            }

            // [참고] 기존 코드에 TcpScreen이 없는 것으로 보이나,
            // 만약 TcpScreen 라우트가 필요하다면 여기에 추가해야 합니다.
            /*
            composable(Screen.Tcp.route) { // 예시: Screen.Tcp.route가 "tcp_screen"이라고 가정
                 // TcpScreen Composable 호출
                 val tcpUiState by tcpViewModel.tcpUiState.collectAsStateWithLifecycle()
                 val messages by tcpViewModel.receivedMessages.collectAsStateWithLifecycle()
                 TcpScreen(
                     uiState = tcpUiState,
                     messages = messages,
                     onSendMessage = tcpViewModel::sendMessage,
                     onClearMessages = tcpViewModel::clearMessages
                 )
            }
            */
        }
    }
}