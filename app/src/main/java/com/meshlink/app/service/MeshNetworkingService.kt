package com.meshlink.app.service

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.meshlink.app.MainActivity
import com.meshlink.app.MeshLinkApp
import com.meshlink.app.R
import com.meshlink.app.domain.network.MeshSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** The application repository is shared with the UI; only this service owns its active lease. */
class MeshNetworkingService : Service() {
    private val repository get() = (application as MeshLinkApp).meshRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MeshSession
    private var registered = false
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                repository.bluetoothStatus.value = "Off — tap Start after enabling Bluetooth"
                shutdown()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        session = MeshSession({ repository.startNetworking() }, { repository.stopNetworking() })
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Mesh networking", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || intent == null) { shutdown(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(repository.connectedNodes.value.size),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            if (!session.active) {
                session.start()
                scope.launch {
                    combine(repository.connectedNodes, repository.isNetworkingActive) { nodes, active -> nodes.size to active }
                        .collect { (count, active) ->
                            if (!active) {
                                // Identity/demo reconfiguration can synchronously replace the transport.
                                delay(250)
                                if (!repository.isNetworkingActive.value) shutdown()
                            }
                            else getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(count))
                        }
                }
            }
        } catch (error: Exception) {
            repository.bluetoothStatus.value = "Networking stopped: ${error.message}"
            shutdown()
        }
        // Process death requires an explicit fresh Start; never silently recreate stale GATT sessions.
        return START_NOT_STICKY
    }
    private fun notification(neighbors: Int): Notification {
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 41, Intent(this, MeshNetworkingService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle("MeshLink — Mesh networking active")
            .setContentText("Connected neighbors: $neighbors")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Open MeshLink", open).addAction(0, "Stop Networking", stop).build()
    }
    private fun shutdown() {
        session.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        if (registered) unregisterReceiver(bluetoothReceiver)
        session.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "mesh_networking"
        private const val NOTIFICATION = 7041
        private const val STOP = "com.meshlink.app.STOP_NETWORKING"
    }
}
