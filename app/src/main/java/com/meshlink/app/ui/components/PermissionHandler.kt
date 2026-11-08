package com.meshlink.app.ui.components

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.meshlink.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/** Passive status only: the Start transaction owns permission prompts and continuation. */
@Composable
fun PermissionHandler() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT < 31) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
        }
        while (true) {
            granted = permissions.all { ContextCompat.checkSelfPermission(context,it) == PackageManager.PERMISSION_GRANTED }
            delay(1000)
        }
    }
    Text(if (granted) "Permissions: granted" else "Permissions required — tap Start to grant access", color = TextSecondary)
}
