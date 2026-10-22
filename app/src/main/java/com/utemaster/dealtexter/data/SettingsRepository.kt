package com.utemaster.dealtexter.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.utemaster.dealtexter.core.MessageTemplate
import com.utemaster.dealtexter.core.TexterConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Everything editable on the Settings screen. All IDs are editable – these are only the starting values. */
data class AppSettings(
    val pipelineId: String = "658412132", // Trade & Rec
    val stageId: String = "967474456", // Discovery
    val ownerId: String = "1101472483",
    val linkProperty: String = "utebuilder_submission_link",
    val myName: String = "Cooper",
    val myNumber: String = "",
    val testMode: Boolean = true,
    val notesInTestMode: Boolean = false,
    val messageTemplate: String = MessageTemplate.DEFAULT_TEMPLATE,
    /** Weekday work hours: deals created between these times get no text. */
    val workStart: String = "07:00",
    val workEnd: String = "15:00",
) {
    /** Returns a list of problems with the values, empty if they can be saved. */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (parseTime(workStart) == null) errors += "Work hours start must be HH:mm, e.g. 07:00"
        if (parseTime(workEnd) == null) errors += "Work hours end must be HH:mm, e.g. 15:00"
        return errors
    }

    companion object {
        fun parseTime(s: String): LocalTime? = try {
            LocalTime.parse(s.trim())
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

class SettingsRepository private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The HubSpot token lives only here, encrypted with a key held in the Android Keystore. */
    private val secrets: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "secrets",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<RunState> = _state

    data class RunState(
        val enabled: Boolean,
        val activatedAt: Instant?,
        val lastRunAt: Instant?,
        val lastRunSummary: String?,
        val hasToken: Boolean,
    )

    // ---- Token -----------------------------------------------------------------------------

    /** Null if not set, or if the encrypted store can't be opened (e.g. Keystore reset). */
    fun token(): String? = runCatching { secrets.getString(KEY_TOKEN, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun setToken(token: String?) {
        secrets.edit { if (token.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, token.trim()) }
        _state.value = loadState()
    }

    // ---- Editable settings -----------------------------------------------------------------

    fun save(s: AppSettings) {
        prefs.edit {
            putString("pipelineId", s.pipelineId.trim())
            putString("stageId", s.stageId.trim())
            putString("ownerId", s.ownerId.trim())
            putString("linkProperty", s.linkProperty.trim())
            putString("myName", s.myName.trim())
            putString("myNumber", s.myNumber.trim())
            putBoolean("testMode", s.testMode)
            putBoolean("notesInTestMode", s.notesInTestMode)
            putString("messageTemplate", s.messageTemplate)
            putString("workStart", s.workStart.trim())
            putString("workEnd", s.workEnd.trim())
        }
        _settings.value = load()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            pipelineId = prefs.getString("pipelineId", d.pipelineId)!!,
            stageId = prefs.getString("stageId", d.stageId)!!,
            ownerId = prefs.getString("ownerId", d.ownerId)!!,
            linkProperty = prefs.getString("linkProperty", d.linkProperty)!!,
            myName = prefs.getString("myName", d.myName)!!,
            myNumber = prefs.getString("myNumber", d.myNumber)!!,
            testMode = prefs.getBoolean("testMode", d.testMode),
            notesInTestMode = prefs.getBoolean("notesInTestMode", d.notesInTestMode),
            messageTemplate = prefs.getString("messageTemplate", d.messageTemplate)!!,
            workStart = prefs.getString("workStart", d.workStart)!!,
            workEnd = prefs.getString("workEnd", d.workEnd)!!,
        )
    }

    // ---- On/off and run status ---------------------------------------------------------------

    /** Turning on for the first time records the start moment: older deals are never texted. */
    fun setEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean("enabled", enabled)
            if (enabled && !prefs.contains("activatedAt")) putLong("activatedAt", System.currentTimeMillis())
        }
        _state.value = loadState()
    }

    fun resetActivationToNow() {
        prefs.edit { putLong("activatedAt", System.currentTimeMillis()) }
        _state.value = loadState()
    }

    fun recordRun(summary: String) {
        prefs.edit {
            putLong("lastRunAt", System.currentTimeMillis())
            putString("lastRunSummary", summary)
        }
        _state.value = loadState()
    }

    private fun loadState() = RunState(
        enabled = prefs.getBoolean("enabled", false),
        activatedAt = prefs.getLong("activatedAt", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        lastRunAt = prefs.getLong("lastRunAt", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        lastRunSummary = prefs.getString("lastRunSummary", null),
        hasToken = token() != null,
    )

    /**
     * Builds the core config, or null if the app has never been switched on
     * (unless [fallbackActivation] is given, e.g. for the connection test).
     */
    fun toConfig(fallbackActivation: Instant? = null): TexterConfig? {
        val s = settings.value
        val activatedAt = state.value.activatedAt ?: fallbackActivation ?: return null
        return TexterConfig(
            pipelineId = s.pipelineId,
            stageId = s.stageId,
            ownerId = s.ownerId,
            linkProperty = s.linkProperty,
            myName = s.myName,
            myNumber = s.myNumber,
            testMode = s.testMode,
            notesInTestMode = s.notesInTestMode,
            messageTemplate = s.messageTemplate,
            workStart = AppSettings.parseTime(s.workStart) ?: LocalTime.of(7, 0),
            workEnd = AppSettings.parseTime(s.workEnd) ?: LocalTime.of(15, 0),
            activatedAt = activatedAt,
        )
    }

    companion object {
        private const val KEY_TOKEN = "hubspot_token"

        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
