package com.greenaccesse.app.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.greenaccesse.app.di.AppContainer
import com.greenaccesse.app.history.ScanRecord
import com.greenaccesse.app.network.BridgeStatus
import com.greenaccesse.app.network.ConnectionState
import com.greenaccesse.app.nfc.Uid
import com.greenaccesse.app.pairing.PairedPc
import com.greenaccesse.app.presentation.appViewModel
import com.greenaccesse.app.presentation.components.InfoRow
import com.greenaccesse.app.presentation.components.SectionCard
import com.greenaccesse.app.presentation.components.StatusDot
import com.greenaccesse.app.presentation.components.formatDateTime
import com.greenaccesse.app.presentation.components.formatTime
import com.greenaccesse.app.presentation.components.presentation
import com.greenaccesse.app.presentation.theme.LocalStatusColors
import com.greenaccesse.app.presentation.theme.UidTextStyle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

data class DashboardUiState(
    val connection: ConnectionState = ConnectionState.Disconnected(),
    val bridge: BridgeStatus? = null,
    val pairedPc: PairedPc? = null,
    val lastScan: ScanRecord? = null,
    val todayScans: Int = 0,
    val todaySuccessful: Int = 0,
)

class DashboardViewModel(c: AppContainer) : ViewModel() {
    val state: StateFlow<DashboardUiState> = combine(
        c.connection.state,
        c.connection.bridgeStatus,
        c.pairing.pairedPc,
        c.history.records,
    ) { conn, bridge, pc, records ->
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val today = records.filter { it.timestampMillis >= startOfDay }
        DashboardUiState(conn, bridge, pc, records.firstOrNull(), today.size, today.count { it.status.isSuccess })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())
}

@Composable
fun DashboardScreen(onOpenConnection: () -> Unit, onOpenScanner: () -> Unit) {
    val vm = appViewModel { DashboardViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val status = LocalStatusColors.current
    val p = s.connection.presentation()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "Connection", modifier = Modifier.clickable(onClick = onOpenConnection)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(p.color)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleLarge, color = p.color)
                    Text(
                        s.bridge?.pcName ?: s.pairedPc?.pcName ?: p.detail ?: "Green Access PC",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Open connection")
            }
            if (s.connection is ConnectionState.NotPaired || s.connection is ConnectionState.AuthenticationFailed) {
                Button(onClick = onOpenConnection, modifier = Modifier.fillMaxWidth()) { Text("Connect to Green Access") }
            }
        }

        SectionCard(title = "Gym") {
            Text(
                s.bridge?.gymName?.takeIf { it.isNotBlank() } ?: s.pairedPc?.pcName ?: "Not connected",
                style = MaterialTheme.typography.titleMedium,
            )
            s.bridge?.greenAccessConnected?.let {
                InfoRow("Green Access app", if (it) "Running" else "Not running", if (it) status.success else status.warning)
            }
        }

        SectionCard(title = "Last scan") {
            val last = s.lastScan
            if (last == null) {
                Text("No card scanned yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(Uid.formatForDisplay(last.uid), style = UidTextStyle)
                InfoRow("Time", formatTime(last.timestampMillis))
                InfoRow("Status", last.status.label, if (last.status.isSuccess) status.success else status.error)
            }
            OutlinedButton(onClick = onOpenScanner, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Nfc, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open NFC scanner")
            }
        }

        SectionCard(title = "Statistics") {
            InfoRow("Today's scans", s.todayScans.toString())
            InfoRow("Sent successfully", s.todaySuccessful.toString())
            InfoRow("Connection status", p.title, p.color)
            InfoRow("Last connection", s.pairedPc?.lastConnectedAtMillis?.let(::formatDateTime) ?: "Never")
        }
    }
}
