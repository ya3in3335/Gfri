package com.greenaccesse.app.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greenaccesse.app.BuildConfig
import com.greenaccesse.app.nfc.ScanDebouncer
import com.greenaccesse.app.presentation.appContainer
import com.greenaccesse.app.presentation.components.InfoRow
import com.greenaccesse.app.presentation.components.SectionCard
import com.greenaccesse.app.presentation.components.formatDateTime
import com.greenaccesse.app.settings.AppSettings
import com.greenaccesse.app.settings.ThemeMode
import com.greenaccesse.app.transport.HostValidator
import com.greenaccesse.app.transport.TransportKind
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onOpenAbout: () -> Unit, onOpenPairing: () -> Unit) {
    val c = appContainer()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val pc by c.pairing.pairedPc.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmRevoke by remember { mutableStateOf(false) }
    var customDebounce by remember { mutableStateOf("") }
    var hostField by remember { mutableStateOf("") }
    var portField by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.serverHost, settings.serverPort) {
        hostField = settings.serverHost
        portField = settings.serverPort.toString()
    }
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(t) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionCard(title = "NFC") {
            SwitchRow("NFC Reader Enabled", settings.nfcReaderEnabled) { v -> update { it.copy(nfcReaderEnabled = v) } }
            Text("Duplicate Scan Delay", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanDebouncer.PRESETS.forEach { ms ->
                    FilterChip(
                        selected = settings.debounceMillis == ms,
                        onClick = { update { it.copy(debounceMillis = ms) } },
                        label = { Text("$ms ms") },
                    )
                }
                FilterChip(
                    selected = settings.debounceMillis !in ScanDebouncer.PRESETS,
                    onClick = { customDebounce = settings.debounceMillis.toString() },
                    label = { Text("Custom") },
                )
            }
            if (settings.debounceMillis !in ScanDebouncer.PRESETS || customDebounce.isNotEmpty()) {
                OutlinedTextField(
                    value = customDebounce.ifEmpty { settings.debounceMillis.toString() },
                    onValueChange = { v ->
                        customDebounce = v.filter(Char::isDigit).take(5)
                        customDebounce.toLongOrNull()?.takeIf { it in ScanDebouncer.MIN_WINDOW_MS..ScanDebouncer.MAX_WINDOW_MS }
                            ?.let { ms -> update { it.copy(debounceMillis = ms) } }
                    },
                    label = { Text("Custom delay (ms, ${ScanDebouncer.MIN_WINDOW_MS}-${ScanDebouncer.MAX_WINDOW_MS})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SwitchRow("Sound on Scan", settings.soundOnScan) { v -> update { it.copy(soundOnScan = v) } }
            SwitchRow("Vibration on Scan", settings.vibrationOnScan) { v -> update { it.copy(vibrationOnScan = v) } }
            SwitchRow("Keep screen on", settings.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
        }

        SectionCard(title = "Connection") {
            SwitchRow("Auto Reconnect", settings.autoReconnect) { v -> update { it.copy(autoReconnect = v) } }
            Text("Transport", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransportKind.entries.forEach { t ->
                    FilterChip(
                        selected = settings.transport == t,
                        onClick = {
                            scope.launch {
                                c.settings.update { it.copy(transport = t) }
                                c.connection.reconnect()
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
            OutlinedTextField(
                value = hostField,
                onValueChange = { hostField = it.trim().take(253); addressError = null },
                label = { Text("Server Address") },
                singleLine = true,
                enabled = settings.transport == TransportKind.WIFI,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = portField,
                onValueChange = { portField = it.filter(Char::isDigit).take(5); addressError = null },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            addressError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(
                onClick = {
                    val port = portField.toIntOrNull()
                    when {
                        settings.transport == TransportKind.WIFI && !HostValidator.isValidHost(hostField) -> addressError = "Invalid address"
                        port == null || !HostValidator.isValidPort(port) -> addressError = "Invalid port"
                        else -> scope.launch {
                            c.settings.update { it.copy(serverHost = hostField, serverPort = port) }
                            c.connection.reconnect()
                        }
                    }
                },
                enabled = hostField != settings.serverHost || portField != settings.serverPort.toString(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save address and reconnect") }
        }

        SectionCard(title = "Security") {
            Text("Paired Devices", style = MaterialTheme.typography.bodyMedium)
            val paired = pc
            if (paired == null) {
                Text("No PC paired", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onOpenPairing, modifier = Modifier.fillMaxWidth()) { Text("Pair with Green Access PC") }
            } else {
                InfoRow("PC", paired.pcName)
                InfoRow("Paired on", formatDateTime(paired.pairedAtMillis))
                InfoRow("Security code", paired.verificationCode)
                OutlinedButton(onClick = { confirmRevoke = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Revoke Pairing", color = MaterialTheme.colorScheme.error)
                }
            }
            InfoRow("Device ID", c.identity.deviceId)
            OutlinedButton(onClick = { scope.launch { c.auth.logout() } }, modifier = Modifier.fillMaxWidth()) { Text("Logout") }
        }

        SectionCard(title = "Appearance") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.DARK to "Dark Mode", ThemeMode.LIGHT to "Light Mode", ThemeMode.SYSTEM to "System Default").forEach { (m, label) ->
                    FilterChip(selected = settings.themeMode == m, onClick = { update { it.copy(themeMode = m) } }, label = { Text(label) })
                }
            }
        }

        SectionCard(title = "About", modifier = Modifier.clickable(onClick = onOpenAbout)) {
            InfoRow("Application", "Green Accesse")
            InfoRow("Version", BuildConfig.VERSION_NAME)
            Text("Green Access Companion - tap for details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("Revoke pairing?") },
            text = { Text("This phone will forget the Green Access PC and stop sending scans. The PC is notified if it is reachable. You will need a new pairing code to connect again.") },
            confirmButton = {
                TextButton(onClick = { confirmRevoke = false; scope.launch { c.pairingService.revoke() } }) {
                    Text("Revoke", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun AboutScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Green Accesse", style = MaterialTheme.typography.headlineMedium)
        Text("Green Access Companion", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        SectionCard {
            InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("Developer", "Green Access")
            InfoRow("Protocol", "Green Access Bridge v1")
        }
        SectionCard(title = "What this app does") {
            Text(
                "Green Accesse turns this phone into an NFC reader for the Green Access Windows application. " +
                    "It reads the card UID and sends it securely over the local network to the paired Green Access PC.\n\n" +
                    "All membership decisions, member records, payments and attendance stay in Green Access on the PC. " +
                    "This app never stores member information and never sends data to the Internet.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SectionCard(title = "Permissions") {
            Text(
                "NFC - read membership cards.\nInternet / network state - talk to the Green Access PC on the local network.\nVibrate - scan feedback.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
