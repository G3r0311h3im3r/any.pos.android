package de.anypos

import de.anypos.tse.EposXmlSocketTransport
import de.anypos.tse.EpsonTse
import de.anypos.tse.ReadOnlyGuardTransport
import de.anypos.tse.TlsPinning
import de.anypos.tse.TseCredentials
import de.anypos.tsemock.MockConfig
import de.anypos.tsemock.MockTse
import de.anypos.tsemock.MockTseServer
import de.anypos.tsemock.Scenario
import java.io.Closeable
import java.util.Collections
import javax.net.SocketFactory

/**
 * Gemeinsamer Testaufbau: Mock-TSE + Server + Transport (+ Schreibschutz) + EpsonTse.
 * Mock und Transport teilen eine Uhr, die mit [advance] vorgestellt wird (Sperrfenster, 8-h-/25-h-Fristen).
 * Mit [tls] spricht der Server TLS mit dem Testzertifikat des Mocks; der Client prüft [fingerprint].
 */
class MockTseFixture(
    scenario: Scenario = Scenario.OPERATIONAL,
    config: MockConfig = MockConfig(),
    val tls: Boolean = false,
) : Closeable {
    private var offset = 0L
    val clock: () -> Long = { System.currentTimeMillis() + offset }

    val mock = MockTse(config.copy(scenario = scenario, clock = clock))
    val server: MockTseServer = MockTseServer(mock, tls = if (tls) MockTseServer.tlsContext() else null).start()

    /** Schalter des Schreibschutzes für alle hier gebauten Transporte. */
    var writeEnabled = false

    /** Zugangsdaten der Kasse; Standard = Testwerte des Mocks. null = keine PIN eingestellt. */
    var pin: String? = config.timeAdminPin
    var secret: String = ""

    /** Bestätigter Zertifikats-Fingerabdruck (nur mit [tls]). */
    var fingerprint: String = ""

    /** Vom Schreibschutz abgewiesene Funktionen. */
    val blocked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Protokollzeilen der Transporte (nur Funktion/Dauer/Ergebnis). */
    val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun advance(ms: Long) { offset += ms }

    /** Transport ohne Schreibschutz – nur für Protokolltests. */
    fun rawTransport(connectTimeoutMs: Int = 1_000) = EposXmlSocketTransport(
        "127.0.0.1", server.port,
        socketFactory = if (tls) TlsPinning.pinnedSocketFactory(fingerprint) else SocketFactory.getDefault(),
        connectTimeoutMs = connectTimeoutMs, handshakeTimeoutMs = 2_000,
        reconnectDrainMs = 100, clock = clock, log = { log += it },
    )

    fun guardedTransport() = ReadOnlyGuardTransport(rawTransport(), writeEnabled = { writeEnabled }, onBlocked = { blocked += it })

    fun epson(clientId: String = "KASSE-01", commandTimeoutMs: Int = 10_000) = EpsonTse(
        guardedTransport(), clientId = { clientId },
        credentials = { pin?.let { TseCredentials(it, secret) } },
        clock = clock, commandTimeoutMs = commandTimeoutMs, encrypted = tls,
    )

    /** Aufrufe einer Funktion beim Mock. */
    fun count(function: String) = mock.calls.count { it == function }

    override fun close() = server.close()
}
