package de.anypos

import de.anypos.tse.EposXmlSocketTransport
import de.anypos.tse.EpsonTse
import de.anypos.tse.ReadOnlyGuardTransport
import de.anypos.tsemock.MockConfig
import de.anypos.tsemock.MockTse
import de.anypos.tsemock.MockTseServer
import de.anypos.tsemock.Scenario
import java.io.Closeable
import java.util.Collections

/**
 * Gemeinsamer Testaufbau: Mock-TSE + Server + Transport (+ Schreibschutz) + EpsonTse.
 * Mock und Transport teilen eine Uhr, die mit [advance] vorgestellt wird (Sperrfenster, 8-h-/25-h-Fristen).
 */
class MockTseFixture(scenario: Scenario = Scenario.OPERATIONAL, config: MockConfig = MockConfig()) : Closeable {
    private var offset = 0L
    val clock: () -> Long = { System.currentTimeMillis() + offset }

    val mock = MockTse(config.copy(scenario = scenario, clock = clock))
    val server: MockTseServer = MockTseServer(mock).start()

    /** Schalter des Schreibschutzes für alle hier gebauten Transporte. */
    var writeEnabled = false

    /** Vom Schreibschutz abgewiesene Funktionen. */
    val blocked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Protokollzeilen der Transporte (nur Funktion/Dauer/Ergebnis). */
    val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun advance(ms: Long) { offset += ms }

    /** Transport ohne Schreibschutz – nur für Protokolltests. */
    fun rawTransport(connectTimeoutMs: Int = 1_000) = EposXmlSocketTransport(
        "127.0.0.1", server.port, connectTimeoutMs = connectTimeoutMs, handshakeTimeoutMs = 1_000,
        reconnectDrainMs = 100, clock = clock, log = { log += it },
    )

    fun guardedTransport() = ReadOnlyGuardTransport(rawTransport(), writeEnabled = { writeEnabled }, onBlocked = { blocked += it })

    fun epson(clientId: String = "KASSE-01", commandTimeoutMs: Int = 10_000) =
        EpsonTse(guardedTransport(), clientId = { clientId }, commandTimeoutMs = commandTimeoutMs)

    override fun close() = server.close()
}
