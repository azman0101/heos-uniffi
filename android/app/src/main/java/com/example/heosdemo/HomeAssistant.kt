package com.example.heosdemo

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TIMEOUT_MS = 4_000

class HomeAssistantException(text: String) : Exception(text)

data class SeatPoint(val x: Double, val y: Double)

/** Position calibrée et réglage des arrières voulu à cet endroit. */
data class Spot(val name: String, val point: SeatPoint, val leftDb: Int, val rightDb: Int)

data class SeatTarget(val id: Int, val point: SeatPoint, val followed: Boolean)

/** Réponse des services de l'intégration `heos_seat` (`status`, `save_spot`, …). */
data class SeatStatus(
    val enabled: Boolean,
    val spots: List<Spot>,
    val targets: List<SeatTarget>,
    /** Personnes proches d'une position calibrée. */
    val inZone: Int,
    val offsetLeftDb: Int,
    val offsetRightDb: Int,
    val error: String?,
)

/** Appels aux services de l'intégration `heos_seat` (API REST, jeton longue durée). */
class HomeAssistant(private val baseUrl: String, private val token: String) {

    suspend fun status(): SeatStatus = parseStatus(call("status", response = true))

    /** Enregistre la position actuelle de la seule personne vue par le radar. */
    suspend fun saveSpot(name: String): SeatStatus =
        parseStatus(call("save_spot", JSONObject().put("name", name), response = true))

    suspend fun setSpotLevels(name: String, leftDb: Int, rightDb: Int): SeatStatus = parseStatus(
        call(
            "set_spot_levels",
            JSONObject().put("name", name).put("left_db", leftDb).put("right_db", rightDb),
            response = true,
        ),
    )

    suspend fun removeSpot(name: String): SeatStatus =
        parseStatus(call("remove_spot", JSONObject().put("name", name), response = true))

    suspend fun setEnabled(enabled: Boolean) {
        call("set_enabled", JSONObject().put("enabled", enabled))
    }

    private suspend fun call(
        service: String,
        body: JSONObject = JSONObject(),
        response: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
        val conn = try {
            val url = URL("${baseUrl.trimEnd('/')}/api/services/heos_seat/$service" + if (response) "?return_response" else "")
            if (url.protocol !in setOf("http", "https") || url.host.isBlank()) {
                throw HomeAssistantException("Adresse Home Assistant invalide (http:// ou https:// requis)")
            }
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        } catch (e: java.net.MalformedURLException) {
            throw HomeAssistantException("Adresse Home Assistant invalide (http:// ou https:// requis)")
        } catch (e: java.io.IOException) {
            throw HomeAssistantException("Home Assistant injoignable")
        }
        try {
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().decodeToString() }.orEmpty()
            when {
                code == 401 -> throw HomeAssistantException("Jeton refusé par Home Assistant")
                code == 400 && text.contains("not found", ignoreCase = true) ->
                    throw HomeAssistantException("Intégration HEOS Seat absente de Home Assistant")
                code >= 400 -> throw HomeAssistantException(
                    runCatching { JSONObject(text).getString("message") }.getOrDefault("Erreur $code"),
                )
            }
            if (response) JSONObject(text).getJSONObject("service_response") else JSONObject()
        } catch (e: java.io.IOException) {
            throw HomeAssistantException("Home Assistant injoignable")
        } finally {
            conn.disconnect()
        }
    }
}

private fun JSONObject.point() = SeatPoint(getDouble("x"), getDouble("y"))

private fun parseStatus(json: JSONObject): SeatStatus {
    val spots = json.getJSONArray("spots")
    val targets = json.getJSONArray("targets")
    val offsets = json.getJSONObject("offsets_db")
    return SeatStatus(
        enabled = json.getBoolean("enabled"),
        spots = (0 until spots.length()).map { i ->
            val s = spots.getJSONObject(i)
            Spot(s.getString("name"), s.point(), s.getInt("left_db"), s.getInt("right_db"))
        },
        targets = (0 until targets.length()).map { i ->
            val t = targets.getJSONObject(i)
            SeatTarget(t.getInt("id"), t.point(), t.getBoolean("followed"))
        },
        inZone = json.getInt("in_zone"),
        offsetLeftDb = offsets.getInt("left"),
        offsetRightDb = offsets.getInt("right"),
        error = if (json.isNull("error")) null else json.getString("error"),
    )
}
