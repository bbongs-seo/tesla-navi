package dev.teslanavi.drive

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Bounded app-private diagnostic history; contains no keys or vehicle payloads. */
internal class ConnectionHistory(context: Context) {
    private val prefs = context.getSharedPreferences("connection-history", Context.MODE_PRIVATE)
    private var entries: List<String> = runCatching {
        val data = JSONArray(prefs.getString("entries", "[]"))
        (0 until data.length()).map { data.getString(it).take(800) }.takeLast(LIMIT)
    }.getOrDefault(emptyList())

    @Synchronized fun read(): List<String> = entries.toList()

    @Synchronized fun append(message: String): List<String> {
        val safe = message.replace(Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b"), "[VIN]")
            .replace('\n', ' ').replace('\r', ' ').take(600)
        val time = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        entries = (entries + "$time  $safe").takeLast(LIMIT)
        runCatching {
            check(prefs.edit().putString("entries", JSONArray(entries).toString()).commit())
        }.onFailure { Log.w("TeslaNaviConnection", "연결 기록 파일 저장 실패: ${it.javaClass.simpleName}") }
        return entries.toList()
    }

    companion object { const val LIMIT = 160 }
}
