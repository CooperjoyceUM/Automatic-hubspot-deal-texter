package com.utemaster.dealtexter.hubspot

import com.utemaster.dealtexter.core.Contact
import com.utemaster.dealtexter.core.CrmException
import com.utemaster.dealtexter.core.CrmGateway
import com.utemaster.dealtexter.core.Deal
import com.utemaster.dealtexter.core.TexterConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * HubSpot CRM v3/v4 REST client using a private app / service key token.
 *
 * The token is read from encrypted storage for each request and only ever placed in the
 * Authorization header. It is never logged or included in error messages.
 */
class HubSpotClient(private val tokenProvider: () -> String?) : CrmGateway {

    override suspend fun findQualifyingDeals(config: TexterConfig): List<Deal> {
        val deals = mutableListOf<Deal>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val body = JSONObject()
                .put(
                    "filterGroups",
                    JSONArray().put(
                        JSONObject().put(
                            "filters",
                            JSONArray()
                                .put(filter("pipeline", "EQ", config.pipelineId))
                                .put(filter("dealstage", "EQ", config.stageId))
                                .put(filter("hubspot_owner_id", "EQ", config.ownerId))
                                .put(JSONObject().put("propertyName", config.linkProperty).put("operator", "HAS_PROPERTY"))
                                .put(filter("createdate", "GTE", config.activatedAt.toEpochMilli().toString())),
                        ),
                    ),
                )
                .put("properties", JSONArray(listOf("dealname", "createdate", config.linkProperty)))
                .put("sorts", JSONArray().put(JSONObject().put("propertyName", "createdate").put("direction", "ASCENDING")))
                .put("limit", 100)
            after?.let { body.put("after", it) }

            val json = request("POST", "/crm/v3/objects/deals/search", body)
            val results = json.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                val props = r.optJSONObject("properties") ?: JSONObject()
                val created = props.optStringOrNull("createdate")?.let(::parseInstant) ?: continue
                deals += Deal(id = r.getString("id"), name = props.optStringOrNull("dealname"), createdAt = created)
            }
            after = json.optJSONObject("paging")?.optJSONObject("next")?.optStringOrNull("after") ?: return deals
        }
        return deals
    }

    override suspend fun contactsForDeal(dealId: String): List<Contact> {
        val ids = associatedIds("deals", dealId, "contacts")
        if (ids.isEmpty()) return emptyList()
        val byId = batchRead("contacts", ids, listOf("firstname", "mobilephone", "phone")).associateBy { it.getString("id") }
        // Keep HubSpot's association order.
        return ids.mapNotNull { id ->
            val props = byId[id]?.optJSONObject("properties") ?: return@mapNotNull null
            Contact(
                id = id,
                firstName = props.optStringOrNull("firstname"),
                mobilePhone = props.optStringOrNull("mobilephone"),
                phone = props.optStringOrNull("phone"),
            )
        }
    }

    override suspend fun noteBodiesForDeal(dealId: String): List<String> {
        val ids = associatedIds("deals", dealId, "notes")
        return ids.chunked(100).flatMap { chunk ->
            batchRead("notes", chunk, listOf("hs_note_body")).mapNotNull {
                it.optJSONObject("properties")?.optStringOrNull("hs_note_body")
            }
        }
    }

    override suspend fun createDealNote(dealId: String, ownerId: String, htmlBody: String, timestamp: Instant) {
        val body = JSONObject()
            .put(
                "properties",
                JSONObject()
                    .put("hs_timestamp", timestamp.toString())
                    .put("hs_note_body", htmlBody)
                    .put("hubspot_owner_id", ownerId),
            )
            .put(
                "associations",
                JSONArray().put(
                    JSONObject()
                        .put("to", JSONObject().put("id", dealId))
                        .put(
                            "types",
                            JSONArray().put(
                                JSONObject()
                                    .put("associationCategory", "HUBSPOT_DEFINED")
                                    .put("associationTypeId", NOTE_TO_DEAL),
                            ),
                        ),
                ),
            )
        request("POST", "/crm/v3/objects/notes", body)
    }

    /** Used by Settings → "Test HubSpot connection". Returns a human-readable result. */
    suspend fun testConnection(config: TexterConfig): String {
        val owner = request("GET", "/crm/v3/owners/${enc(config.ownerId)}")
        val ownerName = listOfNotNull(owner.optStringOrNull("firstName"), owner.optStringOrNull("lastName"))
            .joinToString(" ").ifBlank { owner.optStringOrNull("email") ?: config.ownerId }
        val deals = findQualifyingDeals(config)
        return "Connected. Owner ${config.ownerId} = $ownerName.\n" +
            "${deals.size} qualifying deal(s) created since the app was switched on."
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private suspend fun associatedIds(fromType: String, fromId: String, toType: String): List<String> {
        val ids = mutableListOf<String>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val query = "?limit=500" + (after?.let { "&after=${enc(it)}" } ?: "")
            val json = request("GET", "/crm/v4/objects/$fromType/${enc(fromId)}/associations/$toType$query")
            val results = json.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                ids += results.getJSONObject(i).get("toObjectId").toString()
            }
            after = json.optJSONObject("paging")?.optJSONObject("next")?.optStringOrNull("after") ?: return ids.distinct()
        }
        return ids.distinct()
    }

    private suspend fun batchRead(objectType: String, ids: List<String>, properties: List<String>): List<JSONObject> {
        val inputs = JSONArray()
        ids.forEach { inputs.put(JSONObject().put("id", it)) }
        val body = JSONObject().put("properties", JSONArray(properties)).put("inputs", inputs)
        val results = request("POST", "/crm/v3/objects/$objectType/batch/read", body).optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).map { results.getJSONObject(it) }
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val token = tokenProvider() ?: throw CrmException("HubSpot token isn't set – add it in Settings")
            val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw CrmException(describeError(code, text), code)
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (e: IOException) {
                throw CrmException("Network problem talking to HubSpot (${e.javaClass.simpleName})", cause = e)
            } finally {
                conn.disconnect()
            }
        }

    private fun describeError(code: Int, body: String): String {
        val apiMessage = runCatching { JSONObject(body).optString("message") }.getOrNull()?.take(200).orEmpty()
        val hint = when (code) {
            401 -> "HubSpot rejected the token – check it in Settings"
            403 -> "The token is missing a required scope"
            404 -> "Not found – check the IDs in Settings"
            429 -> "HubSpot rate limit hit – will retry"
            in 500..599 -> "HubSpot is having problems – will retry"
            else -> "HubSpot request failed"
        }
        return "$hint (HTTP $code)" + if (apiMessage.isNotBlank()) ": $apiMessage" else ""
    }

    private fun filter(property: String, operator: String, value: String) =
        JSONObject().put("propertyName", property).put("operator", operator).put("value", value)

    private fun parseInstant(s: String): Instant? =
        runCatching { Instant.parse(s) }.getOrNull() ?: s.toLongOrNull()?.let(Instant::ofEpochMilli)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    companion object {
        private const val BASE = "https://api.hubapi.com"
        private const val MAX_PAGES = 20

        /** HubSpot-defined association type: note → deal. */
        private const val NOTE_TO_DEAL = 214
    }
}
