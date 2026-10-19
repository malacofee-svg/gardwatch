package fr.cherif.gardwatch

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private val sb = StringBuilder()
    @Volatile var listener: ((String) -> Unit)? = null

    fun log(s: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date()) + "  " + s
        synchronized(sb) {
            sb.append(line).append('\n')
            if (sb.length > 40000) sb.delete(0, 10000)
        }
        android.util.Log.d("GardWatch", s)
        listener?.invoke(line)
    }

    fun all(): String = synchronized(sb) { sb.toString() }
}

fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }
