package com.meshlink.app

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.meshlink.app.data.notifications.AndroidMessageNotifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.meshlink.app.data.repository.TransportMode
import com.meshlink.app.domain.network.BluetoothReadiness
import com.meshlink.app.domain.network.NetworkingStartup
import com.meshlink.app.domain.network.StartupAction
import com.meshlink.app.presentation.viewmodels.MeshViewModel
import com.meshlink.app.ui.screens.MainScreen
import com.meshlink.app.ui.theme.MeshLinkTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MeshViewModel by viewModels {
        MeshViewModel.provideFactory((application as MeshLinkApp).meshRepository)
    }
    private var enableTimeout: Job? = null
    private val notificationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.notificationPromptPending = false
        viewModel.refreshNotificationStatus()
    }
    private fun requestMessageNotifications() {
        val preferences = viewModel.preferences.value
        if (Build.VERSION.SDK_INT < 33 || !preferences.notificationsEnabled || preferences.notificationPermissionAsked || viewModel.notificationPromptPending) return
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        viewModel.markNotificationPermissionAsked()
        viewModel.notificationPromptPending = true
        notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun openSystemNotificationSettings() {
        val settings = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))
        startActivity(settings)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AndroidMessageNotifier.target(intent)?.let(viewModel::openNotification)
    }
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perform(viewModel.startup.permissionsResult(readiness()))
    }
    private val enableRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        perform(viewModel.startup.enableResult(result.resultCode == Activity.RESULT_OK, readiness()))
        awaitBluetoothIfNeeded()
    }
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            perform(viewModel.startup.bluetoothChanged(readiness()))
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF && viewModel.uiState.value.isNetworkingActive) viewModel.stopNetworking()
        }
    }
    private fun permissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
        if (viewModel.uiState.value.transportMode == TransportMode.NEARBY_CONNECTIONS) {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.distinct().toTypedArray()
    private fun readiness(): BluetoothReadiness {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val supported = adapter != null && packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val permitted = permissions().all { ContextCompat.checkSelfPermission(this,it) == PackageManager.PERMISSION_GRANTED }
        val enabled = if (permitted) try { adapter?.isEnabled == true } catch (_: SecurityException) { false } else false
        return BluetoothReadiness(supported,permitted,enabled)
    }
    private fun toggleNetworking() {
        if (viewModel.startup.busy) return
        if (viewModel.uiState.value.isNetworkingActive) {
            viewModel.startup.cancel(); viewModel.syncStartup(); viewModel.stopNetworking()
        } else perform(viewModel.startup.request(readiness()))
    }
    private fun perform(action: StartupAction) {
        try {
            when(action) {
                StartupAction.REQUEST_PERMISSIONS -> permissionRequest.launch(permissions())
                StartupAction.REQUEST_ENABLE -> enableRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                StartupAction.START -> startWhenResumed()
                StartupAction.NONE -> Unit
            }
        } catch (e: Exception) {
            viewModel.startup.cancel("Unable to open the Bluetooth system prompt: ${e.message}. Enable Bluetooth in Settings, then tap Start.")
        }
        viewModel.syncStartup()
    }
    private fun startWhenResumed() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (viewModel.startup.phase != NetworkingStartup.Phase.STARTING) return
        enableTimeout?.cancel()
        val ready = readiness()
        if (!ready.permitted || !ready.enabled) viewModel.startup.cancel("Bluetooth or permissions changed. Tap Start to try again.")
        else {
            viewModel.startApprovedNetworking()
            viewModel.startup.finished()
            if (viewModel.uiState.value.transportMode == TransportMode.BLE_GATT) requestMessageNotifications()
        }
        viewModel.syncStartup()
    }
    private fun awaitBluetoothIfNeeded() {
        if (viewModel.startup.phase != NetworkingStartup.Phase.WAITING_FOR_ON) return
        enableTimeout?.cancel()
        enableTimeout = lifecycleScope.launch {
            delay(15_000)
            if (viewModel.startup.phase == NetworkingStartup.Phase.WAITING_FOR_ON) {
                viewModel.startup.cancel("Bluetooth did not turn on in time. Tap Start to retry."); viewModel.syncStartup()
            }
        }
    }
    override fun onPostResume() { super.onPostResume(); startWhenResumed(); awaitBluetoothIfNeeded(); viewModel.refreshNotificationStatus() }
    override fun onDestroy() { unregisterReceiver(bluetoothReceiver); super.onDestroy() }
    override fun onCreate(savedInstanceState: Bundle?) {
        val savedPreferences = (application as MeshLinkApp).meshRepository.appPreferences.state.value
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setTheme(if (savedPreferences.theme.isDark(systemDark)) R.style.Theme_MeshLink_Dark else R.style.Theme_MeshLink_Light)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) AndroidMessageNotifier.target(intent)?.let(viewModel::openNotification)
        // Activity Result can arrive while STARTED; onPostResume may precede Lifecycle ON_RESUME.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) startWhenResumed()
        })
        ContextCompat.registerReceiver(this, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        setContent {
            val preferences by viewModel.preferences.collectAsState()
            MeshLinkTheme(darkTheme = preferences.theme.isDark(isSystemInDarkTheme())) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(viewModel = viewModel, onToggleNetworking = ::toggleNetworking, onNotificationPermission = ::requestMessageNotifications, onSystemNotificationSettings = ::openSystemNotificationSettings)
                }
            }
        }
    }
}
