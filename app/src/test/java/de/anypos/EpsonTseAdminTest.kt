package de.anypos

import de.anypos.tse.EpsonTse
import de.anypos.tse.TseException
import de.anypos.tse.TseLoginException
import de.anypos.tse.TseWriteBlockedException
import de.anypos.tsemock.MockConfig
import de.anypos.tsemock.Scenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Schritt 7: Admin-Ablauf „TSE übernehmen“ gegen die Mock-TSE. */
class EpsonTseAdminTest {

    private val fixtures = mutableListOf<MockTseFixture>()
    @After fun tearDown() = fixtures.forEach { it.close() }

    /** TSE, die JTL-POS eingerichtet hat (eigenes Shared Secret, Client JTL-POS-1, Self-Test fällig). */
    private fun jtlTse(config: MockConfig = MockConfig(sharedSecret = "JTLSECRET")) =
        MockTseFixture(Scenario.JTL_TAKEOVER, config).also {
            it.writeEnabled = true
            it.secret = "JTLSECRET"
            fixtures += it
        }

    private val adminPin = "11111"

    @Test fun takeoverRegistersThisRegister() = runBlocking<Unit> {
        val f = jtlTse()
        val epson = f.epson()
        assertEquals(listOf("JTL-POS-1"), epson.asAdmin(adminPin) { registeredClients() })
        assertTrue(epson.asAdmin(adminPin) { registerClient("KASSE-01") })
        assertFalse(epson.asAdmin(adminPin) { registerClient("KASSE-01") }) // schon registriert → nichts gesendet
        assertEquals(1, f.count("RegisterClient"))
        assertEquals(3, f.count("LogOutForAdmin"))                         // jede Sitzung abgemeldet
        assertEquals(listOf("JTL-POS-1", "KASSE-01"), epson.asAdmin(adminPin) { registeredClients() })

        // Danach arbeitet die Kasse mit der TSE (Self-Test, Anmeldung, Zeit automatisch)
        val start = epson.startTransaction("KASSE-01")
        epson.finishTransaction("KASSE-01", start.txNumber, "Kassenbeleg-V1", "Beleg^2.50_0.00_0.00_0.00_0.00^2.50:Bar")
        assertTrue(f.mock.calls.none { it == "SetUp" || it == "RegisterSecretKey" || it == "DeregisterClient" })
    }

    @Test fun wrongAdminPinOnlyOnce() = runBlocking<Unit> {
        val f = jtlTse()
        val epson = f.epson()
        try { epson.asAdmin("00000") { registeredClients() }; fail() } catch (e: TseLoginException) {
            assertTrue(e.message!!, e.message!!.contains("noch 2 Versuche"))
        }
        // Dieselbe falsche PIN wird nicht noch einmal gesendet
        try { epson.asAdmin("00000") { registeredClients() }; fail() } catch (_: TseLoginException) {}
        assertEquals(1, f.count("AuthenticateUserForAdmin"))
        // Richtige PIN funktioniert, die PIN ist nicht gesperrt
        assertEquals(listOf("JTL-POS-1"), epson.asAdmin(adminPin) { registeredClients() })
    }

    @Test fun wrongSecretIsReported() = runBlocking<Unit> {
        val f = jtlTse()
        f.secret = "" // Standard-Secret EPSONKEY, JTL hat es aber geändert
        try { f.epson().asAdmin(adminPin) { registeredClients() }; fail() } catch (e: TseLoginException) {
            assertTrue(e.message!!, e.message!!.contains("Shared Secret"))
        }
    }

    @Test fun logoutEvenIfActionFails() = runBlocking<Unit> {
        val f = jtlTse()
        try { f.epson().asAdmin(adminPin) { error("Abbruch") }; fail() } catch (e: IllegalStateException) {}
        assertEquals(1, f.count("LogOutForAdmin"))
    }

    @Test fun invalidClientIdRejectedBeforeSending() = runBlocking<Unit> {
        val f = jtlTse()
        for (id in listOf("KASSE_01", "EPSON-1", "", "x".repeat(31))) {
            try { f.epson().asAdmin(adminPin) { registerClient(id) }; fail(id) } catch (e: TseException) {
                assertTrue(e.message!!, e.message!!.contains("nicht zulässig"))
            }
        }
        assertEquals(0, f.count("RegisterClient"))
        EpsonTse.validateClientId("KASSE-01")
    }

    @Test fun tseFromOtherPrinterIsBound() = runBlocking<Unit> {
        val f = jtlTse(MockConfig(sharedSecret = "JTLSECRET", boundToOtherHost = true))
        val epson = f.epson()
        assertFalse(epson.asAdmin(adminPin) { selfTestOnThisHost() })
        assertTrue(epson.asAdmin(adminPin) { setUpForPrinter(); selfTestOnThisHost() })
        assertEquals(1, f.count("SetUpForPrinter"))
    }

    @Test fun writeProtectionBlocksAdminLogin() = runBlocking<Unit> {
        val f = jtlTse()
        f.writeEnabled = false
        try { f.epson().asAdmin(adminPin) { registeredClients() }; fail() } catch (_: TseWriteBlockedException) {}
        assertTrue(f.mock.writeCalls.isEmpty())
        assertEquals(listOf("AuthenticateUserForAdmin"), f.blocked)
    }

    @Test fun adminPinNeverInProtocol() = runBlocking<Unit> {
        val f = jtlTse()
        f.epson().asAdmin(adminPin) { registeredClients() }
        assertTrue(f.log.isNotEmpty())
        assertTrue(f.log.toString(), f.log.none { it.contains(adminPin) || it.contains("JTLSECRET") })
    }
}
