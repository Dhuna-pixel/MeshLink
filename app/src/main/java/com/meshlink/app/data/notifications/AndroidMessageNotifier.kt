package com.meshlink.app.data.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meshlink.app.MainActivity
import com.meshlink.app.R
import com.meshlink.app.domain.notifications.MessageNotification
import com.meshlink.app.domain.notifications.NotificationTarget

class AndroidMessageNotifier(private val context: Context) {
    companion object {
        const val CHANNEL = "meshlink_messages"
        const val ACTION_OPEN_MESSAGE = "com.meshlink.app.OPEN_RECEIVED_MESSAGE"
        fun target(intent: Intent?): NotificationTarget? {
            val uri = intent?.data ?: return null
            if (intent.action != ACTION_OPEN_MESSAGE || uri.scheme != "meshlink" || uri.authority != "received" || uri.pathSegments.size != 2) return null
            val (sender,message) = uri.pathSegments
            if (sender.length !in 1..80 || message.length !in 1..128) return null
            return NotificationTarget(message,sender)
        }
    }
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL,"MeshLink Messages",NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Messages delivered to this device through the BLE mesh"
            }
        )
    }
    fun permitted(): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
    fun status(): String = when {
        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> "Notification permission not granted"
        !NotificationManagerCompat.from(context).areNotificationsEnabled() -> "Notifications blocked in Android settings"
        Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE -> "MeshLink Messages channel blocked"
        else -> "System notifications allowed"
    }
    fun cancelAll() = manager.cancelAll()
    fun post(message: MessageNotification) {
        if (!permitted()) return
        val uri = Uri.Builder().scheme("meshlink").authority("received").appendPath(message.target.senderId).appendPath(message.target.messageId).build()
        val intent = Intent(context,MainActivity::class.java).setAction(ACTION_OPEN_MESSAGE).setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val publicVersion = NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle("MeshLink • New message").setContentText("Open MeshLink to read your message").build()
        val notification = NotificationCompat.Builder(context,CHANNEL)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle("MeshLink • New message")
            .setSubText(message.sender)
            .setContentText("${message.sender}: ${message.text.take(180)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${message.sender}\n${message.text}"))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(if (message.privatePreview) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(pending).build()
        try { manager.notify(uri.toString(),0,notification) } catch (_: SecurityException) { /* Permission may be revoked between check and post. */ }
    }
}
