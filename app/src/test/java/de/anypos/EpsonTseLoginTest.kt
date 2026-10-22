package de.anypos

import de.anypos.tse.TlsPinning
import de.anypos.tse.TseLoginException
import de.anypos.tse.TseResultUnknownException
import de.anypos.tse.TseWriteBlockedException
import de.anypos.tsemock.Fault
import de.anypos.tsemock.MockConfig
import de.anypos.tsemock.Scenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Schritt 5: Self-Test, TimeAdmin-Anmeldung, Zeit und Wiederherstellung gegen die Mock-TSE. */
class EpsonTseLoginTest {

    private val fixtures = mutableListOf<MockTseFixture>()
    @After fun tearDown() = fixtures.forEach { it.close() }

    private fun fixture(scenario: Scenario = Scenario.OPERATIONAL, config: MockConfig = MockConfig(), tls: Boolean = false) =
        MockTseFixture(scenario, config, tls).also { it.writeEnabled = true; fixtures += it }

    private inline fun <reified E : Throwable> expect(block: () -> Unit): E {
        try { block() } catch (e: Throwable) { if (e is E) return e; throw e }
        fail("${E::class.simpleName} erwartet"); throw IllegalStateException()
    }

    @Test fun operationalNeedsOnlyLogin() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        epson.ensureReady()
        assertEquals(listOf("GetStorageInfo", "GetChallenge", "AuthenticateUserForTimeAdmin"), f.mock.calls)
        // Zweiter Aufruf: schon angemeldet, Zeit gültig – nur Statusabfrage
        epson.ensureReady()
        assertEquals(1, f.count("AuthenticateUserForTimeAdmin"))
        assertEquals(0, f.count("UpdateTime"))
    }

    @Test fun afterPowerCycleSelfTestLoginAndTime() = runBlocking<Unit> {
        val f = fixture()
        f.mock.powerCycle()
        val epson = f.epson()
        epson.ensureReady()
        assertEquals(listOf("GetStorageInfo", "RunTSESelfTest", "GetChallenge", "AuthenticateUserForTimeAdmin", "UpdateTime"), f.mock.calls)
        val info = epson.storageInfo()
        assertTrue(info.hasPassedSelfTest && info.hasValidTime)
        assertTrue(f.mock.calls.none { it in setOf("SetUp", "SetUpForPrinter", "RegisterClient", "RegisterSecretKey") })
    }

    @Test fun timeAdminTimeoutReLoginOnce() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        epson.ensureReady()
        f.advance(8 * MockConfig.HOUR + 1)
        epson.syncTime()
        // UpdateTime abgelehnt (Code) → neu angemeldet → einmal wiederholt
        assertEquals(2, f.count("AuthenticateUserForTimeAdmin"))
        assertEquals(2, f.count("UpdateTime"))
    }

    @Test fun selfTestCodeTriggersFullRecovery() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        epson.ensureReady()
        f.mock.inject("TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED", "UpdateTime")
        epson.syncTime()
        assertEquals(
            listOf("UpdateTime", "RunTSESelfTest", "GetChallenge", "AuthenticateUserForTimeAdmin", "UpdateTime", "UpdateTime"),
            f.mock.calls.drop(3),
        )
    }

    @Test fun recoveryOnlyOnce() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        epson.ensureReady()
        f.mock.inject("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", "UpdateTime")
        f.mock.inject("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", "UpdateTime")
        val e = expect<de.anypos.tse.EpsonException> { epson.syncTime() }
        assertEquals("OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER", e.code)
        assertEquals(2, f.count("UpdateTime"))
    }

    @Test fun wrongPinOnlyOneAttemptThenStops() = runBlocking<Unit> {
        val f = fixture()
        f.pin = "00000"
        val epson = f.epson()
        val e = expect<TseLoginException> { epson.ensureReady() }
        assertTrue(e.message!!, e.message!!.contains("PIN falsch"))
        // Weitere Versuche mit denselben Daten erreichen die TSE nicht – die PIN wird nicht gesperrt
        repeat(5) { expect<TseLoginException> { epson.ensureReady() } }
        assertEquals(1, f.count("AuthenticateUserForTimeAdmin"))
        // Nach Korrektur klappt es, die PIN ist nicht gesperrt
        f.pin = "22222"
        epson.ensureReady()
        assertEquals(2, f.count("AuthenticateUserForTimeAdmin"))
    }

    @Test fun wrongSecretStops() = runBlocking<Unit> {
        val f = fixture(Scenario.OPERATIONAL, MockConfig(sharedSecret = "JTLSECRET"))
        val epson = f.epson()
        val e = expect<TseLoginException> { epson.ensureReady() }
        assertTrue(e.message!!, e.message!!.contains("Shared Secret"))
        expect<TseLoginException> { epson.ensureReady() }
        assertEquals(1, f.count("AuthenticateUserForTimeAdmin"))
        f.secret = "JTLSECRET"
        epson.ensureReady()
    }

    @Test fun clientNotRegisteredNoAutomaticSetup() = runBlocking<Unit> {
        val f = fixture(Scenario.JTL_TAKEOVER)
        val e = expect<TseLoginException> { f.epson().ensureReady() }
        assertTrue(e.message!!, e.message!!.contains("nicht registriert"))
        assertTrue(f.mock.calls.none { it in setOf("SetUpForPrinter", "RegisterClient", "AuthenticateUserForAdmin") })
    }

    @Test fun boundToOtherHostNoSetUpForPrinter() = runBlocking<Unit> {
        val f = fixture(Scenario.JTL_TAKEOVER, MockConfig(boundToOtherHost = true))
        val e = expect<TseLoginException> { f.epson().ensureReady() }
        assertTrue(e.message!!, e.message!!.contains("anderen Drucker"))
        assertEquals(0, f.count("SetUpForPrinter"))
    }

    @Test fun missingPinAndUninitialized() = runBlocking<Unit> {
        val f = fixture()
        f.pin = null
        assertTrue(expect<TseLoginException> { f.epson().ensureReady() }.message!!.contains("nicht eingestellt"))
        assertEquals(0, f.count("AuthenticateUserForTimeAdmin"))

        val fresh = fixture(Scenario.FRESH)
        assertTrue(expect<TseLoginException> { fresh.epson().ensureReady() }.message!!.contains("nicht initialisiert"))
        assertEquals(listOf("GetStorageInfo"), fresh.mock.calls)
    }

    @Test fun unknownResultIsNotRetried() = runBlocking<Unit> {
        val f = fixture()
        val epson = f.epson()
        epson.ensureReady()
        f.server.injectFault(Fault.DROP_AFTER_EXECUTE, "UpdateTime")
        expect<TseResultUnknownException> { epson.syncTime() }
        assertEquals(1, f.count("UpdateTime"))
    }

    @Test fun writeProtectionBlocksLogin() = runBlocking<Unit> {
        val f = fixture()
        f.writeEnabled = false
        expect<TseWriteBlockedException> { f.epson().ensureReady() }
        assertEquals(listOf("AuthenticateUserForTimeAdmin"), f.blocked)
        assertTrue(f.mock.writeCalls.isEmpty())
    }

    @Test fun pinNeverInProtocol() = runBlocking<Unit> {
        val f = fixture()
        f.epson().ensureReady()
        assertTrue(f.log.isNotEmpty())
        assertTrue(f.log.toString(), f.log.none { it.contains("22222") || it.contains("hash") || it.contains("EPSONKEY") })
    }

    @Test fun tlsWithConfirmedCertificate() = runBlocking<Unit> {
        val f = fixture(tls = true)

        // Noch nicht bestätigt: keine Verbindung, nichts kommt beim Mock an
        val unconfirmed = f.epson().status()
        assertFalse(unconfirmed.available)
        assertTrue(unconfirmed.message, unconfirmed.message.contains("nicht bestätigt"))

        // Falscher Fingerabdruck: abgelehnt
        f.fingerprint = "AA:BB:CC"
        val wrong = f.epson().status()
        assertFalse(wrong.available)
        assertTrue(wrong.message, wrong.message.contains("geändert"))
        assertTrue(f.mock.calls.isEmpty())

        // Zertifikat holen (nur Handshake), bestätigen, dann läuft alles über TLS
        val cert = TlsPinning.fetchCertificate("127.0.0.1", f.server.port)
        assertTrue(cert.subjectX500Principal.name.contains("MOCK TSE"))
        f.fingerprint = TlsPinning.fingerprint(cert)
        val epson = f.epson()
        epson.ensureReady()
        assertFalse(epson.status().message.contains("UNVERSCHLÜSSELT"))
    }
}
