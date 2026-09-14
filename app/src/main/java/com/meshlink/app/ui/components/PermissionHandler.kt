package com.meshlink.app.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.meshlink.app.ui.theme.MeshAmberWarning
import com.meshlink.app.ui.theme.MeshCyanPrimary
import com.meshlink.app.ui.theme.MeshDarkBackground
import com.meshlink.app.ui.theme.MeshDarkSurface
import com.meshlink.app.ui.theme.TextPrimary
import com.meshlink.app.ui.theme.TextSecondary

@Composable
fun PermissionHandler(
    onPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current

    val requiredPermissions = remember {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        list.toTypedArray()
    }

    var hasAllPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasAllPermissions = results.values.all { it }
        if (hasAllPermissions) {
            onPermissionsGranted()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasAllPermissions) {
            launcher.launch(requiredPermissions)
        } else {
            onPermissionsGranted()
        }
    }

    if (!hasAllPermissions) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(MeshDarkSurface, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MeshAmberWarning
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Permissions Required for Mesh Networking",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "MeshLink requires Bluetooth, Nearby Wi-Fi, and Location permissions to discover and form ad-hoc peer connections with nearby Android phones without internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { launcher.launch(requiredPermissions) },
                colors = ButtonDefaults.buttonColors(containerColor = MeshCyanPrimary, contentColor = MeshDarkBackground),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Grant Permissions")
            }
        }
    }
}
