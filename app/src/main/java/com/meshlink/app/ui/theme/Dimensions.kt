package com.meshlink.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object MeshSpace {
    val xs = 4.dp
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val section = 32.dp
    val touch = 48.dp
    val contentWidth = 840.dp
    val bubbleWidth = 420.dp
}
val MeshShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
fun messageShape(outgoing: Boolean) = RoundedCornerShape(
    topStart = 20.dp, topEnd = 20.dp,
    bottomEnd = if (outgoing) 4.dp else 20.dp,
    bottomStart = if (outgoing) 20.dp else 4.dp
)
