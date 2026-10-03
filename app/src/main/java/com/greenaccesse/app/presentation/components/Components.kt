package com.greenaccesse.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.greenaccesse.app.network.ConnectionState
import com.greenaccesse.app.presentation.theme.LocalStatusColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
            modifier = Modifier.weight(0.55f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}

data class ConnectionPresentation(val title: String, val detail: String?, val color: Color)

@Composable
fun ConnectionState.presentation(): ConnectionPresentation {
    val c = LocalStatusColors.current
    return when (this) {
        is ConnectionState.Connected -> ConnectionPresentation("Connected", pcName, c.success)
        is ConnectionState.Connecting -> ConnectionPresentation(if (attempt > 1) "Reconnecting..." else "Connecting...", endpoint.toString(), c.warning)
        is ConnectionState.Reconnecting -> ConnectionPresentation(
            "Connection lost",
            "Reconnecting in ${(retryInMillis + 999) / 1000}s - $lastError",
            c.warning,
        )
        is ConnectionState.Disconnected -> ConnectionPresentation("Disconnected", reason, c.error)
        ConnectionState.NotPaired -> ConnectionPresentation("Not paired", "Pair this phone with Green Access", c.neutral)
        ConnectionState.NotConfigured -> ConnectionPresentation("Not configured", "Set the PC address", c.neutral)
        is ConnectionState.AuthenticationFailed -> ConnectionPresentation("Pairing required", message, c.error)
    }
}

@Composable
fun ConnectionBadge(state: ConnectionState, modifier: Modifier = Modifier) {
    val p = state.presentation()
    Surface(shape = RoundedCornerShape(50), color = p.color.copy(alpha = 0.14f), modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(p.color)
            Spacer(Modifier.width(8.dp))
            Text(p.title, style = MaterialTheme.typography.labelLarge, color = p.color)
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun formatTime(millis: Long): String = timeFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
fun formatDateTime(millis: Long): String = dateTimeFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
