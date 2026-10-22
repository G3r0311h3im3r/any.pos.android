package de.anypos

import de.anypos.display.DisplayServer
import de.anypos.domain.CustomerDisplayHub
import de.anypos.payment.CardPaymentRequest
import de.anypos.payment.CardPaymentResult
import de.anypos.payment.SumUpSwitch
import de.anypos.payment.SumUpSwitchBridge
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** SumUp-App per Payment Switch: Startlink, Rückruf mit Einmal-Code, Auswertung, Display-Server-Route. */
class SumUpSwitchTest {
    private val request = CardPaymentRequest(7, "KASSE-01-14-1", 1490, 0, "Beleg 14")

    @Test fun startUriHasAllParametersEncoded() {
        val uri = SumUpSwitch.startUri("sup_afk_X", 1490, "Beleg 14", "KASSE-01-14-1", "http://192.168.1.5:8090/d/T/sumup/N")
        assertTrue(uri.startsWith("sumupmerchant://pay/1.0?"))
        val p = SumUpSwitch.params(uri)
        assertEquals("sup_afk_X", p["affiliate-key"])
        assertEquals("de.anypos", p["app-id"])
        assertEquals("14.90", p["total"])
        assertEquals("EUR", p["currency"])
        assertEquals("Beleg 14", p["title"])
        assertEquals("KASSE-01-14-1", p["foreign-tx-id"])
        assertEquals("true", p["skip-screen-success"])
        assertEquals("http://192.168.1.5:8090/d/T/sumup/N", p["callback"])
        assertFalse(uri.contains(" "))
    }

    @Test fun mapCallback() {
        fun m(vararg kv: Pair<String, String>) = SumUpSwitch.map(kv.toMap(), "KASSE-01-14-1")
        assertEquals(CardPaymentResult.Approved("TX9"), m("smp-status" to "success", "smp-tx-code" to "TX9", "foreign-tx-id" to "KASSE-01-14-1"))
        assertTrue(m("smp-status" to "success") is CardPaymentResult.Unknown)                       // ohne Transaktionscode
        assertTrue(m("smp-status" to "success", "smp-tx-code" to "TX9", "foreign-tx-id" to "ANDERS") is CardPaymentResult.Unknown)
        assertTrue(m("smp-status" to "failed", "smp-failure-cause" to "Abgelehnt") is CardPaymentResult.Declined)
        assertTrue(m() is CardPaymentResult.Unknown)                                                  // kein Status
    }

    @Test fun paramsTolerateQuestionMarkAppendedTwice() {
        val p = SumUpSwitch.params("anypos://sumup-callback/N?smp-status=success?smp-tx-code=TX%201&foreign-tx-id=R")
        assertEquals("success", p["smp-status"])
        assertEquals("TX 1", p["smp-tx-code"])
        assertEquals("R", p["foreign-tx-id"])
    }

    @Test fun bridgeAcceptsOnlyMatchingNonceOnce() = runBlocking<Unit> {
        val bridge = SumUpSwitchBridge()
        bridge.begin(request, "N1", "sumupmerchant://…", onDisplay = false)
        val result = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { bridge.results.first() } }
        assertFalse(bridge.deliver("FALSCH", mapOf("smp-status" to "success", "smp-tx-code" to "TX")))
        assertTrue(bridge.deliver("N1", mapOf("smp-status" to "success", "smp-tx-code" to "TX", "foreign-tx-id" to "KASSE-01-14-1")))
        assertEquals(7L to CardPaymentResult.Approved("TX"), result.await())
        assertFalse(bridge.deliver("N1", mapOf("smp-status" to "success", "smp-tx-code" to "TX")))   // nur einmal
        assertNull(bridge.pending.value)
    }

    @Test fun abandonDependsOnWhetherSumUpWasOpened() = runBlocking<Unit> {
        val bridge = SumUpSwitchBridge()
        suspend fun abandoned(onDisplay: Boolean, opened: Boolean): CardPaymentResult {
            bridge.begin(request, "N", "x", onDisplay)
            if (opened) bridge.markOpened("N")
            val r = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { bridge.results.first() } }
            bridge.abandon(7, "Kassierer")
            return r.await().second
        }
        assertEquals(CardPaymentResult.Cancelled, abandoned(onDisplay = true, opened = false))       // nie geöffnet: sicher nichts abgebucht
        assertTrue(abandoned(onDisplay = true, opened = true) is CardPaymentResult.Unknown)
        assertTrue(abandoned(onDisplay = false, opened = false) is CardPaymentResult.Unknown)        // Tablet: App wurde gestartet
    }

    @Test fun displayServerRoutesCallbackAndOpenedMark() = runBlocking<Unit> {
        val bridge = SumUpSwitchBridge()
        val token = DisplayServer.newToken()
        val server = DisplayServer(CustomerDisplayHub(), token, 0, { "<html/>" }, InetAddress.getLoopbackAddress(), switch = bridge).start()
        try {
            val base = "http://127.0.0.1:${server.port}/d/$token"
            fun call(url: String, method: String = "GET"): Pair<Int, String> {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = method
                if (method == "POST") { c.doOutput = true; c.outputStream.close() }
                val code = c.responseCode
                return code to ((if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
            }
            bridge.begin(request, "NONCE", "x", onDisplay = true)
            assertEquals(204, call("$base/sumup-open/NONCE", "POST").first)
            assertTrue(bridge.pending.value!!.opened)
            assertEquals(409, call("$base/sumup-open/FALSCH", "POST").first)

            val result = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { bridge.results.first() } }
            val (code, body) = call("$base/sumup/NONCE?smp-status=success&smp-tx-code=TX5&foreign-tx-id=KASSE-01-14-1")
            assertEquals(200, code)
            assertTrue(body.contains("Zahlung übermittelt") && body.contains("/d/$token"))
            assertEquals(CardPaymentResult.Approved("TX5"), result.await().second)
            assertTrue(call("$base/sumup/NONCE?smp-status=success&smp-tx-code=TX5").second.contains("bereits verarbeitet"))
            assertEquals(404, call("http://127.0.0.1:${server.port}/d/falsch/sumup/NONCE?smp-status=success").first)
        } finally {
            server.close()
        }
    }
}
