package com.example.heospresence

import android.content.Context
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

private const val BAR = "192.168.1.12"
private const val PORT = 60006
private const val URN = "urn:schemas-denon-com:service:ACT:1"
private const val TIMEOUT_MS = 3_000
private const val ZERO_DB = 12 // niveau brut = 12 + dB
private const val MAX_RAW = 24
private const val KEY_LEFT = "left"
private const val KEY_RIGHT = "right"

/**
 * Niveaux des arrières de la barre (mêmes écritures que `heos_core/src/levels.rs`).
 * Les niveaux d'origine sont gardés sur disque : si l'app est tuée pendant un décalage,
 * ils sont remis à la prochaine ouverture.
 */
class RearLevels(context: Context, private val report: (String) -> Unit) {
    private val prefs = context.getSharedPreferences("rear", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var base: Pair<Int, Int>? = null
    private var applied = Seat.CENTER

    /** Niveaux réels en dB pour cette place, une fois les niveaux d'origine connus. */
    fun displayDb(seat: Seat): Pair<Int, Int>? = base?.let { (left, right) ->
        (left + seat.leftDb).coerceIn(0, MAX_RAW) - ZERO_DB to (right + seat.rightDb).coerceIn(0, MAX_RAW) - ZERO_DB
    }

    fun start() = run("niveaux lus") {
        restoreSaved()
        base = read()
        Log.i(TAG, "niveaux d'origine bruts ${base}")
    }

    fun apply(seat: Seat) = run("envoyé à la barre") {
        val (left, right) = base ?: error("niveaux d'origine inconnus")
        if (seat == applied) return@run
        prefs.edit().putInt(KEY_LEFT, left).putInt(KEY_RIGHT, right).commit()
        write("Left", (left + seat.leftDb).coerceIn(0, MAX_RAW))
        write("Right", (right + seat.rightDb).coerceIn(0, MAX_RAW))
        applied = seat
        if (seat == Seat.CENTER) clearSaved()
        Log.i(TAG, "appliqué $seat")
    }

    fun restore() = run("niveaux d'origine remis") {
        restoreSaved()
        applied = Seat.CENTER
    }

    private fun run(done: String, action: () -> Unit) = io.execute {
        try {
            action()
            report(done)
        } catch (e: Exception) {
            Log.e(TAG, "barre : ${e.message}")
            report("erreur barre : ${e.message}")
        }
    }

    private fun restoreSaved() {
        if (!prefs.contains(KEY_LEFT)) return
        write("Left", prefs.getInt(KEY_LEFT, ZERO_DB))
        write("Right", prefs.getInt(KEY_RIGHT, ZERO_DB))
        clearSaved()
        Log.i(TAG, "niveaux d'origine remis")
    }

    private fun clearSaved() {
        prefs.edit().clear().commit()
    }

    private fun read(): Pair<Int, Int> {
        val xml = soap("GetSurroundSpeakerConfig", "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        val rear = xml.indexOf("<Rear>").also { check(it >= 0) { "section Rear absente" } }
        return level(xml, rear, "Left") to level(xml, rear, "Right")
    }

    private fun level(xml: String, from: Int, side: String): Int {
        val start = xml.indexOf("<level>", xml.indexOf("<$side>", from)) + "<level>".length
        return xml.substring(start, xml.indexOf("</level>", start)).toInt()
    }

    private fun write(side: String, raw: Int) {
        val fragment = "<SurroundSpeakerConfig><Rear><$side><level>$raw</level></$side></Rear></SurroundSpeakerConfig>"
        val escaped = fragment.replace("<", "&lt;").replace(">", "&gt;")
        soap("SetSurroundSpeakerConfig", "<SurroundSpeakerConfig>$escaped</SurroundSpeakerConfig>")
    }

    /** HTTP/1.0 : la barre ferme la connexion à la fin de la réponse. */
    private fun soap(action: String, args: String): String {
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$URN">$args</u:$action></s:Body></s:Envelope>"""
        val request = "POST /ACT/control HTTP/1.0\r\nHost: $BAR:$PORT\r\n" +
            "Content-Type: text/xml; charset=\"utf-8\"\r\nSOAPACTION: \"$URN#$action\"\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
        Socket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(BAR, PORT), TIMEOUT_MS)
            socket.getOutputStream().write(request.toByteArray())
            val response = socket.getInputStream().readBytes().decodeToString()
            check(response.lineSequence().first().contains(" 200")) { response.lineSequence().first() }
            return response
        }
    }
}
