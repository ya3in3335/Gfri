package com.greenaccesse.app.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.greenaccesse.app.di.AppContainer
import com.greenaccesse.app.network.DiscoveredBridge
import com.greenaccesse.app.pairing.PairedPc
import com.greenaccesse.app.pairing.PairingResult
import com.greenaccesse.app.protocol.Protocol
import com.greenaccesse.app.presentation.appViewModel
import com.greenaccesse.app.presentation.components.SectionCard
import com.greenaccesse.app.presentation.theme.LocalStatusColors
import com.greenaccesse.app.transport.TransportKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairingUiState(
    val host: String = "",
    val port: String = Protocol.DEFAULT_PORT.toString(),
    val code: String = "",
    val transport: TransportKind = TransportKind.WIFI,
    val discovered: List<DiscoveredBridge> = emptyList(),
    val discovering: Boolean = false,
    val discoveryError: String? = null,
    val pairing: Boolean = false,
    val error: String? = null,
    val paired: PairedPc? = null,
)

class PairingViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(PairingUiState())
    val state = _state.asStateFlow()
    private var discoveryJob: Job? = null

    init {
        viewModelScope.launch {
            val s = c.settings.current()
            _state.update {
                it.copy(
                    host = s.serverHost,
                    port = s.serverPort.toString(),
                    transport = s.transport,
                )
            }
        }
        startDiscovery()
    }

    fun startDiscovery() {
        discoveryJob?.cancel()
        _state.update { it.copy(discovering = true, discoveryError = null) }
        discoveryJob = viewModelScope.launch {
            c.discovery.discover()
                .catch { e -> _state.update { it.copy(discovering = false, discoveryError = e.message) } }
                .collect { list -> _state.update { it.copy(discovered = list) } }
        }
    }

    fun onHost(v: String) = _state.update { it.copy(host = v.trim().take(253), error = null) }
    fun onPort(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit).take(5), error = null) }
    fun onCode(v: String) = _state.update { it.copy(code = v.filter(Char::isDigit).take(Protocol.PAIRING_CODE_LENGTH), error = null) }
    fun onTransport(t: TransportKind) = _state.update { it.copy(transport = t, error = null) }
    fun select(b: DiscoveredBridge) = _state.update { it.copy(host = b.host, port = b.port.toString(), transport = TransportKind.WIFI, error = null) }

    fun pair() {
        val s = _state.value
        if (s.pairing) return
        val port = s.port.toIntOrNull()
        if (port == null) {
            _state.update { it.copy(error = "Enter a valid port") }
            return
        }
        val host = if (s.transport == TransportKind.USB_ADB) "127.0.0.1" else s.host
        _state.update { it.copy(pairing = true, error = null) }
        viewModelScope.launch {
            when (val r = c.pairingService.pair(host, port, s.transport, s.code)) {
                is PairingResult.Success -> _state.update { it.copy(pairing = false, paired = r.pc, code = "") }
                is PairingResult.Failure -> _state.update { it.copy(pairing = false, error = r.message) }
            }
        }
    }

    override fun onCleared() {
        discoveryJob?.cancel()
    }
}

@Composable
fun PairingScreen(onDone: () -> Unit) {
    val vm = appViewModel { PairingViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val colors = LocalStatusColors.current

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Connect to Green Access", style = MaterialTheme.typography.headlineSmall)

        val paired = s.paired
        if (paired != null) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Paired ✓", style = MaterialTheme.typography.titleLarge, color = colors.success)
                        Text(paired.pcName, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text("Security code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(paired.verificationCode, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 26.sp))
                Text(
                    "Check that the Green Access PC shows the same security code. If it does not, revoke the pairing in Settings and pair again on a trusted network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
            return@Column
        }

        Text(
            "1. On the PC, open the Green Access Bridge and choose \"Pair new phone\".\n" +
                "2. Select the PC below (or type its address).\n" +
                "3. Enter the 6-digit pairing code shown on the PC.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportKind.entries.forEach { t ->
                FilterChip(selected = s.transport == t, onClick = { vm.onTransport(t) }, label = { Text(t.label) })
            }
        }

        if (s.transport == TransportKind.WIFI) {
            SectionCard(title = "Green Access PCs on this network") {
                if (s.discovered.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.discovering && s.discoveryError == null) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            s.discoveryError ?: "Searching... Make sure the phone is on the same Wi-Fi as the PC. You can also enter the address manually.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                s.discovered.forEachIndexed { i, b ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.select(b) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Computer, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.pcName ?: b.serviceName, style = MaterialTheme.typography.titleSmall)
                            Text("${b.host}:${b.port}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (s.host == b.host && s.port == b.port.toString()) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = "Selected", tint = colors.success)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = s.host,
                onValueChange = vm::onHost,
                label = { Text("PC IP address") },
                placeholder = { Text("192.168.1.20") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            SectionCard(title = "USB") {
                Text(
                    "Connect the phone to the PC with a USB cable, enable USB debugging, and run on the PC:\n" +
                        "adb reverse tcp:${s.port.ifBlank { "8787" }} tcp:${s.port.ifBlank { "8787" }}\n" +
                        "The phone will reach the Bridge through the cable.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OutlinedTextField(
            value = s.port,
            onValueChange = vm::onPort,
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.code,
            onValueChange = vm::onCode,
            label = { Text("Enter Pairing Code") },
            placeholder = { Text("583921") },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 24.sp, letterSpacing = 6.sp, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = vm::pair,
            enabled = !s.pairing && s.code.length == Protocol.PAIRING_CODE_LENGTH &&
                (s.transport == TransportKind.USB_ADB || s.host.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (s.pairing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Connect")
        }
        Text(
            "The pairing code is never sent over the network. It is used to prove both devices saw the same code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
