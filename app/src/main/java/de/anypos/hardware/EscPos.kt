package de.anypos.hardware

import java.io.ByteArrayOutputStream

/**
 * ESC/POS-Befehlsaufbau für Epson TM-Drucker (TM-m30II/III, TM-T88 …).
 * Zeichensatz: PC858 (Westeuropa inkl. €), Codepage-Tabelle 19.
 */
class EscPos(val columns: Int = 48) {
    private val out = ByteArrayOutputStream()

    fun init() = raw(ESC, '@'.code, ESC, 't'.code, 19)

    fun align(a: Align) = raw(ESC, 'a'.code, a.ordinal)
    fun bold(on: Boolean) = raw(ESC, 'E'.code, if (on) 1 else 0)

    /** Doppelte Höhe/Breite. */
    fun size(doubleWidth: Boolean, doubleHeight: Boolean) =
        raw(GS, '!'.code, (if (doubleWidth) 0x10 else 0) or (if (doubleHeight) 0x01 else 0))

    fun text(s: String) = apply { out.write(encode(s)) }
    fun line(s: String = "") = text(s).raw(LF)
    fun separator(c: Char = '-') = line(c.toString().repeat(columns))

    /** Links- und rechtsbündiger Text in einer Zeile; zu lange linke Texte werden umbrochen. */
    fun row(left: String, right: String, width: Int = columns) = apply {
        val maxLeft = width - right.length - 1
        if (left.length <= maxLeft) {
            line(left + " ".repeat(width - left.length - right.length) + right)
        } else {
            wrap(left, width).let { parts ->
                parts.dropLast(1).forEach { line(it) }
                val last = parts.last()
                if (last.length <= maxLeft) line(last + " ".repeat(width - last.length - right.length) + right)
                else { line(last); line(right.padStart(width)) }
            }
        }
    }

    fun feed(lines: Int) = raw(ESC, 'd'.code, lines)

    /** Teilschnitt mit Vorschub. */
    fun cut() = raw(GS, 'V'.code, 66, 0)

    /** Schubladenimpuls über den Drucker-Kick-Anschluss: Pin 2 (m=0) oder Pin 5 (m=1). */
    fun drawerKick(pin: Int = 2) = raw(ESC, 'p'.code, if (pin == 5) 1 else 0, 25, 250)

    /** QR-Code (Modell 2, Fehlerkorrektur M) – Epson GS ( k. */
    fun qr(data: String, moduleSize: Int = 4) = apply {
        val bytes = data.toByteArray(Charsets.US_ASCII)
        raw(GS, '('.code, 'k'.code, 4, 0, 49, 65, 50, 0)
        raw(GS, '('.code, 'k'.code, 3, 0, 49, 67, moduleSize.coerceIn(1, 16))
        raw(GS, '('.code, 'k'.code, 3, 0, 49, 69, 49)
        val len = bytes.size + 3
        raw(GS, '('.code, 'k'.code, len and 0xFF, (len shr 8) and 0xFF, 49, 80, 48)
        out.write(bytes)
        raw(GS, '('.code, 'k'.code, 3, 0, 49, 81, 48)
    }

    fun raw(vararg b: Int) = apply { b.forEach { out.write(it) } }

    fun bytes(): ByteArray = out.toByteArray()

    enum class Align { LEFT, CENTER, RIGHT }

    companion object {
        const val ESC = 0x1B
        const val GS = 0x1D
        const val LF = 0x0A

        fun wrap(text: String, width: Int): List<String> {
            if (text.length <= width) return listOf(text)
            val result = mutableListOf<String>()
            var current = StringBuilder()
            for (word in text.split(' ')) {
                var w = word
                while (w.length > width) {
                    if (current.isNotEmpty()) { result += current.toString(); current = StringBuilder() }
                    result += w.take(width); w = w.drop(width)
                }
                if (current.isEmpty()) current.append(w)
                else if (current.length + 1 + w.length <= width) current.append(' ').append(w)
                else { result += current.toString(); current = StringBuilder(w) }
            }
            if (current.isNotEmpty()) result += current.toString()
            return result
        }

        /** Unicode -> PC858. Nicht abbildbare Zeichen werden zu '?'. */
        fun encode(s: String): ByteArray {
            val out = ByteArray(s.length)
            s.forEachIndexed { i, c -> out[i] = (if (c.code < 0x80) c.code else PC858[c] ?: '?'.code).toByte() }
            return out
        }

        private val PC858 = mapOf(
            'Ç' to 0x80, 'ü' to 0x81, 'é' to 0x82, 'â' to 0x83, 'ä' to 0x84, 'à' to 0x85, 'å' to 0x86,
            'ç' to 0x87, 'ê' to 0x88, 'ë' to 0x89, 'è' to 0x8A, 'ï' to 0x8B, 'î' to 0x8C, 'ì' to 0x8D,
            'Ä' to 0x8E, 'Å' to 0x8F, 'É' to 0x90, 'æ' to 0x91, 'Æ' to 0x92, 'ô' to 0x93, 'ö' to 0x94,
            'ò' to 0x95, 'û' to 0x96, 'ù' to 0x97, 'ÿ' to 0x98, 'Ö' to 0x99, 'Ü' to 0x9A, 'ø' to 0x9B,
            '£' to 0x9C, 'Ø' to 0x9D, '×' to 0x9E, 'á' to 0xA0, 'í' to 0xA1, 'ó' to 0xA2, 'ú' to 0xA3,
            'ñ' to 0xA4, 'Ñ' to 0xA5, '°' to 0xF8, '§' to 0xF5, 'ß' to 0xE1, 'µ' to 0xE6, '€' to 0xD5,
            '½' to 0xAB, '¼' to 0xAC, '²' to 0xFD, '³' to 0xFC, '·' to 0xFA, '–' to '-'.code, '„' to '"'.code,
            '“' to '"'.code, '”' to '"'.code, '’' to '\''.code,
        )
    }
}
