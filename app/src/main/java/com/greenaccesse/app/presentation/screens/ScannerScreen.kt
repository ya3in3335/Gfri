package com.greenaccesse.app.presentation.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.PortableWifiOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greenaccesse.app.domain.ScannerState
import com.greenaccesse.app.history.ScanStatus
import com.greenaccesse.app.network.ConnectionState
import com.greenaccesse.app.nfc.NfcStatus
import com.greenaccesse.app.nfc.Uid
import com.greenaccesse.app.presentation.LocalNfcStatus
import com.greenaccesse.app.presentation.appContainer
import com.greenaccesse.app.presentation.components.ConnectionBadge
import com.greenaccesse.app.presentation.components.formatTime
import com.greenaccesse.app.presentation.theme.LocalStatusColors
import com.greenaccesse.app.presentation.theme.UidTextStyle

@Composable
fun ScannerScreen(onOpenConnection: () -> Unit) {
    val c = appContainer()
    val scanner by c.scans.state.collectAsStateWithLifecycle()
    val connection by c.connection.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val nfcStatus = LocalNfcStatus.current
    val colors = LocalStatusColors.current
    val context = LocalContext.current

    data class View(val icon: ImageVector, val title: String, val subtitle: String, val color: Color, val busy: Boolean = false)

    val view: View = when {
        nfcStatus == NfcStatus.UNAVAILABLE -> View(Icons.Outlined.ErrorOutline, "NFC NOT AVAILABLE", "This phone has no NFC hardware. Green Accesse needs an NFC-enabled phone.", colors.error)
        nfcStatus == NfcStatus.DISABLED -> View(Icons.Outlined.PortableWifiOff, "NFC IS OFF", "Turn on NFC in the Android settings to scan cards.", colors.warning)
        settings?.nfcReaderEnabled == false -> View(Icons.Outlined.PortableWifiOff, "READER DISABLED", "The NFC reader is disabled in Settings.", colors.neutral)
        else -> when (val s = scanner) {
            ScannerState.Ready -> View(Icons.Outlined.Nfc, "READY", "Hold NFC card near the phone", MaterialTheme.colorScheme.primary)
            is ScannerState.Sending -> View(Icons.Outlined.Sync, "CARD DETECTED", "Sending...", colors.warning, busy = true)
            is ScannerState.Sent -> when (s.result) {
                // Decision made and reported by Green Access on the PC; displayed as-is.
                "granted" -> View(Icons.Outlined.CheckCircle, "ACCESS GRANTED", s.message ?: "Sent successfully", colors.success)
                "denied", "unknown_card" -> View(
                    Icons.Outlined.Block,
                    if (s.result == "denied") "ACCESS DENIED" else "UNKNOWN CARD",
                    s.message ?: "Reported by Green Access",
                    colors.warning,
                )
                else -> View(
                    Icons.Outlined.CheckCircle,
                    if (s.status == ScanStatus.DELIVERED) "DELIVERED" else "SENT ✓",
                    s.message ?: "Sent successfully",
                    colors.success,
                )
            }
            is ScannerState.Failed -> if (s.status == ScanStatus.FAILED_DISCONNECTED) {
                View(Icons.Outlined.CloudOff, "PC DISCONNECTED", "Unable to send scan.", colors.error)
            } else {
                View(Icons.Outlined.ErrorOutline, "NOT SENT", s.message, colors.error)
            }
            is ScannerState.ReadError -> View(Icons.Outlined.ErrorOutline, "READ ERROR", s.message, colors.error)
        }
    }
    val ringColor by animateColorAsState(view.color, label = "ring")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("NFC Scanner", style = MaterialTheme.typography.headlineMedium)
        ConnectionBadge(connection)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.size(200.dp).border(6.dp, ringColor.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (view.busy) {
                CircularProgressIndicator(Modifier.size(72.dp), color = view.color)
            } else {
                Icon(view.icon, contentDescription = null, tint = view.color, modifier = Modifier.size(88.dp))
            }
        }
        Text(view.title, style = MaterialTheme.typography.headlineSmall, color = view.color)
        Text(view.subtitle, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val uid = when (val s = scanner) {
            is ScannerState.Sending -> s.uid to s.atMillis
            is ScannerState.Sent -> s.uid to s.atMillis
            is ScannerState.Failed -> s.uid to s.atMillis
            else -> null
        }
        if (uid != null && nfcStatus == NfcStatus.ENABLED) {
            Text("UID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Uid.formatForDisplay(uid.first), style = UidTextStyle)
            Text(formatTime(uid.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        when {
            nfcStatus == NfcStatus.DISABLED -> Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open NFC settings") }
            !connection.isConnected && connection !is ConnectionState.Connecting ->
                OutlinedButton(onClick = onOpenConnection, modifier = Modifier.fillMaxWidth()) { Text("Connection settings") }
        }
        if (scanner !is ScannerState.Ready && scanner !is ScannerState.Sending) {
            OutlinedButton(onClick = c.scans::resetToReady, modifier = Modifier.fillMaxWidth()) { Text("Ready for next card") }
        }
    }
}
