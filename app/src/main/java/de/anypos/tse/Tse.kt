package de.anypos.tse

/**
 * Abstraktion der Technischen Sicherheitseinrichtung (BSI TR-03153).
 *
 * Der Kassenkern kennt nur diese Schnittstelle. Konkrete Umsetzungen:
 *  - [NoTse]: keine TSE angeschlossen, jeder Vorgang wird als TSE-Ausfall gekennzeichnet
 *  - [SimulatedTse]: nur Entwicklung/Schulung, erzeugt sichtbar ungültige Signaturen
 *  - [EpsonTse]: Epson-TSE über den Bondrucker (Phase 2, nach Hardware-Freigabe)
 */
interface Tse {
    /** Kurzname für Anzeige und Protokoll. */
    val name: String

    /** Produktivtauglich im Sinne von §146a AO? Nur eine echte, zertifizierte TSE darf true liefern. */
    val certified: Boolean

    suspend fun status(): TseStatus

    /** Startet eine Transaktion zu Beginn eines Vorgangs. */
    suspend fun startTransaction(clientId: String): TseStart

    /** Schließt die Transaktion mit den abschließenden Vorgangsdaten ab und liefert die Signatur. */
    suspend fun finishTransaction(clientId: String, txNumber: Long, processType: String, processData: String): TseFinish
}

data class TseStatus(
    val available: Boolean,
    val serial: String? = null,
    val message: String,
    val signatureCounter: Long? = null,
    val certificateExpiry: String? = null,
)

data class TseStart(val txNumber: Long, val startTime: Long, val serial: String)

data class TseFinish(
    val txNumber: Long,
    val startTime: Long,
    val endTime: Long,
    val signatureCounter: Long,
    val signature: String,
    val serial: String,
    val signatureAlgorithm: String,
    val timeFormat: String,
    val publicKey: String,
    /** Base64-codiertes TSE-Zertifikat (DSFinV-K tse.csv). */
    val certificate: String,
)

/** TSE nicht erreichbar oder Fehler. Der Vorgang wird trotzdem aufgezeichnet und als Ausfall gekennzeichnet. */
open class TseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Kein TSE-Modul konfiguriert. Für Produktivbetrieb unzulässig – die App zeigt das dauerhaft an. */
class NoTse : Tse {
    override val name = "Keine TSE"
    override val certified = false
    override suspend fun status() = TseStatus(available = false, message = "Keine TSE konfiguriert – kein zulässiger Produktivbetrieb")
    override suspend fun startTransaction(clientId: String): TseStart = throw TseException("Keine TSE konfiguriert")
    override suspend fun finishTransaction(clientId: String, txNumber: Long, processType: String, processData: String): TseFinish =
        throw TseException("Keine TSE konfiguriert")
}
