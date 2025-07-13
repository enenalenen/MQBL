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
    var showNotificationPermissionDialog by remember { mutableStateOf(false) }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                if (!isGranted) {
                    showNotificationPermissionDialog = true
                }
            }
        )

        LaunchedEffect(Unit) {
            val permissionStatus = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            if (permissionStatus == PackageManager.PERMISSION_DENIED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (showNotificationPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showNotificationPermissionDialog = false },
                title = { Text("알림 권한 필요") },
                text = { Text("위험 상황(사이렌, 경적 등)을 즉시 알려면 알림 권한이 반드시 필요합니다. 설정에서 권한을 허용해주세요.") },
                confirmButton = {
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            val uri = Uri.fromParts("package", context.packageName, null)
                            intent.data = uri
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
                    onDeviceSelected = { },
                    onSendValue = { },
                    onRequestPermissions = { },
                    onDisconnect = { }
                )
            }

            composable(Screen.Settings.route) {
                val settingsViewModel: SettingsViewModel = viewModel()
                val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()

                val bleViewModel: BleViewModel = viewModel()
                val bleUiState by bleViewModel.uiState.collectAsStateWithLifecycle()
                val bondedDevices by bleViewModel.bondedDevices.collectAsStateWithLifecycle()
                val scannedDevices by bleViewModel.scannedDevices.collectAsStateWithLifecycle()

                val tcpViewModel: TcpViewModel = viewModel()
                val tcpUiState by tcpViewModel.tcpUiState.collectAsStateWithLifecycle()
                val currentServerIp by tcpViewModel.serverIp.collectAsStateWithLifecycle()
                val currentServerPort by tcpViewModel.serverPort.collectAsStateWithLifecycle()

                // --- ▼▼▼ Wi-Fi Direct ViewModel 및 관련 코드 주석 처리 ▼▼▼ ---
                // val wifiDirectViewModel: WifiDirectViewModel = viewModel()
                // val wifiDirectUiState by wifiDirectViewModel.wifiDirectUiState.collectAsStateWithLifecycle()


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

                /*
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
                */


                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, bleViewModel) { // wifiDirectViewModel 제거
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            Log.d("SettingsScreen", "ON_RESUME detected, re-checking permissions.")
                            bleViewModel.checkOrRequestPermissions()
                            // wifiDirectViewModel.discoverPeers() // 주석 처리
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
                    scannedDevices = scannedDevices,
                    onDeviceSelected = bleViewModel::connectToDevice,
                    onRequestBlePermissions = bleViewModel::checkOrRequestPermissions,
                    onBleDisconnect = bleViewModel::disconnect,
                    onSendVibrationValue = bleViewModel::sendValue,
                    onStartScan = bleViewModel::startScan,
                    onStopScan = bleViewModel::stopScan,
                    onPairDevice = bleViewModel::pairWithDevice,
                    // TCP
                    tcpUiState = tcpUiState,
                    currentServerIp = currentServerIp,
                    currentServerPort = currentServerPort,
                    onServerIpChange = tcpViewModel::updateServerIp,
                    onServerPortChange = tcpViewModel::updateServerPort,
                    onTcpConnect = tcpViewModel::connect,
                    onTcpDisconnect = tcpViewModel::disconnect,
                    // Wi-Fi Direct 파라미터 전달 부분 주석 처리
                    /*
                    wifiDirectUiState = wifiDirectUiState,
                    onRequestWifiDirectPermissions = requestWifiDirectPerms,
                    onDiscoverWifiDirectPeers = wifiDirectViewModel::discoverPeers,
                    onConnectToWifiDirectPeer = wifiDirectViewModel::connectToPeer,
                    onDisconnectWifiDirect = wifiDirectViewModel::disconnect,
                    onSendWifiDirectMessage = wifiDirectViewModel::sendWifiDirectMessage
                    */
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
