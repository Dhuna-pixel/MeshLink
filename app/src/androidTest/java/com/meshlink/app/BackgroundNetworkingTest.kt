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
        for (permission in required) {
            if (ContextCompat.checkSelfPermission(app, permission) != PackageManager.PERMISSION_GRANTED) {
                instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} $permission").use { fd ->
                    java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
                }
            }
        }
        assumeTrue(required.all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED })
        assumeTrue((app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true)
        val repository = app.meshRepository
        val original = repository.currentNode.value
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { repository.requestNetworkingStart() }
                await { repository.isNetworkingActive.value }
                val starts = repository.networkLogs.value.count { it.message.startsWith("Starting networking") }
                scenario.onActivity { repository.updateNodeIdentity(original.nodeId, "Field team", "At the shelter") }
                val revised = repository.identity.profile(repository.currentNode.value).profile
                assertEquals("Field team", revised.card.name)
                assertEquals("At the shelter", revised.about)
                assertEquals(original.nodeId, revised.card.id)
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
            } finally { instrumentation.runOnMainSync {
                repository.updateNodeIdentity(original.nodeId, original.nodeName, original.about)
                repository.requestNetworkingStop()
            } }
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Timed out awaiting networking state", condition())
    }
}
