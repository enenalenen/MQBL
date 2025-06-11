package com.example.mqbl

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
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
import com.example.mqbl.ui.tcp.TcpScreen
import com.example.mqbl.ui.tcp.TcpViewModel
import com.example.mqbl.ui.theme.MQBLTheme
import com.example.mqbl.ui.wifidirect.WifiDirectViewModel
import kotlinx.coroutines.flow.collectLatest

import android.net.Uri // Uri import 추가
import android.provider.Settings // Settings import 추가
import androidx.activity.compose.rememberLauncherForActivityResult // 추가
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission // 추가
import androidx.compose.material3.AlertDialog // AlertDialog import 추가
import androidx.compose.material3.Button // Button import 추가
import androidx.compose.runtime.mutableStateOf // mutableStateOf import 추가
import androidx.compose.runtime.remember // remember import 추가
import androidx.compose.runtime.setValue // setValue import 추가
import androidx.core.content.ContextCompat // ContextCompat import 추가
import android.content.pm.PackageManager // PackageManager import 추가
import androidx.compose.ui.platform.LocalContext // LocalContext import 추가


class MainActivity : ComponentActivity() {

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Log.d("MainActivity", "Permission Result Received: $permissions")
            // 권한 요청 결과는 각 ViewModel 또는 화면의 onResume에서 다시 확인하여 처리
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        // --- 수정: 서비스 시작 로직 변경 ---
        // 이제 서비스는 설정 값에 따라 스스로 포그라운드 여부를 결정하므로,
        // 여기서는 단순히 서비스를 시작하기만 하면 됩니다.
        startCommunicationService()
        // --- 수정 끝 ---

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
        // startForegroundService 대신 startService 사용.
        // 서비스 내부에서 설정에 따라 포그라운드로 전환할지 결정함.
        startService(serviceIntent)
        Log.i("MainActivity", "CommunicationService started.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppNavigation(requestPermissions: (Array<String>) -> Unit) {
    val navController = rememberNavController()

    val context = LocalContext.current
    var showNotificationPermissionDialog by remember { mutableStateOf(false) }

    // 안드로이드 13 (TIRAMISU) 이상에서만 알림 권한 요청 로직 실행
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // 알림 권한 상태를 확인하는 런처
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = RequestPermission(),
            onResult = { isGranted ->
                if (!isGranted) {
                    // 사용자가 권한을 거부한 경우, 설정으로 유도하는 다이얼로그를 띄움
                    showNotificationPermissionDialog = true
                }
            }
        )

        // 화면이 처음 그려질 때 알림 권한 확인
        LaunchedEffect(Unit) {
            val permissionStatus = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            if (permissionStatus == PackageManager.PERMISSION_DENIED) {
                // 권한이 없다면 시스템 권한 요청 팝업을 띄움
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 권한이 최종적으로 거부되어 설정으로 유도해야 할 때 다이얼로그 표시
        if (showNotificationPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showNotificationPermissionDialog = false },
                title = { Text("알림 권한 필요") },
                text = { Text("위험 상황(사이렌, 경적 등)을 즉시 알려면 알림 권한이 반드시 필요합니다. 설정에서 권한을 허용해주세요.") },
                confirmButton = {
                    Button(
                        onClick = {
                            // 앱의 상세 설정 화면으로 이동하는 인텐트(Intent) 생성
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            val uri = Uri.fromParts("package", context.packageName, null)
                            intent.data = uri
                            // 설정 화면으로 이동
                            context.startActivity(intent)
                            showNotificationPermissionDialog = false
                        }
                    ) {
                        Text("설정으로 이동")
                    }
                },
                dismissButton = {
                    Button(onClick = { showNotificationPermissionDialog = false }) {
                        Text("닫기")
                    }
                }
            )
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
                // --- 수정: SettingsViewModel 추가 및 연결 ---
                val settingsViewModel: SettingsViewModel = viewModel()
                val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()

                val bleViewModel: BleViewModel = viewModel()
                val bleUiState by bleViewModel.uiState.collectAsStateWithLifecycle()
                val bondedDevices by bleViewModel.bondedDevices.collectAsStateWithLifecycle()

                val tcpViewModel: TcpViewModel = viewModel()
                val tcpUiState by tcpViewModel.tcpUiState.collectAsStateWithLifecycle()
                val currentServerIp by tcpViewModel.serverIp.collectAsStateWithLifecycle()
                val currentServerPort by tcpViewModel.serverPort.collectAsStateWithLifecycle()

                val wifiDirectViewModel: WifiDirectViewModel = viewModel()
                val wifiDirectUiState by wifiDirectViewModel.wifiDirectUiState.collectAsStateWithLifecycle()
                // --- 수정 끝 ---


                // BLE 권한 요청 이벤트 리스너
                LaunchedEffect(key1 = bleViewModel) {
                    bleViewModel.permissionRequestEvent.collectLatest {
                        Log.d("SettingsScreen", "BLE Permission request event received, launching dialog.")
                        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        } else {
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        if (permissionsToRequest.isNotEmpty()) {
                            requestPermissions(permissionsToRequest)
                        }
                    }
                }

                val requestWifiDirectPerms = {
                    Log.d("SettingsScreen", "Wi-Fi Direct Permission request initiated from UI.")
                    val wdPermissions = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        wdPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }
                    requestPermissions(wdPermissions.toTypedArray())
                }


                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, bleViewModel, wifiDirectViewModel) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            Log.d("SettingsScreen", "ON_RESUME detected, re-checking permissions.")
                            bleViewModel.checkOrRequestPermissions()
                            wifiDirectViewModel.discoverPeers()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                SettingsScreen(
                    // App Settings
                    settingsUiState = settingsUiState,
                    onBackgroundExecutionToggled = settingsViewModel::toggleBackgroundExecution,
                    // BLE
                    bleUiState = bleUiState,
                    bondedDevices = bondedDevices,
                    onDeviceSelected = bleViewModel::connectToDevice,
                    onRequestBlePermissions = bleViewModel::checkOrRequestPermissions,
                    onBleDisconnect = bleViewModel::disconnect,
                    onSendVibrationValue = bleViewModel::sendValue,
                    // TCP
                    tcpUiState = tcpUiState,
                    currentServerIp = currentServerIp,
                    currentServerPort = currentServerPort,
                    onServerIpChange = tcpViewModel::updateServerIp,
                    onServerPortChange = tcpViewModel::updateServerPort,
                    onTcpConnect = tcpViewModel::connect,
                    onTcpDisconnect = tcpViewModel::disconnect,
                    // Wi-Fi Direct
                    wifiDirectUiState = wifiDirectUiState,
                    onRequestWifiDirectPermissions = requestWifiDirectPerms,
                    onDiscoverWifiDirectPeers = wifiDirectViewModel::discoverPeers,
                    onConnectToWifiDirectPeer = wifiDirectViewModel::connectToPeer,
                    onDisconnectWifiDirect = wifiDirectViewModel::disconnect,
                    onSendWifiDirectMessage = wifiDirectViewModel::sendWifiDirectMessage
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
