package de.anypos

import de.anypos.tse.EposXmlSocketTransport
import de.anypos.tse.TseResultUnknownException
import de.anypos.tse.TseTransportException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Protokolltests gegen einen skriptgesteuerten Fake-Drucker (kein echtes Gerät, keine TSE). */
class EposXmlSocketTransportTest {

    /** Eine Verbindung aus Sicht des Fake-Druckers. */
    class Conn(private val socket: Socket) {
        private val inp: InputStream = socket.getInputStream()
        private val out: OutputStream = socket.getOutputStream()

        fun read(): String {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = inp.read()
                require(b != -1) { "Client hat Verbindung geschlossen" }
                if (b == 0) return buf.toString(Charsets.UTF_8.name())
                buf.write(b)
            }
        }

        fun send(xml: String) { out.write(xml.toByteArray(Charsets.UTF_8)); out.write(0); out.flush() }

        /** Antwort in kleinen Stücken senden (TCP-Fragmentierung). */
        fun sendFragmented(xml: String) {
            val bytes = xml.toByteArray(Charsets.UTF_8) + 0
            bytes.toList().chunked(7).forEach { out.write(it.toByteArray()); out.flush(); Thread.sleep(2) }
        }

        fun close() = socket.close()

        fun hello(id: String) = send("<connect><data><client_id>$id</client_id><protocol_version>2</protocol_version></data></connect>")

