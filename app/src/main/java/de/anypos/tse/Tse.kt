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

    /**
     * Abgleich nach Störung oder Neustart: offene TSE-Transaktionen dieser Kasse, die nicht in [knownOpen] stehen
     * (Start-Ergebnis unbekannt, Absturz vor dem Speichern), werden als Belegabbruch abgeschlossen.
     * Liefert deren Abschlussdaten – die Kasse legt dazu je einen Vorgang „AVBelegabbruch“ an
     * (DSFinV-K 2.4 Kap. 2.1: jeder abgesicherte Vorgang muss als Vorgang erkennbar sein).
     * [TseFinish.startTime] ist dabei 0 (unbekannt).
     */
    suspend fun reconcile(clientId: String, knownOpen: Set<Long>): List<TseFinish> = emptyList()
}

/** Die TSE nimmt keine weiteren Transaktionen an (z. B. 512 offene); nach einem Abgleich erneut versuchen. */
class TseCapacityException(message: String) : TseException(message)

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
