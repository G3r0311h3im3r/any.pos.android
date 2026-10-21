package de.anypos

import de.anypos.data.Article
import de.anypos.data.Category
import de.anypos.data.PosDatabase
import de.anypos.importer.Csv
import de.anypos.importer.JtlImporter
import de.anypos.importer.JtlImporter.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JtlImporterTest {
    private val rates = PosDatabase.DEFAULT_TAX_RATES

    @Test fun csvHandlesQuotesDelimitersAndBom() {
        val rows = Csv.parse("﻿a;b;c\r\n\"x;y\";\"he said \"\"hi\"\"\";\"multi\nline\"\n")
        assertEquals(listOf("a", "b", "c"), rows[0])
        assertEquals(listOf("x;y", "he said \"hi\"", "multi\nline"), rows[1])
    }

    @Test fun importsTargetFormatV1() {
        val csv = """
            external_id;sku;name;unit_price;currency;vat_rate;barcode;category;active
            JTL-10001;10001;"Kaffee klein";2.50;EUR;19.00;4012345678901;Getränke;true
            JTL-10002;10002;"Buch";14.90;EUR;7.00;;Bücher;true
        """.trimIndent()
        val plan = JtlImporter.analyze(csv, rates, emptyList(), emptyList())
        assertTrue(plan.errors.toString(), plan.canApply)
        assertEquals(2, plan.created)
        assertEquals(listOf("Getränke", "Bücher"), plan.newCategories)
        val articles = JtlImporter.toArticles(plan, mapOf("getränke" to 1L, "bücher" to 2L), 0)
        assertEquals(250L, articles[0].priceCents)
        assertEquals(1L, articles[0].taxRateId)
        assertEquals(2L, articles[1].taxRateId)
        assertEquals(2L, articles[1].categoryId)
        assertEquals(null, articles[1].barcode)
    }

    @Test fun importsJtlAmeiseStyleExportWithNetPricesAndGermanDecimals() {
        val csv = "Artikelnummer;Artikelname;VK Netto;Steuersatz;GTIN;Warengruppe;Aktiv\n" +
            "A-1;Tasse;10,00;19;;Haushalt;Y\n" +
            "A-2;Brot;1,00;7,00%;400;Lebensmittel;N\n"
        val plan = JtlImporter.analyze(csv, rates, emptyList(), emptyList())
        assertTrue(plan.errors.toString(), plan.canApply)
        assertTrue(plan.usedNetPrices)
        val a = JtlImporter.toArticles(plan, emptyMap(), 0)
        assertEquals(1190L, a[0].priceCents)
        assertEquals(107L, a[1].priceCents)
        assertFalse(a[1].active)
    }

    @Test fun rejectsUnknownTaxRateDuplicatesAndBadPrices() {
        val csv = "sku;name;unit_price;vat_rate\n1;A;1.00;16\n2;B;abc;19\n3;C;1.00;19\n3;D;1.00;19\n"
        val plan = JtlImporter.analyze(csv, rates, emptyList(), emptyList())
        assertFalse(plan.canApply)
        assertEquals(3, plan.errors.size)
        assertTrue(plan.errors[0].startsWith("Zeile 2"))
        assertTrue(plan.errors[2].contains("doppelt"))
    }

    @Test fun missingRequiredColumnStopsImport() {
        val plan = JtlImporter.analyze("sku;name\n1;A\n", rates, emptyList(), emptyList())
        assertFalse(plan.canApply)
        assertTrue(plan.errors.any { it.contains("Pflichtspalte") })
        assertTrue(plan.errors.any { it.contains("Preisspalte") })
    }

    @Test fun reimportIsIdempotentAndMatchesByExternalId() {
        val existing = listOf(Article(id = 7, externalId = "JTL-1", sku = "OLD", name = "Kaffee", priceCents = 250, taxRateId = 1, categoryId = 3))
        val cats = listOf(Category(id = 3, name = "Getränke"))
        val same = "external_id;sku;name;unit_price;vat_rate;category\nJTL-1;OLD;Kaffee;2.50;19;Getränke\n"
        assertEquals(Change.UNCHANGED, JtlImporter.analyze(same, rates, existing, cats).articles.single().change)

        val changed = "external_id;sku;name;unit_price;vat_rate;category\nJTL-1;NEW;Kaffee;2.80;19;Getränke\n"
        val plan = JtlImporter.analyze(changed, rates, existing, cats)
        val p = plan.articles.single()
        assertEquals(Change.UPDATED, p.change)
        assertEquals(7L, JtlImporter.toArticles(plan, mapOf("getränke" to 3L), 0).single().id)
    }

    @Test fun articlesMissingInFileAreReportedNotDeleted() {
        val existing = listOf(Article(id = 1, sku = "1", name = "A", priceCents = 1, taxRateId = 1), Article(id = 2, sku = "2", name = "B", priceCents = 1, taxRateId = 1))
        val plan = JtlImporter.analyze("sku;name;unit_price;vat_rate\n1;A;0.01;19\n", rates, existing, emptyList())
        assertEquals(listOf(2L), plan.missingInFile.map { it.id })
        assertTrue(JtlImporter.toArticles(plan, emptyMap(), 0).none { it.id == 2L })
    }
}
