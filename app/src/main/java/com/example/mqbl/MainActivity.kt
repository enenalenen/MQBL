package com.example.mqbl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.example.mqbl.ui.ble.BleScreen
import com.example.mqbl.ui.ble.BleViewModel
import com.example.mqbl.ui.settings.SettingsScreen
import com.example.mqbl.ui.settings.SettingsViewModel
import com.example.mqbl.ui.tcp.TcpViewModel
import com.example.mqbl.ui.theme.MQBLTheme
import com.example.mqbl.ui.wifidirect.WifiDirectViewModel
import kotlinx.coroutines.flow.collectLatest


class MainActivity : ComponentActivity() {

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Log.d("MainActivity", "Permission Result Received: $permissions")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        startCommunicationService()

        setContent {
            MQBLTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppNavigation(
                        requestPermissions = { permissionsToRequest ->
                            requestMultiplePermissionsLauncher.launch(permissionsToRequest)
                        }
                    )
                }
            }
        }
    }

    private fun startCommunicationService() {
        val serviceIntent = Intent(this, CommunicationService::class.java)
        startService(serviceIntent)
        Log.i("MainActivity", "CommunicationService started.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppNavigation(requestPermissions: (Array<String>) -> Unit) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // --- ▼▼▼ ViewModel 인스턴스를 NavHost 바깥으로 이동 ▼▼▼ ---
    // SettingsScreen과 NotificationsScreen이 동일한 ViewModel 인스턴스를 공유하도록 함
    val bleViewModel: BleViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val tcpViewModel: TcpViewModel = viewModel()

    // --- ▼▼▼ 앱 시작 시 알림 권한만 별도로 요청 ▼▼▼ ---
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                if (!isGranted) {
                    // 사용자에게 권한이 왜 필요한지 설명하는 UI를 보여줄 수 있습니다.
                    Log.w("MainAppNavigation", "Notification permission was denied.")
                }
            }
        )
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // --- ▼▼▼ ViewModel의 권한 요청 이벤트를 구독하여 처리하는 로직으로 복원 및 개선 ▼▼▼ ---
    LaunchedEffect(key1 = bleViewModel) {
        bleViewModel.permissionRequestEvent.collectLatest {
            Log.d("MainActivity", "Permission request event received from BleViewModel.")

            val permissionsToRequest = mutableListOf<String>()

            // 1. BLE 권한 추가 (버전별로 올바르게 분기)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION) // 스캔을 위해 위치 권한도 함께 요청
            } else {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            // 2. 파일 쓰기 권한 추가 (안드로이드 9 이하)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }

            // 이미 허용된 권한은 제외하고, 실제로 요청이 필요한 권한만 추림
            val permissionsToActuallyRequest = permissionsToRequest.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()


            if (permissionsToActuallyRequest.isNotEmpty()) {
                Log.d("MainActivity", "Requesting permissions: ${permissionsToActuallyRequest.joinToString()}")
                requestPermissions(permissionsToActuallyRequest)
            }
        }
    }
    // --- ▲▲▲ 로직 수정 끝 ▲▲▲ ---


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
                // 공유된 ViewModel 사용
                val uiState by bleViewModel.uiState.collectAsStateWithLifecycle()
                val bondedDevices by bleViewModel.bondedDevices.collectAsStateWithLifecycle()
                val detectionLog by bleViewModel.detectionEventLog.collectAsStateWithLifecycle()
                val customSoundLog by bleViewModel.customSoundEventLog.collectAsStateWithLifecycle()

                BleScreen(
                    uiState = uiState,
                    bondedDevices = bondedDevices,
                    detectionLog = detectionLog,
                    customSoundLog = customSoundLog,
                    onDeviceSelected = { },
                    onSendValue = { },
                    onRequestPermissions = { },
                    onDisconnect = { }
                )
            }

            composable(Screen.Settings.route) {
                // 공유된 ViewModel 사용
                val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                val customKeywords by settingsViewModel.customKeywords.collectAsStateWithLifecycle()

                val bleUiState by bleViewModel.uiState.collectAsStateWithLifecycle()
                val bondedDevices by bleViewModel.bondedDevices.collectAsStateWithLifecycle()
                val scannedDevices by bleViewModel.scannedDevices.collectAsStateWithLifecycle()

                val tcpUiState by tcpViewModel.tcpUiState.collectAsStateWithLifecycle()
                val tcpServerIp by settingsViewModel.tcpServerIp.collectAsStateWithLifecycle()
                val tcpServerPort by settingsViewModel.tcpServerPort.collectAsStateWithLifecycle()

                // --- ▼▼▼ 화면이 다시 보일 때마다 권한을 다시 체크하도록 수정 ▼▼▼ ---
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, bleViewModel) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            Log.d("SettingsScreen", "ON_RESUME detected, re-checking permissions.")
                            bleViewModel.checkOrRequestPermissions()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }
                // --- ▲▲▲ 수정 끝 ▲▲▲ ---

                SettingsScreen(
                    settingsUiState = settingsUiState,
                    onBackgroundExecutionToggled = settingsViewModel::toggleBackgroundExecution,

                    customKeywords = customKeywords,
                    onCustomKeywordsChange = settingsViewModel::updateCustomKeywords,
                    onSaveCustomKeywords = settingsViewModel::saveCustomKeywords,

                    bleUiState = bleUiState,
                    bondedDevices = bondedDevices,
                    scannedDevices = scannedDevices,
                    onDeviceSelected = bleViewModel::connectToDevice,
                    onRequestBlePermissions = bleViewModel::checkOrRequestPermissions,
                    onBleDisconnect = bleViewModel::disconnect,
                    onSendVibrationValue = bleViewModel::sendValue,
                    onStartScan = bleViewModel::startScan,
                    onStopScan = bleViewModel::stopScan,
                    onPairDevice = bleViewModel::pairWithDevice,
                    onSendCommand = bleViewModel::sendBleCommand,

                    onStartRecording = settingsViewModel::startRecording,
                    onStopRecording = settingsViewModel::stopRecording,

                    tcpUiState = tcpUiState,
                    tcpServerIp = tcpServerIp,
                    tcpServerPort = tcpServerPort,
                    onTcpServerIpChange = settingsViewModel::onTcpServerIpChange,
                    onTcpServerPortChange = settingsViewModel::onTcpServerPortChange,
                    onSaveTcpSettings = settingsViewModel::saveTcpSettings,
                    onTcpConnect = { tcpViewModel.connect(tcpServerIp, tcpServerPort.toIntOrNull() ?: 0) },
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

