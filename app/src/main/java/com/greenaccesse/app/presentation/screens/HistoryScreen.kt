package com.greenaccesse.app.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greenaccesse.app.history.ScanStatus
import com.greenaccesse.app.presentation.appContainer
import com.greenaccesse.app.presentation.components.StatusDot
import com.greenaccesse.app.presentation.components.formatDateTime
import com.greenaccesse.app.presentation.components.formatTime
import com.greenaccesse.app.presentation.theme.LocalStatusColors
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen() {
    val c = appContainer()
    val records by c.history.records.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    val colors = LocalStatusColors.current

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Scan history", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Stored only on this phone: time, UID and status. No member information.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { confirmClear = true }, enabled = records.isNotEmpty()) { Text("Clear History") }
        }
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No scans yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(records, key = { it.id }) { r ->
                    val color = when {
                        r.status == ScanStatus.PENDING -> colors.warning
                        r.status.isSuccess -> colors.success
                        else -> colors.error
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.uid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${formatDateTime(r.timestampMillis).substring(0, 10)}  ·  ${r.connectionStatus}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatTime(r.timestampMillis), style = MaterialTheme.typography.bodyMedium)
                            Text(r.status.label, style = MaterialTheme.typography.labelMedium, color = color)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All local scan records will be deleted from this phone. Attendance recorded by Green Access on the PC is not affected.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; scope.launch { c.history.clear() } }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
