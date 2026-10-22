package com.utemaster.dealtexter.ui

import android.os.Build
import android.telephony.SmsManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.utemaster.dealtexter.Graph
import com.utemaster.dealtexter.core.MessageTemplate
import com.utemaster.dealtexter.core.NswHolidays
import com.utemaster.dealtexter.core.PhoneNumbers
import com.utemaster.dealtexter.data.AppSettings
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Graph.settings(context) }
    val state by repo.state.collectAsState()
    var form by remember { mutableStateOf(repo.settings.value) }
    var newToken by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save(): Boolean {
        val errors = form.validate().toMutableList()
        if (form.myNumber.isNotBlank() && PhoneNumbers.normalise(form.myNumber) == null) {
            errors += "Your mobile number doesn't look valid"
        }
        if (errors.isNotEmpty()) {
            message = "Not saved:\n" + errors.joinToString("\n")
            return false
        }
        repo.save(form)
        if (newToken.isNotBlank()) {
            repo.setToken(newToken)
            newToken = ""
        }
        message = "Saved."
        return true
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Button(onClick = { save() }) { Text("Save") }
            }
            message?.let { Text(it, color = if (it.startsWith("Not saved") || it.startsWith("Failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }

            Section("Test mode") {
                SwitchRow(
                    "TEST MODE – send every text to my own number instead of the customer",
                    form.testMode,
                ) { form = form.copy(testMode = it) }
                Field("My mobile number (test texts go here)", form.myNumber, KeyboardType.Phone) { form = form.copy(myNumber = it) }
                SwitchRow("Also write HubSpot notes for test texts", form.notesInTestMode) {
                    form = form.copy(notesInTestMode = it)
                }
            }

            Section("HubSpot") {
                Text(
                    if (state.hasToken) "Token saved ✓ (hidden). Paste a new one below to replace it." else "No token saved yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = newToken,
                    onValueChange = { newToken = it },
                    label = { Text("Private app / service key token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.hasToken) {
                    TextButton(onClick = { repo.setToken(null); message = "Token removed." }) { Text("Remove saved token") }
                }
                Field("Pipeline ID (Trade & Rec)", form.pipelineId) { form = form.copy(pipelineId = it) }
                Field("Discovery stage ID", form.stageId) { form = form.copy(stageId = it) }
                Field("My HubSpot owner ID", form.ownerId) { form = form.copy(ownerId = it) }
                Field("Utebuilder link property (internal name)", form.linkProperty) { form = form.copy(linkProperty = it) }
                OutlinedButton(onClick = {
                    if (save()) {
                        message = "Testing…"
                        scope.launch {
                            val config = repo.toConfig(fallbackActivation = Instant.now())!!
                            message = try {
                                Graph.hubSpot(context).testConnection(config)
                            } catch (e: Exception) {
                                "Failed: ${e.message}"
                            }
                        }
                    }
                }) { Text("Save & test HubSpot connection") }
            }

            Section("Message") {
                Field("My name (used in the text)", form.myName) { form = form.copy(myName = it) }
                Field("Message template", form.messageTemplate, singleLine = false) { form = form.copy(messageTemplate = it) }
                Text(
                    "Placeholders: {first_name} {my_name} {when} {questions} {deal_name}\n" +
                        "{when} becomes \"tomorrow\", \"this morning\", \"today\" or \"on Monday\".",
                    style = MaterialTheme.typography.bodySmall,
                )
                Field("Qualifying questions", form.questions, singleLine = false) { form = form.copy(questions = it) }
                if (MessageTemplate.hasPlaceholderQuestions(form.questions)) {
                    Text(
                        "These are still the placeholder. Live texting stays blocked until you replace them.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = {
                    val text = MessageTemplate.render(form.messageTemplate, "Sam", form.myName, "tomorrow", form.questions, "Sample deal")
                    preview = text + "\n\n(${text.length} characters, ${smsParts(context, text)} SMS part(s))"
                }) { Text("Preview message") }
                preview?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            Section("Timing (Sydney time)") {
                Field("Morning ends (\"this morning\" before this)", form.morningEnd) { form = form.copy(morningEnd = it) }
                Field("Afternoon texts start (\"tomorrow\" from this)", form.afternoonStart) { form = form.copy(afternoonStart = it) }
                Text(
                    state.activatedAt?.let { "Deals created before ${formatSydney(it)} are ignored." } ?: "Not switched on yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.activatedAt != null) {
                    OutlinedButton(onClick = { repo.resetActivationToNow(); message = "Start time reset to now." }) {
                        Text("Reset start time to now")
                    }
                }
            }

            Section("NSW public holidays") {
                Text(
                    "One per line: YYYY-MM-DD Name. Check nsw.gov.au each year and add the next year's dates.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Field("Holidays", form.effectiveHolidaysText, singleLine = false) { form = form.copy(holidaysText = it) }
                OutlinedButton(onClick = { form = form.copy(holidaysText = null) }) { Text("Reset to built-in list") }
                val errors = remember(form.effectiveHolidaysText) { NswHolidays.parse(form.effectiveHolidaysText).errors }
                errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Suppress("DEPRECATION")
private fun smsParts(context: android.content.Context, text: String): Int = runCatching {
    val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java)
    } else {
        SmsManager.getDefault()
    }
    sms.divideMessage(text).size
}.getOrDefault(1)
