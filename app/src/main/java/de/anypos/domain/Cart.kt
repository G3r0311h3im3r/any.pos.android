package de.anypos.domain

import de.anypos.data.Article
import de.anypos.data.BusinessCase
import de.anypos.data.TaxRate
import de.anypos.data.Voucher
import de.anypos.data.VoucherType

data class CartLine(
    val key: Long,
    val articleId: Long?,
    val sku: String,
    val name: String,
    val quantityMilli: Long,
    val unitPriceCents: Long,
    val discountCents: Long,
    val taxRate: TaxRate,
    val businessCase: String = BusinessCase.SALE,
    val gtin: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val voucherCode: String? = null,
) {
    val grossCents: Long get() = Money.lineTotal(unitPriceCents, quantityMilli)
    val totalCents: Long get() = grossCents - discountCents

    /** Nur Warenpositionen dürfen in Menge, Preis und Rabatt verändert werden. */
    val editable: Boolean get() = businessCase == BusinessCase.SALE
}

/** Unveränderlicher Warenkorb; jede Operation liefert einen neuen Zustand. */
data class Cart(val lines: List<CartLine> = emptyList(), private val nextKey: Long = 1) {

    val totalCents: Long get() = lines.sumOf { it.totalCents }
    val isEmpty: Boolean get() = lines.isEmpty()

    /** Summe vor Gutscheineinlösungen; begrenzt, wie viel ein Gutschein maximal decken darf. */
    val totalBeforeRedemptionsCents: Long get() =
        lines.filter { it.businessCase !in BusinessCase.voucherRedemptions }.sumOf { it.totalCents }

    fun add(
        article: Article, taxRate: TaxRate, priceCents: Long = article.priceCents, quantityMilli: Long = 1000,
        categoryName: String? = null,
    ): Cart {
        // Gleicher Artikel zum gleichen Preis ohne Rabatt wird zusammengefasst
        val existing = lines.lastOrNull()
        if (existing != null && existing.editable && existing.articleId == article.id && existing.unitPriceCents == priceCents && existing.discountCents == 0L) {
            return copy(lines = lines.dropLast(1) + existing.copy(quantityMilli = existing.quantityMilli + quantityMilli))
        }
        val line = CartLine(
            nextKey, article.id, article.sku, article.name, quantityMilli, priceCents, 0, taxRate,
            gtin = article.barcode, categoryId = article.categoryId, categoryName = categoryName,
        )
        return copy(lines = lines + line, nextKey = nextKey + 1)
    }

    /**
     * Gutscheinverkauf. Einzweckgutschein mit dem Steuersatz der späteren Leistung,
     * Mehrzweckgutschein mit dem Satz "nicht steuerbar" (USt-Schlüssel 5).
     */
    fun addVoucherSale(code: String, type: String, amountCents: Long, taxRate: TaxRate): Cart {
        require(amountCents > 0)
        require(lines.none { it.voucherCode == code }) { "Gutschein $code ist bereits im Warenkorb" }
        val bc = if (type == VoucherType.SINGLE) BusinessCase.SINGLE_VOUCHER_SALE else BusinessCase.MULTI_VOUCHER_SALE
        val line = CartLine(nextKey, null, "", "Gutschein $code", 1000, amountCents, 0, taxRate, bc, voucherCode = code)
        return copy(lines = lines + line, nextKey = nextKey + 1)
    }

    /** Gutscheineinlösung als negative Position (DSFinV-K), höchstens bis zum offenen Betrag. */
    fun addVoucherRedemption(voucher: Voucher, amountCents: Long, taxRate: TaxRate): Cart {
        require(amountCents > 0)
        require(lines.none { it.voucherCode == voucher.code }) { "Gutschein ${voucher.code} ist bereits im Warenkorb" }
        val bc = if (voucher.type == VoucherType.SINGLE) BusinessCase.SINGLE_VOUCHER_REDEEM else BusinessCase.MULTI_VOUCHER_REDEEM
        val line = CartLine(nextKey, null, "", "Gutschein ${voucher.code}", 1000, -amountCents, 0, taxRate, bc, voucherCode = voucher.code)
        return copy(lines = lines + line, nextKey = nextKey + 1)
    }

    /** Trinkgeld im Warenkorb (höchstens eine Zeile). */
    val tipCents: Long get() = lines.filter { it.businessCase in BusinessCase.tips }.sumOf { it.totalCents }

    /** Zu zahlender Betrag ohne Trinkgeld – Grundlage der Trinkgeldstufen. */
    val totalWithoutTipCents: Long get() = totalCents - tipCents

    /**
     * Setzt das Trinkgeld als eigene, nicht bearbeitbare Position am Ende ([businessCase] TrinkgeldAN/AG);
     * 0 entfernt es. Ein bestehendes Trinkgeld wird ersetzt.
     */
    fun withTip(cents: Long, businessCase: String, taxRate: TaxRate): Cart {
        require(businessCase in BusinessCase.tips)
        require(cents >= 0) { "Trinkgeld darf nicht negativ sein" }
        val rest = lines.filterNot { it.businessCase in BusinessCase.tips }
        if (cents == 0L) return copy(lines = rest)
        require(rest.isNotEmpty() && totalWithoutTipCents > 0) { "Trinkgeld nur bei einem Verkauf mit positivem Betrag" }
        val line = CartLine(nextKey, null, "", "Trinkgeld", 1000, cents, 0, taxRate, businessCase)
        return copy(lines = rest + line, nextKey = nextKey + 1)
    }

    fun withoutTip(): Cart = if (tipCents == 0L) this else copy(lines = lines.filterNot { it.businessCase in BusinessCase.tips })

    fun setQuantity(key: Long, quantityMilli: Long): Cart =
        if (quantityMilli == 0L) remove(key)
        else edit(key) { it.copy(quantityMilli = quantityMilli, discountCents = it.discountCents.coerceAtMost(Money.lineTotal(it.unitPriceCents, quantityMilli).coerceAtLeast(0))) }

    fun setUnitPrice(key: Long, priceCents: Long): Cart = edit(key) { it.copy(unitPriceCents = priceCents, discountCents = 0) }

    /** Rabatt in Prozent (Basispunkte) auf eine Position. */
    fun setDiscountPercent(key: Long, percentBp: Int): Cart =
        edit(key) { it.copy(discountCents = Money.percentOf(it.grossCents, percentBp.coerceIn(0, 10000))) }

    fun setDiscountAmount(key: Long, discountCents: Long): Cart =
        edit(key) { it.copy(discountCents = discountCents.coerceIn(0, it.grossCents.coerceAtLeast(0))) }

    fun remove(key: Long): Cart = copy(lines = lines.filterNot { it.key == key })

    fun clear(): Cart = Cart(nextKey = nextKey)

    private fun edit(key: Long, change: (CartLine) -> CartLine): Cart =
        copy(lines = lines.map { if (it.key == key && it.editable) change(it) else it })
}
