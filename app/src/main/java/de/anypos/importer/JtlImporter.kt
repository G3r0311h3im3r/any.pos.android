package de.anypos.importer

import de.anypos.data.Article
import de.anypos.data.Category
import de.anypos.data.TaxRate
import de.anypos.domain.Money

/**
 * Artikelimport aus JTL (JTL-Wawi/JTL-Ameise-Export bzw. Zielformat "jtl-items-v1").
 *
 * Spalten werden über Aliasnamen erkannt (Groß-/Kleinschreibung, Leerzeichen, Punkte egal).
 * Der Import ist zweistufig: [analyze] erzeugt einen vollständig validierten Plan ohne Schreibzugriff,
 * erst nach Bestätigung wird der Plan übernommen. Fehlerhafte Zeilen werden nie teilweise übernommen.
 */
object JtlImporter {

    enum class Field(vararg val aliases: String) {
        EXTERNAL_ID("external_id", "kArtikel", "Artikel-ID", "ArtikelID", "Interne Artikel-ID", "Interne ID"),
        SKU("sku", "Artikelnummer", "Art.Nr.", "ArtNr", "Artikelnr", "Artikel-Nr."),
        NAME("name", "Artikelname", "Bezeichnung", "Artikelbezeichnung", "Name"),
        PRICE_GROSS("unit_price", "VK Brutto", "Brutto-VK", "VK-Brutto", "Verkaufspreis Brutto", "Bruttopreis", "Standard-VK Brutto"),
        PRICE_NET("VK Netto", "Netto-VK", "VK-Netto", "Verkaufspreis Netto", "Nettopreis", "Standard-VK Netto"),
        VAT("vat_rate", "Steuersatz", "MwSt", "MwSt.", "USt", "Steuersatz in %", "Mehrwertsteuer"),
        BARCODE("barcode", "GTIN", "EAN", "Barcode", "EAN/GTIN"),
        CATEGORY("category", "Warengruppe", "Kategorie", "Kategoriename"),
        ACTIVE("active", "Aktiv", "Artikel aktiv", "Status", "Verkaufbar", "POS aktiv"),
        CURRENCY("currency", "Währung"),
        OPEN_PRICE("open_price", "Freipreis", "Preis abfragen"),
    }

    data class Row(
        val line: Int,
        val externalId: String?,
        val sku: String,
        val name: String,
        val priceCents: Long,
        val taxRate: TaxRate,
        val barcode: String?,
        val category: String?,
        val active: Boolean,
        val openPrice: Boolean,
    )

    enum class Change { NEW, UPDATED, UNCHANGED }

    data class PlannedArticle(val row: Row, val existing: Article?, val change: Change)

    data class Plan(
        val articles: List<PlannedArticle>,
        val errors: List<String>,
        val newCategories: List<String>,
        val mapping: Map<Field, String>,
        val usedNetPrices: Boolean,
        /** Artikel, die in der Kasse existieren, aber nicht in der Datei vorkommen (werden NICHT automatisch geändert). */
        val missingInFile: List<Article>,
    ) {
        val created get() = articles.count { it.change == Change.NEW }
        val updated get() = articles.count { it.change == Change.UPDATED }
        val unchanged get() = articles.count { it.change == Change.UNCHANGED }
        val canApply get() = errors.isEmpty() && articles.isNotEmpty()
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s._\\-/()%]"), "")

    fun mapHeader(header: List<String>): Map<Field, Int> {
        val normalized = header.map { norm(it) }
        return Field.entries.mapNotNull { f ->
            val idx = f.aliases.map { norm(it) }.firstNotNullOfOrNull { a -> normalized.indexOf(a).takeIf { it >= 0 } }
            idx?.let { f to it }
        }.toMap()
    }

    fun parseBool(raw: String, default: Boolean): Boolean = when (raw.trim().lowercase()) {
        "" -> default
        "true", "1", "ja", "j", "y", "yes", "aktiv", "x", "wahr" -> true
        "false", "0", "nein", "n", "no", "inaktiv", "falsch" -> false
        else -> default
    }

