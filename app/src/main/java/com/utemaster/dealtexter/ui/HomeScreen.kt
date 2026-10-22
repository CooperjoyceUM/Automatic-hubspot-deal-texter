package com.utemaster.dealtexter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.utemaster.dealtexter.Graph
import com.utemaster.dealtexter.core.CallTiming
import com.utemaster.dealtexter.core.PhoneNumbers
import com.utemaster.dealtexter.core.Status
import com.utemaster.dealtexter.data.TextRecordEntity
import com.utemaster.dealtexter.work.CheckScheduler
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)

fun formatSydney(i: Instant): String = TIME_FORMAT.format(CallTiming.sydneyTime(i))

@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Graph.settings(context) }
    val dao = remember { Graph.db(context).dao() }
    val state by repo.state.collectAsState()
    val settings by repo.settings.collectAsState()
    val records by remember { dao.recent() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // Re-read permission state whenever the app comes back to the foreground.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val smsGranted = remember(tick) { granted(context, Manifest.permission.SEND_SMS) }
    val notificationsGranted = remember(tick) {
        Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    val batteryExempt = remember(tick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Utebuild Texter", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }

            ModeBanner(testMode = settings.testMode, myNumber = settings.myNumber)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatic checking", fontWeight = FontWeight.Bold)
                            Text("Checks HubSpot about every 15 minutes", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = { on ->
                                repo.setEnabled(on)
                                if (on) CheckScheduler.start(context) else CheckScheduler.stop(context)
                            },
                        )
                    }
                    Text(
                        state.activatedAt?.let { "Only deals created after ${formatSydney(it)} are texted." }
                            ?: "Not switched on yet. Deals created before you switch on are never texted.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { CheckScheduler.runNow(context) },
                        enabled = state.activatedAt != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Run check now") }
                    Text(
                        "Last check: " + (state.lastRunAt?.let { "${formatSydney(it)} – ${state.lastRunSummary}" } ?: "never"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Setup checklist", fontWeight = FontWeight.Bold)
                    ChecklistRow("HubSpot token saved", state.hasToken, "Add", onOpenSettings)
                    ChecklistRow("SMS permission", smsGranted, "Allow") {
                        permissionLauncher.launch(Manifest.permission.SEND_SMS)
                    }
                    if (!smsGranted) {
                        Text(
                            "If Android says this is a restricted setting: open App info → ⋮ (top right) → " +
                                "Allow restricted settings, then come back and tap Allow again.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { openAppInfo(context) }) { Text("Open App info") }
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        ChecklistRow("Notifications", notificationsGranted, "Allow") {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    ChecklistRow("Battery: unrestricted", batteryExempt, "Fix") { requestBatteryExemption(context) }
                    Text(
                        "Also add the app to Settings → Battery → Background usage limits → Never sleeping apps (see README).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text("Recent deals", style = MaterialTheme.typography.titleMedium)
            if (records.isEmpty()) {
                Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
            }
            records.forEach { r ->
                RecordCard(
                    r,
                    onRetry = {
                        scope.launch {
                            dao.put(r.copy(status = Status.FAILED.name, attempts = 0, updatedAt = System.currentTimeMillis()))
                            CheckScheduler.runNow(context)
                        }
                    },
                    onMarkSent = {
                        scope.launch {
                            dao.put(
                                r.copy(
                                    status = Status.SENT.name,
                                    sentAt = r.sentAt ?: System.currentTimeMillis(),
                                    // Write the HubSpot note on the next check if we know what was sent.
                                    notePending = !r.testMode && r.message != null,
                                    lastError = null,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                    },
                )
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun ModeBanner(testMode: Boolean, myNumber: String) {
    val (bg, fg) = if (testMode) Color(0xFFFFE08A) to Color(0xFF3A2C00) else Color(0xFFC62828) to Color.White
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg, MaterialTheme.shapes.medium)
            .padding(16.dp),
    ) {
        if (testMode) {
            Text("TEST MODE ON", color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            val n = PhoneNumbers.normalise(myNumber)?.let(PhoneNumbers::pretty) ?: "(your number isn't set – see Settings)"
            Text("All texts go to YOUR number $n. Customers are NOT texted.", color = fg)
        } else {
            Text("LIVE", color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("Texts go to CUSTOMERS from your SIM.", color = fg)
        }
    }
}

@Composable
private fun ChecklistRow(label: String, ok: Boolean, action: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✅" else "⚠️")
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (!ok) OutlinedButton(onClick = onFix) { Text(action) }
    }
}

@Composable
private fun RecordCard(r: TextRecordEntity, onRetry: () -> Unit, onMarkSent: () -> Unit) {
    val status = runCatching { Status.valueOf(r.status) }.getOrNull()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.dealName ?: "Deal ${r.dealId}", fontWeight = FontWeight.Bold)
            Text(
                "${statusLabel(status, r)} · ${formatSydney(Instant.ofEpochMilli(r.updatedAt))}",
                style = MaterialTheme.typography.bodySmall,
            )
            r.sentTo?.let {
                Text(
                    "To ${PhoneNumbers.pretty(it)}" + if (r.testMode) " (test mode)" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            r.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            r.message?.let {
                HorizontalDivider()
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            when (status) {
                Status.SENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onMarkSent) { Text("Mark as sent") }
                    OutlinedButton(onClick = onRetry) { Text("Retry (may duplicate)") }
                }
                Status.FAILED, Status.GAVE_UP -> OutlinedButton(onClick = onRetry) { Text("Retry now") }
                else -> Unit
            }
        }
    }
}

private fun statusLabel(s: Status?, r: TextRecordEntity) = when (s) {
    Status.SENT -> if (r.notePending) "Sent – HubSpot note pending" else "Sent"
    Status.SENDING -> "Unconfirmed – check your Messages app"
    Status.FAILED -> "Failed (attempt ${r.attempts}) – will retry"
    Status.GAVE_UP -> "Gave up after ${r.attempts} attempts"
    Status.NO_TEXT_NEEDED -> "No text needed (weekday business hours)"
    Status.ALREADY_NOTED -> "Skipped – HubSpot already has a text-sent note"
    Status.TOO_LATE -> "Not sent – call day already passed"
    null -> r.status
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@SuppressLint("BatteryLife") // Sideloaded personal app: asking directly is the right call.
private fun requestBatteryExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { openAppInfo(context) }
}
