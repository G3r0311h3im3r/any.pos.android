package de.anypos

import de.anypos.tse.ClientIdStore
import de.anypos.tse.EposXmlSocketTransport
import de.anypos.tse.EpsonTse
import de.anypos.tse.MemoryClientIdStore
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

    /** Gespeicherte client_id (wie SharedPreferences in der App), gemeinsam für alle hier gebauten Transporte. */
    val clientIds = MemoryClientIdStore()

    /** Transport ohne Schreibschutz – nur für Protokolltests. Kurze Fristen, damit Tests mit Sperren und Nachlieferung schnell laufen. */
    fun rawTransport(connectTimeoutMs: Int = 1_000, openAttempts: Int = 2, store: ClientIdStore = clientIds) = EposXmlSocketTransport(
        "127.0.0.1", server.port,
        socketFactory = if (tls) TlsPinning.pinnedSocketFactory(fingerprint) else SocketFactory.getDefault(),
        connectTimeoutMs = connectTimeoutMs, handshakeTimeoutMs = 2_000, openTimeoutMs = 2_000,
        openAttempts = openAttempts, openRetryDelayMs = 50, redeliveryWaitMs = 500,
        clientIdStore = store, clock = clock, log = { log += it },
    )

    fun guardedTransport(store: ClientIdStore = clientIds) =
        ReadOnlyGuardTransport(rawTransport(store = store), writeEnabled = { writeEnabled }, onBlocked = { blocked += it })

    /** [store] = eigene Ablage simuliert eine andere Anwendung am selben Drucker (z. B. JTL-POS). */
    fun epson(clientId: String = "KASSE-01", commandTimeoutMs: Int = 10_000, store: ClientIdStore = clientIds) = EpsonTse(
        guardedTransport(store), clientId = { clientId },
        credentials = { pin?.let { TseCredentials(it, secret) } },
        clock = clock, commandTimeoutMs = commandTimeoutMs, encrypted = tls,
    )

    /** Aufrufe einer Funktion beim Mock. */
    fun count(function: String) = mock.calls.count { it == function }

    override fun close() = server.close()
}
