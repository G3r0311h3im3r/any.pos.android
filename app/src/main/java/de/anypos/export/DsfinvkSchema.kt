package de.anypos.export

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tabellenbeschreibung der DSFinV-K, gelesen aus der offiziellen index.xml (GDPdU-Beschreibungsstandard).
 * Die index.xml liegt unverändert unter assets/dsfinvk/ und ist die einzige Quelle für Spaltenreihenfolge und -typen.
 */
class DsfinvkSchema(val tables: List<Table>) {

    data class Column(val name: String, val numeric: Boolean, val accuracy: Int, val maxLength: Int?)

    data class Table(val file: String, val name: String, val columns: List<Column>) {
        private val byName = columns.associateBy { it.name }
        fun column(name: String): Column =
            byName[name] ?: throw IllegalArgumentException("$file: unbekannte Spalte $name")
    }

    fun table(file: String): Table = tables.firstOrNull { it.file == file } ?: throw IllegalArgumentException("Unbekannte Tabelle $file")

    companion object {
        fun parse(xml: InputStream): DsfinvkSchema {
            val builder = DocumentBuilderFactory.newInstance().apply { isValidating = false; isNamespaceAware = false }.newDocumentBuilder()
            // Die referenzierte DTD wird nicht geladen
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val doc = xml.use { builder.parse(it) }
            val tables = doc.getElementsByTagName("Table").elements().map { t ->
                val columns = t.getElementsByTagName("VariableColumn").elements().map { c ->
                    val numeric = c.getElementsByTagName("Numeric").elements().firstOrNull()
                    Column(
                        name = c.child("Name")!!,
                        numeric = numeric != null,
                        accuracy = numeric?.child("Accuracy")?.toInt() ?: 0,
                        maxLength = c.child("MaxLength")?.toInt(),
                    )
                }
                Table(t.child("URL")!!, t.child("Name")!!, columns)
            }
            return DsfinvkSchema(tables)
        }

        private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

        private fun Element.child(tag: String): String? =
            (0 until childNodes.length).map { childNodes.item(it) }
                .firstOrNull { it is Element && it.tagName == tag }?.textContent?.trim()
    }
}

/**
 * Sammelt Datenzeilen je Tabelle und erzeugt CSV nach den Vorgaben der index.xml:
 * UTF-8, Semikolon, CRLF, Textbegrenzer ", Dezimalkomma, Kopfzeile (Range From 2).
 */
class DsfinvkTableWriter(private val schema: DsfinvkSchema) {
    private val rows = LinkedHashMap<String, MutableList<List<String>>>()
    val warnings = mutableListOf<String>()

    /** Werte: String, Long/Int (ganzzahlig), BigDecimal, Boolean (0/1) oder null (leer). */
    fun add(file: String, values: Map<String, Any?>) {
        val table = schema.table(file)
        values.keys.forEach { table.column(it) } // Tippfehler in Spaltennamen sofort erkennen
        rows.getOrPut(file) { mutableListOf() } += table.columns.map { format(table, it, values[it.name]) }
    }

    fun rowCount(file: String) = rows[file]?.size ?: 0

    fun csv(file: String): String {
        val table = schema.table(file)
        return buildString {
            append(table.columns.joinToString(";") { it.name }).append("\r\n")
            rows[file].orEmpty().forEach { append(it.joinToString(";")).append("\r\n") }
        }
    }

    private fun format(table: DsfinvkSchema.Table, c: DsfinvkSchema.Column, v: Any?): String {
        if (v == null) return ""
        if (c.numeric) {
            val d = when (v) {
                is BigDecimal -> v
                is Long -> BigDecimal.valueOf(v)
                is Int -> BigDecimal.valueOf(v.toLong())
                else -> throw IllegalArgumentException("${table.file}.${c.name}: Zahl erwartet, war $v")
            }
            return number(d, c.accuracy)
        }
        val s = when (v) {
            is Boolean -> if (v) "1" else "0"
            else -> v.toString()
        }
        val text = c.maxLength?.let { max ->
            if (s.length > max) {
                warnings += "${table.file}.${c.name}: Wert auf $max Zeichen gekürzt (\"${s.take(30)}…\")"
                s.take(max)
            } else s
        } ?: s
        if (text.isEmpty()) return ""
        return "\"" + text.replace("\"", "\"\"") + "\""
    }

    companion object {
        /**
         * Beträge mit mindestens 2 Nachkommastellen, bei Genauigkeit 5 so viele wie nötig;
         * Mengen (Genauigkeit 3) immer 3 Stellen; Ganzzahlen ohne Nachkommastellen.
         */
        fun number(v: BigDecimal, accuracy: Int): String {
            val scaled = when {
                accuracy <= 0 -> v.setScale(0, RoundingMode.HALF_UP)
                accuracy == 3 -> v.setScale(3, RoundingMode.HALF_UP)
                accuracy <= 2 -> v.setScale(accuracy, RoundingMode.HALF_UP)
                else -> {
                    val exact = v.setScale(accuracy, RoundingMode.HALF_UP).stripTrailingZeros()
                    exact.setScale(maxOf(2, exact.scale()))
                }
            }
            return scaled.toPlainString().replace('.', ',')
        }
    }
}
