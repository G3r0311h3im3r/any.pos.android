package de.anypos.payment

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Übersetzt die Rückgabe des SumUp Reader SDK (`SumUpAPI.Response`) in ein [CardPaymentResult].
 * Ohne Android-Abhängigkeit, damit jeder Fall getestet werden kann.
 *
 * Grundsatz: Nur wenn sicher ist, dass **nichts** abgebucht wurde, gilt „abgelehnt“. Alles, was eine Abbuchung nicht
 * ausschließt (unbekannter Status, doppelte Referenz, fehlende Rückgabe, abweichender Betrag), ist „unbekannt“ und wird
 * vom Kassierer geklärt.
 */
object SumUpResults {
    // SumUpAPI.Response.ResultCode (SDK 7.1)
    const val SUCCESSFUL = 1
    const val ERROR_TRANSACTION_FAILED = 2
    const val ERROR_GEOLOCATION_REQUIRED = 3
    const val ERROR_INVALID_PARAM = 4
    const val ERROR_INVALID_TOKEN = 5
    const val ERROR_NO_CONNECTIVITY = 6
    const val ERROR_PERMISSION_DENIED = 7
    const val ERROR_NOT_LOGGED_IN = 8
    const val ERROR_DUPLICATE_FOREIGN_TX_ID = 9
    const val ERROR_INVALID_AFFILIATE_KEY = 10
    const val ERROR_ALREADY_LOGGED_IN = 11
    const val ERROR_INVALID_AMOUNT_DECIMALS = 12
    const val ERROR_API_LEVEL_TOO_LOW = 13
    const val ERROR_CARD_READER_SETTINGS_OFF = 14
    const val ERROR_UNKNOWN_TRANSACTION_STATUS = 15

    /** Rückgabe des SDK, soweit für die Kasse nötig (Werte aus Intent-Extras und `TransactionInfo`). */
    data class Raw(
        val resultCode: Int?,
        val message: String? = null,
        val txCode: String? = null,
        /** `TransactionInfo.status`: PENDING, SUCCESSFUL, CANCELLED, FAILED */
        val status: String? = null,
        val cardType: String? = null,
        val last4: String? = null,
        /** `TransactionInfo.amount` (inkl. Trinkgeld) in Euro. */
        val amount: Double? = null,
    )

    fun map(raw: Raw, expectedCents: Long): CardPaymentResult {
        val msg = raw.message?.takeIf { it.isNotBlank() }
        return when (raw.resultCode) {
            SUCCESSFUL -> {
                val tx = raw.txCode ?: return CardPaymentResult.Unknown("SumUp meldet Erfolg ohne Transaktionscode")
                if (raw.status != null && raw.status != "SUCCESSFUL") {
                    return CardPaymentResult.Unknown("SumUp meldet Erfolg, Status ${raw.status}")
                }
                val paid = raw.amount?.let { cents(it) }
                if (paid != null && paid != expectedCents) {
                    return CardPaymentResult.Unknown("SumUp meldet ${paid / 100.0} € statt ${expectedCents / 100.0} €")
                }
                CardPaymentResult.Approved(tx, raw.cardType, raw.last4)
            }
            ERROR_TRANSACTION_FAILED -> when (raw.status) {
                "CANCELLED" -> CardPaymentResult.Cancelled
                "PENDING" -> CardPaymentResult.Unknown(msg ?: "Zahlung noch in Bearbeitung")
                else -> CardPaymentResult.Declined(msg ?: "Zahlung fehlgeschlagen")
            }
            ERROR_UNKNOWN_TRANSACTION_STATUS -> CardPaymentResult.Unknown(msg ?: "Status der Zahlung unbekannt")
            // Mit dieser Referenz gibt es schon eine Transaktion – sie könnte abgebucht sein
            ERROR_DUPLICATE_FOREIGN_TX_ID -> CardPaymentResult.Unknown("Referenz bei SumUp bereits vorhanden")
            null -> CardPaymentResult.Unknown("Keine Rückmeldung vom SumUp-Bezahlvorgang")
            // Vor dem Bezahlen gescheitert: nichts abgebucht
            ERROR_NOT_LOGGED_IN -> CardPaymentResult.Declined("Nicht bei SumUp angemeldet (Einstellungen → Kartenzahlung)")
            ERROR_INVALID_AFFILIATE_KEY -> CardPaymentResult.Declined("SumUp-Affiliate-Key ungültig oder nicht für de.anypos freigegeben")
            ERROR_CARD_READER_SETTINGS_OFF -> CardPaymentResult.Declined("Bluetooth/Standort ist aus – für das Kartenlesegerät einschalten")
            ERROR_PERMISSION_DENIED -> CardPaymentResult.Declined("Berechtigung für das Kartenlesegerät fehlt (Bluetooth/Standort)")
            ERROR_GEOLOCATION_REQUIRED -> CardPaymentResult.Declined("SumUp braucht den Standort – bitte einschalten")
            ERROR_NO_CONNECTIVITY -> CardPaymentResult.Declined("Keine Internetverbindung – SumUp braucht Internet")
            else -> CardPaymentResult.Declined(msg ?: "SumUp-Fehler ${raw.resultCode}")
        }
    }

    /** Betrag für das SDK: `total` ohne Trinkgeld, Trinkgeld getrennt (SumUp addiert es). */
    fun total(amountCents: Long, tipCents: Long): BigDecimal = euros(amountCents - tipCents)

    fun euros(cents: Long): BigDecimal = BigDecimal.valueOf(cents, 2)

    private fun cents(euros: Double): Long = BigDecimal.valueOf(euros).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
}
