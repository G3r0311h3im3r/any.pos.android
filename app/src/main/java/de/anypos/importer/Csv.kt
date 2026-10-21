package de.anypos.importer

/** Minimaler RFC-4180-Parser mit wählbarem Trennzeichen und Unterstützung für Zeilenumbrüche in Anführungszeichen. */
object Csv {

    fun detectDelimiter(headerLine: String): Char =
        listOf(';', '\t', ',', '|').maxBy { d -> headerLine.count { it == d } }

    fun parse(text: String, delimiter: Char = detectDelimiter(text.lineSequence().firstOrNull().orEmpty())): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            when {
                inQuotes && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> { row += field.toString(); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                    row += field.toString(); field.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotEmpty() }) rows += row
        }
        return rows
    }

    fun escape(value: String, delimiter: Char = ';'): String =
        if (value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    fun line(values: List<String>, delimiter: Char = ';'): String = values.joinToString(delimiter.toString()) { escape(it, delimiter) }
}
