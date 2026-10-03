package com.greenaccesse.app.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greenaccesse.app.network.ConnectionState
import com.greenaccesse.app.network.TestResult
import com.greenaccesse.app.presentation.appContainer
import com.greenaccesse.app.presentation.components.ConnectionBadge
import com.greenaccesse.app.presentation.components.InfoRow
import com.greenaccesse.app.presentation.components.SectionCard
import com.greenaccesse.app.presentation.components.formatDateTime
import com.greenaccesse.app.presentation.components.presentation
import com.greenaccesse.app.presentation.theme.LocalStatusColors
import kotlinx.coroutines.launch

@Composable
fun ConnectionScreen(onPair: () -> Unit) {
    val c = appContainer()
    val state by c.connection.state.collectAsStateWithLifecycle()
    val bridge by c.connection.bridgeStatus.collectAsStateWithLifecycle()
    val pc by c.pairing.pairedPc.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var testMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    val colors = LocalStatusColors.current
    val p = state.presentation()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Green Access PC", style = MaterialTheme.typography.headlineSmall)
        ConnectionBadge(state)
        p.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        SectionCard(title = "PC") {
            InfoRow("Status", p.title, p.color)
            InfoRow("PC name", bridge?.pcName ?: pc?.pcName ?: "-")
            InfoRow("IP address", (state as? ConnectionState.Connected)?.endpoint?.host ?: settings?.serverHost?.ifBlank { "-" } ?: "-")
            InfoRow("Port", ((state as? ConnectionState.Connected)?.endpoint?.port ?: settings?.serverPort)?.toString() ?: "-")
            InfoRow("Transport", settings?.transport?.label ?: "-")
            InfoRow("Pairing status", if (pc != null) "Paired ✓" else "Not paired", if (pc != null) colors.success else colors.neutral)
            InfoRow("Last connected", pc?.lastConnectedAtMillis?.let(::formatDateTime) ?: "Never")
            pc?.let { InfoRow("Security code", it.verificationCode) }
            bridge?.bridgeVersion?.let { InfoRow("Bridge version", it) }
        }

        if (pc == null || state is ConnectionState.AuthenticationFailed) {
            Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) { Text("Pair with Green Access PC") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        testing = true
                        testMessage = null
                        scope.launch {
                            testMessage = when (val r = c.connection.testConnection()) {
                                is TestResult.Success -> true to "Connection OK - ${r.pcName} answered in ${r.roundTripMillis} ms"
                                is TestResult.Failure -> false to r.message
                            }
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                ) { Text(if (testing) "Testing..." else "Test Connection") }
                OutlinedButton(onClick = { c.connection.reconnect() }, modifier = Modifier.weight(1f)) { Text("Reconnect") }
            }
            testMessage?.let { (ok, msg) ->
                Text(msg, color = if (ok) colors.success else colors.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.isConnected || state is ConnectionState.Connecting || state is ConnectionState.Reconnecting) {
                OutlinedButton(onClick = { c.connection.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
            } else {
                Button(onClick = { c.connection.connect() }, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            }
            TextButton(onClick = onPair, modifier = Modifier.fillMaxWidth()) { Text("Pair with a different PC") }
        }
    }
}