        fun acceptOpen(dataId: String = "1") {
            val msg = read()
            require(msg.startsWith("<open_device>")) { msg }
            send("<open_device><device_id>local_TSE</device_id><code>OK</code><data_id>$dataId</data_id></open_device>")
        }
    }

    /** Fake-Drucker: je eingehender Verbindung wird das nächste Skript ausgeführt. */
    class FakePrinter(vararg scripts: (Conn) -> Unit) {
        val server = ServerSocket(0)
        val port get() = server.localPort
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val done = CountDownLatch(scripts.size)
        private val thread = Thread {
            for (script in scripts) {
                try {
                    val conn = Conn(server.accept())
                    script(conn)
                } catch (e: Throwable) {
                    if (!server.isClosed) failures += e
                } finally {
                    done.countDown()
                }
            }
        }.apply { isDaemon = true; start() }

        fun stop() = server.close()
    }

    /** Vom Fake-Drucker empfangene Nachrichten (für Zählungen und Inhalte). */
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var printer: FakePrinter? = null
    private var time = 1_000_000L

    private fun transport(p: FakePrinter, connectTimeout: Int = 2_000) = EposXmlSocketTransport(
        host = "127.0.0.1", port = p.port, connectTimeoutMs = connectTimeout, handshakeTimeoutMs = 2_000,
        clock = { System.currentTimeMillis() + (time - 1_000_000L) }, log = {},
    )

    private fun request(fn: String, extra: String = "") =
        """{"storage":{"type":"TSE","vendor":"TSE1"},"function":"$fn","input":{$extra},"compress":{"required":false,"type":""}}"""

    /** requestdata aus einer device_data-Nachricht wieder lesbar machen (Gegenstück zum Escaping). */
    private fun requestData(msg: String) = Regex("<requestdata>(.*)</requestdata>", RegexOption.DOT_MATCHES_ALL)
        .find(msg)!!.groupValues[1].replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun sequence(msg: String) = Regex("<sequence>(\\d+)</sequence>").find(msg)!!.groupValues[1]

    private fun result(seq: String, json: String) =
        "<device_data><sequence>$seq</sequence><device_id>local_TSE</device_id><data><type>onoperateresult</type>" +
            "<success>true</success><code>SUCCESS</code><resultdata>${EposXmlSocketTransport.escape(json)}</resultdata></data></device_data>"

    @After fun tearDown() { printer?.stop() }

    @Test fun handshakeStoresClientId() = runBlocking {
        val p = FakePrinter({ c -> c.hello("sock123"); Thread.sleep(200) }).also { printer = it }
        val t = transport(p)
        t.connect()
        assertTrue(t.isConnected)
        assertEquals("sock123", t.clientId)
        t.disconnect()
        assertFalse(t.isConnected)
    }

    @Test fun missingHandshakeFailsCleanly() = runBlocking {
        val p = FakePrinter({ Thread.sleep(3_000) }).also { printer = it }
        try { transport(p).connect(); fail() } catch (e: TseTransportException) {
            assertTrue(e.message!!.contains("Handshake"))
        }
    }

    @Test fun openOkAndDeviceInUse() = runBlocking {
        val ok = FakePrinter({ c -> c.hello("s1"); c.acceptOpen("7"); Thread.sleep(200) }).also { printer = it }
        val t = transport(ok)
        t.open()
        assertEquals("7", t.lastDataId)
        ok.stop()

        val busy = FakePrinter({ c ->
            c.hello("s2"); c.read()
            c.send("<open_device><device_id>local_TSE</device_id><code>DEVICE_IN_USE</code></open_device>")
            Thread.sleep(200)
        }).also { printer = it }
        try { transport(busy).open(); fail() } catch (e: TseTransportException) {
            assertTrue(e !is TseResultUnknownException)
            assertTrue(e.message!!.contains("DEVICE_IN_USE"))
        }
    }

    @Test fun operateRoundTripWithSpecialCharacters() = runBlocking {
        val payload = request("GetStorageInfo", """"note":"Müller & Söhne <Kasse> ÄÖÜß €"""")
        val p = FakePrinter({ c ->
            c.hello("s1"); c.acceptOpen()
            val msg = c.read()
            seen += msg
            // Echo: Antwort enthält genau das empfangene JSON
            c.send(result(sequence(msg), requestData(msg)))
            Thread.sleep(200)
        }).also { printer = it }
        val answer = transport(p).operate(payload)
        assertEquals(payload, answer)
        // Auf der Leitung ist das JSON XML-sicher codiert
        assertTrue(seen.single().contains("&amp;") && seen.single().contains("&lt;Kasse&gt;"))
    }

    @Test fun fragmentedAnswerAfterForeignMessages() = runBlocking {
        val p = FakePrinter({ c ->
            c.hello("s1"); c.acceptOpen()
            val msg = c.read()
            // Ereignis und Antwort mit fremder Sequenz vor der eigentlichen Antwort
            c.send("<device_data><device_id>local_TSE</device_id><data><type>onstatus</type></data><data_id>9</data_id></device_data>")
            c.send(result("999", """{"result":"FALSCH"}"""))
            c.sendFragmented(result(sequence(msg), """{"result":"EXECUTION_OK","function":"GetStorageInfo","output":{}}"""))
            Thread.sleep(200)
        }).also { printer = it }
        val t = transport(p)
        val answer = t.operate(request("GetStorageInfo"))
        assertTrue(answer.contains("EXECUTION_OK"))
        assertEquals("9", t.lastDataId)
    }

    @Test fun timeoutAfterSendIsUnknownAndNotRepeated() = runBlocking {
        val p = FakePrinter({ c ->
            c.hello("s1"); c.acceptOpen()
            seen += c.read()
            Thread.sleep(3_000) // keine Antwort
        }).also { printer = it }
        try { transport(p).operate(request("FinishTransaction"), timeoutMs = 100).let { fail() } } catch (e: TseResultUnknownException) {
            assertEquals("FinishTransaction", e.function)
        }
        assertEquals(1, seen.size)
    }

    @Test fun connectionLossAfterSendIsUnknown() = runBlocking {
        val p = FakePrinter({ c -> c.hello("s1"); c.acceptOpen(); c.read(); c.close() }).also { printer = it }
        val t = transport(p)
        try { t.operate(request("StartTransaction")); fail() } catch (e: TseResultUnknownException) {
            assertEquals("StartTransaction", e.function)
        }
        assertFalse(t.isConnected)
    }

    @Test fun reconnectWithinWindowResumesSessionWithoutResending() = runBlocking {
        val p = FakePrinter(
            { c -> c.hello("s1"); c.acceptOpen("5"); seen += c.read(); c.close() },   // Kommando A: Abbruch
            { c ->
                c.hello("s2")
                val reconnect = c.read()
                seen += reconnect
                c.send("<reconnect><code>OK</code></reconnect>")
                val msg = c.read()                                                     // direkt Kommando B, kein open_device
                seen += msg
                c.send(result(sequence(msg), """{"result":"EXECUTION_OK","function":"B"}"""))
                Thread.sleep(200)
            },
        ).also { printer = it }
        val t = transport(p)
        try { t.operate(request("A")); fail() } catch (_: TseResultUnknownException) {}

        assertTrue(t.operate(request("B")).contains("\"B\""))
        val reconnect = seen[1]
        assertTrue(reconnect, reconnect.contains("<old_client_id>s1</old_client_id>"))
        assertTrue(reconnect, reconnect.contains("<new_client_id>s2</new_client_id>"))
        assertTrue(reconnect, reconnect.contains("<received_id>5</received_id>"))
        // A wurde genau einmal gesendet, danach nur B
        assertEquals(1, seen.count { it.contains("\"function\":\"A\"") })
        assertTrue(seen[2].contains("\"function\":\"B\""))
    }

    @Test fun afterWindowExpiredFreshConnectAndOpen() = runBlocking {
        val p = FakePrinter(
            { c -> c.hello("s1"); c.acceptOpen(); c.read(); c.close() },
            { c ->
                c.hello("s2")
                val first = c.read()
                seen += first
                c.send("<open_device><device_id>local_TSE</device_id><code>OK</code><data_id>1</data_id></open_device>")
                val msg = c.read()
                c.send(result(sequence(msg), """{"result":"EXECUTION_OK"}"""))
                Thread.sleep(200)
            },
        ).also { printer = it }
        val t = transport(p)
        try { t.operate(request("A")); fail() } catch (_: TseResultUnknownException) {}
        time += 61_000 // Sperrfenster von 60 s abgelaufen
        t.operate(request("B"))
        assertTrue(seen.single().startsWith("<open_device>")) // kein <reconnect>
        p.done.await(2, TimeUnit.SECONDS)
        assertTrue(p.failures.toString(), p.failures.isEmpty())
    }

    @Test fun unreachableHostIsNotExecuted() = runBlocking {
        val p = FakePrinter().also { printer = it }
        val port = p.port
        p.stop()
        val t = EposXmlSocketTransport("127.0.0.1", port, connectTimeoutMs = 500, log = {})
        try { t.operate(request("GetStorageInfo")); fail() } catch (e: TseTransportException) {
            assertTrue(e !is TseResultUnknownException)
        }
    }
}
