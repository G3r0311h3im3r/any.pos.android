package de.anypos.tsemock

/** Ausgangslage der Mock-TSE beim Start. */
enum class Scenario {
    /** Fabrikneu: nicht initialisiert, keine Clients, keine Zeit. */
    FRESH,

    /**
     * Von JTL-POS eingerichtet und genutzt: initialisiert, Client `JTL-POS-1` registriert, eigener Shared Secret möglich.
     * Nach dem Einschalten: Self-Test fällig, keine gültige Zeit. Die Kasse ist noch nicht registriert.
     */
    JTL_TAKEOVER,

    /** Betriebsbereit für die Kasse: Clients `JTL-POS-1` und `KASSE-01`, Self-Test bestanden, Zeit gesetzt. */
    OPERATIONAL,
}

/**
 * Einstellungen der Mock-TSE. PINs und Secret sind reine Testwerte des Mocks, keine Werte einer echten TSE.
 * Zeiten in Millisekunden; [clock] ist austauschbar, damit Tests 8-h- und 25-h-Fristen ohne Warten prüfen können.
 */
data class MockConfig(
    val scenario: Scenario = Scenario.OPERATIONAL,
    val sharedSecret: String = DEFAULT_SECRET,
    val adminPin: String = "11111",
    val timeAdminPin: String = "22222",
    val puk: String = "333333",
    /** Künstliche Dauer jeder Signatur (Start/Update/Finish). */
    val signatureLatencyMs: Long = 0,
    /** Automatische Abmeldung des TimeAdmin nach dieser Zeit ohne erneute Anmeldung. */
    val timeAdminTimeoutMs: Long = 8 * HOUR,
    /** Danach verlangt die TSE einen neuen Self-Test. */
    val selfTestIntervalMs: Long = 25 * HOUR,
    /** Danach gilt die gesetzte Zeit als veraltet (`hasValidTime=false`); null = nie. */
    val timeValidityMs: Long? = null,
    val maxStartedTransactions: Int = 512,
    val maxRegisteredClients: Int = 100,
    val maxPinAttempts: Int = 3,
    /** Größe der Blöcke, die `GetExportData` liefert (Bytes vor Base64). */
    val exportChunkSize: Int = 4096,
    /** So lange bleibt die TSE nach einem Verbindungsabbruch für andere Clients gesperrt. */
    val reconnectWindowMs: Long = 60_000,
    /** TSE war zuletzt an einem anderen Host: `RunTSESelfTest` liefert `TSE1_ERROR_CLIENT_NOT_REGISTERED`. */
    val boundToOtherHost: Boolean = false,
    val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DEFAULT_SECRET = "EPSONKEY"
        const val ADMIN_USER = "Administrator"
        const val JTL_CLIENT = "JTL-POS-1"
        const val POS_CLIENT = "KASSE-01"
        const val HOUR = 3_600_000L
    }
}
