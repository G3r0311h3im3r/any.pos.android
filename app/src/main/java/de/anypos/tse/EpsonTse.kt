package de.anypos.tse

/**
 * Epson-TSE (microSD/USB-TSE im bzw. am Epson-Bondrucker).
 *
 * BEWUSST NOCH NICHT IMPLEMENTIERT (Projektvorgabe: TSE-Anbindung nachgelagert, kein Drucker verfügbar).
 *
 * Geplanter Weg, nach Hardwareprüfung zu bestätigen:
 *  - Zugriff über das Epson ePOS SDK for Android auf das TSE-Gerät des Druckers
 *    (Epson "TSE Integration" – JSON-Kommandos an das Gerät "local_TSE").
 *  - Ablauf je Vorgang: StartTransaction -> FinishTransaction (ggf. UpdateTransaction).
 *  - Einmalig/Administration: GetStorageInfo (nur lesend), RegisterClient (ClientID = Kassen-ID),
 *    UpdateTime, Export (TAR) – jeweils nur nach ausdrücklicher Freigabe.
 *
 * Sicherheitsregel für die Entwicklung: Phase 2a ruft ausschließlich lesende Funktionen auf
 * (Geräte-/Speicherinfo). Schreibende Aufrufe (Setup, Client-Registrierung, Transaktionen)
 * werden erst nach schriftlicher Freigabe des Betreibers und nur gegen eine Test-TSE ausgeführt.
 */
class EpsonTse : Tse {
    override val name = "Epson TSE (nicht implementiert)"
    override val certified = false
    override suspend fun status() = TseStatus(available = false, message = "Epson-TSE-Anbindung folgt in Phase 2")
    override suspend fun startTransaction(clientId: String): TseStart = throw TseException("Epson-TSE noch nicht implementiert")
    override suspend fun finishTransaction(clientId: String, txNumber: Long, processType: String, processData: String): TseFinish =
        throw TseException("Epson-TSE noch nicht implementiert")
}
