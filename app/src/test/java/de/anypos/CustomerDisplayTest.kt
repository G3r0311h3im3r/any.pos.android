package de.anypos

import de.anypos.display.DisplayServer
import de.anypos.domain.CustomerDisplayHub
import de.anypos.domain.DisplayLine
import de.anypos.domain.DisplayState
import de.anypos.domain.Tips
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Kundendisplay: Zustand, Trinkgeldauswahl nur zur offenen Frage, Webserver mit Schlüssel und Server-Sent Events. */
class CustomerDisplayTest {
    private val hub = CustomerDisplayHub()
    private val token = DisplayServer.newToken()
    private val server = DisplayServer(hub, token, 0, { "<html>Display</html>" }, InetAddress.getLoopbackAddress(), heartbeatMs = 200).start()
    private val base = "http://127.0.0.1:${server.port}/d/$token"

    @After fun tearDown() = server.close()

    private fun ask(id: String = "ask-1") = DisplayState.TipAsk("Laden", id, 1235, Tips.options(1235, listOf(5, 10), true))

    private fun request(url: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return code to text
    }

    @Test fun choiceOnlyForOpenQuestionAndOfferedOption() = runBlocking<Unit> {
        assertFalse(hub.choose("ask-1", "p5"))                        // keine Frage offen
        hub.publish(ask())
        assertFalse(hub.choose("alt", "p5"))                          // andere Frage
        assertFalse(hub.choose("ask-1", "p99"))                       // nicht angeboten
        val choice = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { hub.choices.first() } }
        assertTrue(hub.choose("ask-1", "round"))
        assertEquals("round", choice.await().optionId)
        assertEquals("round", (hub.state.value as DisplayState.TipAsk).chosen)
    }

    @Test fun jsonHasFormattedAmounts() {
        val json = CustomerDisplayHub.json(DisplayState.Basket("Laden", listOf(DisplayLine("Essen", "1 ×", null, "12,35 €", null, true)), 1235))
        assertTrue(json.contains("\"mode\":\"cart\"") && json.contains("\"total\":\"12,35 €\"") && json.contains("\"hl\":true"))
        val tip = CustomerDisplayHub.json(ask())
        assertTrue(tip.contains("Aufrunden auf 13,00 €") && tip.contains("\"amount\":\"+ 0,62 €\""))
        val paid = CustomerDisplayHub.json(DisplayState.Paid("Laden", 1300, 65, 2000, 700))
        assertTrue(paid.contains("\"change\":\"7,00 €\"") && paid.contains("\"tip\":\"0,65 €\""))
    }

    @Test fun pageNeedsCorrectKey() {
        assertEquals(200 to "<html>Display</html>", request(base))
        assertEquals(404, request("http://127.0.0.1:${server.port}/d/falsch").first)
        assertEquals(404, request("http://127.0.0.1:${server.port}/").first)
        assertEquals(404, request("$base/irgendwas").first)
    }

    @Test fun eventsDeliverCurrentStateAndChanges() {
        hub.publish(DisplayState.Idle("Laden"))
        val c = URL("$base/events").openConnection() as HttpURLConnection
        c.readTimeout = 3_000
        val reader = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
        fun nextData(): String { while (true) { val l = reader.readLine()!!; if (l.startsWith("data: {")) return l } }
        assertTrue(nextData().contains("\"mode\":\"idle\""))           // sofort der aktuelle Stand
        hub.publish(DisplayState.Basket("Laden", emptyList(), 250))
        assertTrue(nextData().contains("\"mode\":\"cart\""))
        // Lebenszeichen
        var ping = false
        repeat(20) { if (!ping && reader.readLine() == "event: ping") ping = true }
        assertTrue(ping)
        c.disconnect()
    }

    @Test fun tipPostOnlyForOpenQuestion() {
        assertEquals(409, request("$base/tip", "POST", """{"ask":"ask-1","option":"p5"}""").first)
        hub.publish(ask())
        assertEquals(409, request("$base/tip", "POST", """{"ask":"ask-2","option":"p5"}""").first)
        assertEquals(400, request("$base/tip", "POST", "").first.let { if (it == 411) 400 else it })
        assertEquals(204, request("$base/tip", "POST", """{"ask":"ask-1","option":"p10"}""").first)
        assertEquals("p10", (hub.state.value as DisplayState.TipAsk).chosen)
        assertEquals(404, request("http://127.0.0.1:${server.port}/d/falsch/tip", "POST", """{"ask":"ask-1","option":"p5"}""").first)
    }
}
