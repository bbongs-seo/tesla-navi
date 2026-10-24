package dev.teslanavi.drive

/**
 * Near a parked car, automatic attempts repeat about once a minute with the same outcome and used to fill the
 * 160-line connection history within minutes. An automatic attempt's lines are held until its outcome is known:
 * a new outcome writes them in full, a repeated one only counts, and a working connection writes everything.
 * Manual attempts are always written as they happen.
 */
internal class AttemptLog(private val summaryEvery: Int = 30) {
    private var holding = false
    private val held = mutableListOf<String>()
    private var lastOutcome: String? = null
    private var repeats = 0

    /** Lines to write for the start of an attempt. */
    fun begin(automatic: Boolean, line: String): List<String> {
        held.clear()
        holding = automatic
        if (!automatic) return pending() + line
        held += line
        return emptyList()
    }

    /** Lines to write now for a progress line. */
    fun line(text: String): List<String> {
        if (!holding) return listOf(text)
        held += text
        return emptyList()
    }

    /** The attempt reached a working connection; write what was held back. */
    fun connected(): List<String> {
        val out = pending() + held
        held.clear(); holding = false
        return out
    }

    /** Lines to write for a finished attempt. [key] identifies the outcome regardless of time and counters. */
    fun failed(key: String, line: String): List<String> {
        if (!holding) return pending() + line
        holding = false
        if (key == lastOutcome) {
            repeats++
            held.clear()
            return if (repeats % summaryEvery == 0) listOf("같은 자동 연결 결과 ${repeats}회 반복 · $line") else emptyList()
        }
        val out = pending() + held + line
        held.clear()
        lastOutcome = key
        return out
    }

    /** The attempt was cancelled without an outcome; nothing it held is worth keeping. */
    fun cancelled() { held.clear(); holding = false }

    // Closes a run of identical outcomes before anything else is written.
    private fun pending(): List<String> {
        val out = if (repeats > 0) listOf("이전 자동 연결 결과가 ${repeats}회 더 반복됨") else emptyList()
        repeats = 0
        lastOutcome = null
        return out
    }
}
