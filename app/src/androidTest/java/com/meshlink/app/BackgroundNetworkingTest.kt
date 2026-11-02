package com.meshlink.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in device test: enable Bluetooth and grant nearby/location permissions before running. */
class BackgroundNetworkingTest {
    @Test fun backgroundRecreationReturnAndExplicitStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MeshLinkApp
        val required = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        assumeTrue(required.all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED })
        assumeTrue((app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true)
        val repository = app.meshRepository
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { repository.requestNetworkingStart() }
                await { repository.isNetworkingActive.value }
                val starts = repository.networkLogs.value.count { it.message.startsWith("Starting networking") }
                scenario.moveToState(Lifecycle.State.CREATED)
                Thread.sleep(1500)
                assertTrue(repository.isNetworkingActive.value)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                assertTrue(repository.isNetworkingActive.value)
                scenario.onActivity { repository.requestNetworkingStart() }
                Thread.sleep(500)
                assertEquals(starts, repository.networkLogs.value.count { it.message.startsWith("Starting networking") })
                scenario.onActivity { repository.requestNetworkingStop() }
                await { !repository.isNetworkingActive.value }
                assertTrue(repository.connectedNodes.value.isEmpty())
                assertTrue(repository.routes.value.isEmpty())
            } finally { instrumentation.runOnMainSync { repository.requestNetworkingStop() } }
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Timed out awaiting networking state", condition())
    }
}
