package com.meshlink.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.abs

@Composable
internal fun SpeechWaveform(envelope: SpeechLevel) {
    val amplitude = remember(envelope) { mutableFloatStateOf(0f) }
    LaunchedEffect(envelope) {
        while (isActive) {
            withFrameNanos { amplitude.floatValue = envelope.frame(SystemClock.uptimeMillis()) }
        }
    }
    val teal = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Listening", style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(56.dp).semantics {
            contentDescription = "Live microphone loudness"
        }) {
            val center = size.height / 2f
            drawLine(teal.copy(alpha = 0.35f), Offset(0f, center), Offset(size.width, center), 1.dp.toPx())
            val level = amplitude.floatValue // Read in draw phase, not composition.
            if (level > 0f) {
                val count = 33
                val spacing = size.width / count
                for (index in 0 until count) {
                    val distance = abs(index - (count - 1) / 2f) / ((count - 1) / 2f)
                    val shape = (1f - distance * 0.75f) * (if (index % 2 == 0) 1f else 0.72f)
                    val halfHeight = level * shape * (center - 3.dp.toPx())
                    val x = spacing * (index + 0.5f)
                    drawLine(teal, Offset(x, center - halfHeight), Offset(x, center + halfHeight),
                        minOf(3.dp.toPx(), spacing * 0.5f), StrokeCap.Round)
                }
            }
        }
    }
}