    fun analyze(text: String, taxRates: List<TaxRate>, existing: List<Article>, categories: List<Category>): Plan {
        val rows = Csv.parse(text)
        if (rows.isEmpty()) return Plan(emptyList(), listOf("Datei ist leer"), emptyList(), emptyMap(), false, emptyList())
        val header = rows.first()
        val map = mapHeader(header)
        val errors = mutableListOf<String>()

        val required = listOf(Field.SKU, Field.NAME, Field.VAT)
        required.filter { it !in map }.forEach { errors += "Pflichtspalte fehlt: ${it.aliases.take(3).joinToString(" / ")}" }
        if (Field.PRICE_GROSS !in map && Field.PRICE_NET !in map) errors += "Preisspalte fehlt (VK Brutto oder VK Netto)"
        if (errors.isNotEmpty()) return Plan(emptyList(), errors, emptyList(), map.mapValues { header[it.value] }, false, emptyList())

        val usedNet = Field.PRICE_GROSS !in map
        val bySku = existing.associateBy { it.sku }
        val byExt = existing.filter { it.externalId != null }.associateBy { it.externalId!! }
        val seenSku = HashMap<String, Int>()
        val seenExt = HashMap<String, Int>()
        val parsed = mutableListOf<Row>()

        for ((index, cells) in rows.drop(1).withIndex()) {
            val lineNo = index + 2
            fun get(f: Field) = map[f]?.let { cells.getOrNull(it) }?.trim().orEmpty()

            val sku = get(Field.SKU)
            val name = get(Field.NAME)
            val ext = get(Field.EXTERNAL_ID).ifEmpty { null }
            val currency = get(Field.CURRENCY)
            val rowErrors = mutableListOf<String>()

            if (sku.isEmpty()) rowErrors += "Artikelnummer fehlt"
            if (name.isEmpty()) rowErrors += "Bezeichnung fehlt"
            if (currency.isNotEmpty() && currency.uppercase() != "EUR") rowErrors += "Währung '$currency' nicht unterstützt"

            val vat = Money.parseDecimal(get(Field.VAT).replace("%", ""))
            val vatBp = vat?.movePointRight(2)?.toInt()
            // Bei mehreren Sätzen mit 0 % wird der erste (nicht steuerbar) verwendet; abweichend bitte manuell pflegen.
            val taxRate = vatBp?.let { bp -> taxRates.firstOrNull { it.percentBp == bp } }
            if (taxRate == null) rowErrors += "Steuersatz '${get(Field.VAT)}' unbekannt"

            val priceRaw = if (usedNet) get(Field.PRICE_NET) else get(Field.PRICE_GROSS)
            val price = Money.parseDecimal(priceRaw)
            if (price == null) rowErrors += "Preis '$priceRaw' ungültig"
            else if (price.signum() < 0) rowErrors += "Preis darf nicht negativ sein"

            seenSku[sku]?.let { rowErrors += "Artikelnummer $sku doppelt (auch Zeile $it)" }
            ext?.let { e -> seenExt[e]?.let { rowErrors += "Artikel-ID $e doppelt (auch Zeile $it)" } }

            if (rowErrors.isNotEmpty()) { errors += "Zeile $lineNo: ${rowErrors.joinToString("; ")}"; continue }
            seenSku[sku] = lineNo
            ext?.let { seenExt[it] = lineNo }

            val cents = if (usedNet) Money.grossFromNet(price!!, taxRate!!.percentBp)
            else price!!.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()

            parsed += Row(
                line = lineNo, externalId = ext, sku = sku, name = name, priceCents = cents, taxRate = taxRate!!,
                barcode = get(Field.BARCODE).ifEmpty { null }, category = get(Field.CATEGORY).ifEmpty { null },
                active = parseBool(get(Field.ACTIVE), default = true), openPrice = parseBool(get(Field.OPEN_PRICE), default = false),
            )
        }

        val categoryNames = categories.map { it.name.lowercase() }.toSet()
        val newCategories = parsed.mapNotNull { it.category }.distinctBy { it.lowercase() }.filter { it.lowercase() !in categoryNames }
        val categoryIdByName = categories.associate { it.name.lowercase() to it.id }

        val planned = parsed.map { row ->
            val match = row.externalId?.let { byExt[it] } ?: bySku[row.sku]
            // Artikelnummer gehört bereits einem anderen JTL-Artikel
            val conflict = bySku[row.sku]?.takeIf { match != null && it.id != match.id }
            if (conflict != null) errors += "Zeile ${row.line}: Artikelnummer ${row.sku} gehört bereits zu einem anderen Artikel"
            val change = when {
                match == null -> Change.NEW
                match.name == row.name && match.priceCents == row.priceCents && match.taxRateId == row.taxRate.id &&
                    match.barcode == row.barcode && match.active == row.active && match.openPrice == row.openPrice &&
                    match.externalId == row.externalId && match.sku == row.sku &&
                    match.categoryId == row.category?.let { categoryIdByName[it.lowercase()] } -> Change.UNCHANGED
                else -> Change.UPDATED
            }
            PlannedArticle(row, match, change)
        }
        val matchedIds = planned.mapNotNull { it.existing?.id }.toSet()
        return Plan(
            articles = planned,
            errors = errors,
            newCategories = newCategories,
            mapping = map.mapValues { header[it.value] },
            usedNetPrices = usedNet,
            missingInFile = existing.filter { it.id !in matchedIds && it.active },
        )
    }

    /** Wandelt den Plan in zu speichernde Artikel um; [categoryIds] enthält alle (auch neu angelegte) Warengruppen. */
    fun toArticles(plan: Plan, categoryIds: Map<String, Long>, now: Long): List<Article> =
        plan.articles.filter { it.change != Change.UNCHANGED }.map { p ->
            val r = p.row
            Article(
                id = p.existing?.id ?: 0,
                externalId = r.externalId,
                sku = r.sku,
                name = r.name,
                priceCents = r.priceCents,
                taxRateId = r.taxRate.id,
                categoryId = r.category?.let { categoryIds[it.lowercase()] },
                barcode = r.barcode,
                active = r.active,
                openPrice = r.openPrice,
                updatedAt = now,
            )
        }
}
