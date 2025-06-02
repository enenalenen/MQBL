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
import com.example.mqbl.ui.tcp.TcpScreen
import com.example.mqbl.ui.tcp.TcpViewModel
import com.example.mqbl.ui.theme.MQBLTheme
import com.example.mqbl.ui.wifidirect.WifiDirectViewModel
import kotlinx.coroutines.flow.collectLatest


// --- 다크 모드 및 라이트 모드 색상 정의 (기존과 동일 또는 테마 파일에서 가져오기) ---
// private val DarkColorScheme = darkColorScheme(...)
// private val LightColorScheme = lightColorScheme(...)

class MainActivity : ComponentActivity() {

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            Log.d("MainActivity", "Permission Result Received: $permissions")
            // 각 ViewModel에 권한 결과 알림 (선택 사항, ViewModel에서 재확인 가능)
            // bleViewModel?.onPermissionsResult(permissions)
            // wifiDirectViewModel?.onPermissionsResult(permissions)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        startCommunicationService()
        setContent {
            MQBLTheme { // 실제 정의된 테마 사용
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
            startForegroundService(serviceIntent)
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

                val wifiDirectViewModel: WifiDirectViewModel = viewModel() // Wi-Fi Direct ViewModel 추가
                val wifiDirectUiState by wifiDirectViewModel.wifiDirectUiState.collectAsStateWithLifecycle()


                // BLE 권한 요청 이벤트 리스너
                LaunchedEffect(key1 = bleViewModel) {
                    bleViewModel.permissionRequestEvent.collectLatest {
                        Log.d("SettingsScreen", "BLE Permission request event received, launching dialog.")
                        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.ACCESS_FINE_LOCATION // BLE 스캔에 위치 권한도 필요할 수 있음
                            )
                        } else {
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) // 구버전용
                        }
                        if (permissionsToRequest.isNotEmpty()) {
                            requestPermissions(permissionsToRequest)
                        }
                    }
                }

                // Wi-Fi Direct 권한 요청 함수 (SettingsScreen에서 호출)
                val requestWifiDirectPerms = {
                    Log.d("SettingsScreen", "Wi-Fi Direct Permission request initiated from UI.")
                    val wdPermissions = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION // Wi-Fi Direct에 필수
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        wdPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }
                    requestPermissions(wdPermissions.toTypedArray())
                }


                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, bleViewModel, wifiDirectViewModel) { // wifiDirectViewModel 추가
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            Log.d("SettingsScreen", "ON_RESUME detected, re-checking permissions.")
                            bleViewModel.checkOrRequestPermissions()
                            wifiDirectViewModel.discoverPeers() // 화면 재진입 시 피어 검색 시도 (서비스에서 권한 확인 후 실제 동작)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                SettingsScreen(
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
    MQBLTheme { // 실제 정의된 테마 사용
        MainAppNavigation(requestPermissions = {})
    }
}
