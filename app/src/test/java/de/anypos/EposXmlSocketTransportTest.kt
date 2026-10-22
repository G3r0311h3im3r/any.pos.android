package de.anypos

import de.anypos.tse.EposXmlSocketTransport
import de.anypos.tse.MemoryClientIdStore
import de.anypos.tse.TseResultUnknownException
import de.anypos.tse.TseTransportException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/**
 * Protokolltests gegen einen skriptgesteuerten Fake-Drucker (kein echtes Gerät, keine TSE).
 * Nachrichtenformate nach ePOS-Device XML User's Manual Rev. AC.
 */
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

        /** Erwartet <disconnect> und bestätigt; liefert die gemeldete client_id. */
        fun acceptDisconnect(): String {
            val msg = read()
            require(msg.startsWith("<disconnect>")) { msg }
            send("<disconnect><code>OK</code></disconnect>")
            return Regex("<client_id>(.*?)</client_id>").find(msg)!!.groupValues[1]
        }
    }

    /** Fake-Drucker: je eingehender Verbindung wird das nächste Skript ausgeführt. */
    class FakePrinter(vararg scripts: (Conn) -> Unit) {
        val server = ServerSocket(0)
        val port get() = server.localPort
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

        fun assertScriptsOk() {
            done.await(3, TimeUnit.SECONDS)
            assertTrue(failures.toString(), failures.isEmpty())
        }
    }

    /** Vom Fake-Drucker empfangene Nachrichten (für Zählungen und Inhalte). */
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var printer: FakePrinter? = null
    private val store = MemoryClientIdStore()

    private fun transport(p: FakePrinter, openAttempts: Int = 1) = EposXmlSocketTransport(
        host = "127.0.0.1", port = p.port, connectTimeoutMs = 2_000, handshakeTimeoutMs = 2_000, openTimeoutMs = 2_000,
        openAttempts = openAttempts, openRetryDelayMs = 20, redeliveryWaitMs = 500, clientIdStore = store, log = {},
    )

    private fun request(fn: String, extra: String = "") =
        """{"storage":{"type":"TSE","vendor":"TSE1"},"function":"$fn","input":{$extra},"compress":{"required":false,"type":""}}"""

    /** requestdata aus einer device_data-Nachricht wieder lesbar machen (Gegenstück zum Escaping). */
    private fun requestData(msg: String) = Regex("<requestdata>(.*)</requestdata>", RegexOption.DOT_MATCHES_ALL)
        .find(msg)!!.groupValues[1].replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun result(json: String, type: String = "operateresult") =
        "<device_data><sequence>0</sequence><device_id>local_TSE</device_id><data><type>$type</type>" +
            "<success>true</success><code>SUCCESS</code><resultdata>${EposXmlSocketTransport.escape(json)}</resultdata></data>" +
            "<data_id>9</data_id></device_data>"

    private fun ok(fn: String) = """{"result":"EXECUTION_OK","function":"$fn","output":{}}"""

    @After fun tearDown() { printer?.stop() }

    @Test fun handshakeStoresClientIdAndDisconnectEndsSession() = runBlocking<Unit> {
        val p = FakePrinter({ c -> c.hello("sock123"); seen += c.acceptDisconnect() }).also { printer = it }
        val t = transport(p)
        t.connect()
        assertTrue(t.isConnected)
        assertEquals("sock123", t.clientId)
        assertEquals("sock123", store.load())
        t.disconnect()
        assertFalse(t.isConnected)
        assertEquals(listOf("sock123"), seen)          // eigene Sitzung per <disconnect> beendet
        assertNull(store.load())
        p.assertScriptsOk()
    }

    @Test fun missingHandshakeFailsCleanly() = runBlocking {
        val p = FakePrinter({ Thread.sleep(3_000) }).also { printer = it }
        try { transport(p).connect(); fail() } catch (e: TseTransportException) {
            assertTrue(e.message!!.contains("Handshake"))
        }
    }

    @Test fun restartWithStoredClientIdDisconnectsOldSession() = runBlocking<Unit> {
        store.save("old7") // z. B. nach App-Absturz
        val p = FakePrinter({ c ->
            c.hello("s1")
            seen += c.acceptDisconnect()
            c.acceptOpen()
            c.read()
            c.send(result(ok("GetStorageInfo")))
            Thread.sleep(200)
        }).also { printer = it }
        transport(p).operate(request("GetStorageInfo"))
        assertEquals(listOf("old7"), seen)
        assertEquals("s1", store.load())
        p.assertScriptsOk()
    }

    @Test fun openDeviceInUseRetriesThenFails() = runBlocking<Unit> {
        val busy = "<open_device><device_id>local_TSE</device_id><code>DEVICE_IN_USE</code></open_device>"
        val retry = FakePrinter({ c ->
            c.hello("s1"); c.read(); c.send(busy); c.read(); c.send(busy); c.acceptOpen("7"); Thread.sleep(200)
        }).also { printer = it }
        val t = transport(retry, openAttempts = 3)
        t.open()
        assertEquals("7", t.lastDataId)
        retry.stop()
        store.save(null) // eigenständiger zweiter Fall ohne alte Sitzung

        val always = FakePrinter({ c -> c.hello("s2"); repeat(2) { c.read(); c.send(busy) }; Thread.sleep(200) }).also { printer = it }
        try { transport(always, openAttempts = 2).open(); fail() } catch (e: TseTransportException) {
            assertTrue(e !is TseResultUnknownException)
            assertTrue(e.message!!, e.message!!.contains("DEVICE_IN_USE"))
        }
    }

    @Test fun operateRoundTripWithSpecialCharacters() = runBlocking {
        val payload = request("GetStorageInfo", """"note":"Müller & Söhne <Kasse> ÄÖÜß €"""")
        val p = FakePrinter({ c ->
            c.hello("s1"); c.acceptOpen()
            val msg = c.read()
            seen += msg
            // Echo: Antwort enthält genau das empfangene JSON
            c.send(result(requestData(msg)))
            Thread.sleep(200)
        }).also { printer = it }
        val answer = transport(p).operate(payload)
        assertEquals(payload, answer)
        val wire = seen.single()
        assertTrue(wire.contains("&amp;") && wire.contains("&lt;Kasse&gt;") && wire.contains("&quot;"))
        assertFalse(wire.contains("<sequence>"))
        assertTrue(wire.contains("<timeout>60000</timeout>")) // Standard wie Epson-Demo
    }

    @Test fun bothResultTypesAndForeignAnswersSkipped() = runBlocking<Unit> {
        val p = FakePrinter({ c ->
            c.hello("s1"); c.acceptOpen()
            c.read()
            // Ereignis und Antwort auf eine andere Funktion vor der eigentlichen Antwort
            c.send("<device_data><device_id>local_TSE</device_id><data><type>onstatus</type></data><data_id>9</data_id></device_data>")
            c.send(result("""{"result":"FALSCH","function":"StartTransaction"}"""))
            c.sendFragmented(result(ok("GetStorageInfo"), type = "onoperateresult"))
            c.read()
            c.send(result(ok("GetStorageInfo"), type = "operateresult"))
            Thread.sleep(200)
        }).also { printer = it }
        val t = transport(p)
        assertTrue(t.operate(request("GetStorageInfo")).contains("EXECUTION_OK"))
        assertTrue(t.operate(request("GetStorageInfo")).contains("EXECUTION_OK"))
        p.assertScriptsOk()
    }

    @Test fun errorMessageMeansNotExecuted() = runBlocking<Unit> {
        val p = FakePrinter({ c ->
            c.hello("s1"); c.acceptOpen(); c.read()
            c.send("<error><device_id>local_TSE</device_id><code>DEVICE_NOT_OPEN</code><data></data><data_id>3</data_id></error>")
            Thread.sleep(500)
        }).also { printer = it }
        val started = System.currentTimeMillis()
        try { transport(p).operate(request("StartTransaction")); fail() } catch (e: TseTransportException) {
            assertTrue(e !is TseResultUnknownException)
            assertTrue(e.message!!, e.message!!.contains("nicht ausgeführt"))
        }
        assertTrue(System.currentTimeMillis() - started < 2_000) // sofort, nicht erst nach Timeout
    }

    @Test fun deviceErrorCodesClassified() = runBlocking<Unit> {
        val p = FakePrinter({ c ->
            c.hello("s1"); c.acceptOpen()
            c.read()
            c.send("<device_data><device_id>local_TSE</device_id><data><type>operateresult</type><success>false</success>" +
                "<code>ERROR_DEVICE_BUSY</code></data><data_id>4</data_id></device_data>")
            c.read()
            c.send("<error><device_id>local_TSE</device_id><code>SYSTEM_ERROR</code><data></data><data_id>5</data_id></error>")
            Thread.sleep(200)
        }).also { printer = it }
        val t = transport(p)
        try { t.operate(request("UpdateTime")); fail() } catch (e: TseTransportException) { assertTrue(e !is TseResultUnknownException) }
        try { t.operate(request("UpdateTime")); fail() } catch (e: TseResultUnknownException) { assertEquals("UpdateTime", e.function) }
    }

    @Test fun lostConnectionAnswerRedeliveredAfterReconnect() = runBlocking<Unit> {
        val p = FakePrinter(
            { c -> c.hello("s1"); c.acceptOpen("5"); seen += c.read(); c.close() },   // Kommando A: Abbruch
            { c ->
                c.hello("s2")
                val reconnect = c.read()
                seen += reconnect
                c.send("<reconnect><code>OK</code></reconnect>")
                c.send(result(ok("StartTransaction")))                             // Nachlieferung (Handbuch S. 36)
                Thread.sleep(200)
            },
        ).also { printer = it }
        val answer = transport(p).operate(request("StartTransaction"))
        assertTrue(answer.contains("EXECUTION_OK"))
        val reconnect = seen[1]
        assertTrue(reconnect, reconnect.contains("<old_client_id>s1</old_client_id>"))
        assertTrue(reconnect, reconnect.contains("<new_client_id>s2</new_client_id>"))
        assertTrue(reconnect, reconnect.contains("<received_id>5</received_id>"))
        assertEquals(1, seen.count { it.startsWith("<device_data>") }) // nie erneut gesendet
        p.assertScriptsOk()
    }

    @Test fun noRedeliveryIsUnknownAndSessionIsDropped() = runBlocking<Unit> {
        val p = FakePrinter(
            { c -> c.hello("s1"); c.acceptOpen("5"); c.read(); c.close() },
            { c -> c.hello("s2"); c.read(); c.send("<reconnect><code>OK</code></reconnect>"); runCatching { c.read() } }, // keine Nachlieferung
            { c ->
                c.hello("s3")
                seen += c.acceptDisconnect()                                         // alte Sitzung beendet
                c.acceptOpen()
                c.read()
                c.send(result(ok("GetStartedTransactionList")))
                Thread.sleep(200)
            },
        ).also { printer = it }
        val t = transport(p)
        try { t.operate(request("StartTransaction")); fail() } catch (e: TseResultUnknownException) {
            assertEquals("StartTransaction", e.function)
        }
        assertTrue(t.operate(request("GetStartedTransactionList")).contains("EXECUTION_OK"))
        assertEquals(listOf("s2"), seen)
        p.assertScriptsOk()
    }

    @Test fun reconnectRejectedEndsOldSessionAndIsUnknown() = runBlocking<Unit> {
        val p = FakePrinter(
            { c -> c.hello("s1"); c.acceptOpen("5"); c.read(); c.close() },
            { c ->
                c.hello("s2")
                c.read()
                c.send("<reconnect><code>CLIENT_NOT_FOUND</code></reconnect>")
                seen += c.acceptDisconnect()
                Thread.sleep(200)
            },
        ).also { printer = it }
        try { transport(p).operate(request("FinishTransaction")); fail() } catch (_: TseResultUnknownException) {}
        assertEquals(listOf("s1"), seen)
        p.assertScriptsOk()
    }

    @Test fun timeoutIsUnknownNotRepeatedAndOldSessionEnded() = runBlocking<Unit> {
        val p = FakePrinter(
            { c -> c.hello("s1"); c.acceptOpen(); seen += c.read(); runCatching { c.read() } },   // keine Antwort, bis der Client aufgibt
            { c -> c.hello("s2"); seen += c.acceptDisconnect(); c.acceptOpen(); c.read(); c.send(result(ok("GetStorageInfo"))); Thread.sleep(200) },
        ).also { printer = it }
        val t = transport(p)
        try { t.operate(request("FinishTransaction"), timeoutMs = 100); fail() } catch (e: TseResultUnknownException) {
            assertEquals("FinishTransaction", e.function)
        }
        // Kein <reconnect> (eine späte Antwort darf keinem neuen Kommando zugeordnet werden), sondern <disconnect>
        assertTrue(t.operate(request("GetStorageInfo")).contains("EXECUTION_OK"))
        assertEquals(2, seen.size)
        assertEquals("s1", seen[1])
    }

    @Test fun timeoutAndSizeLimits() = runBlocking<Unit> {
        // Zu große Anfrage: vor jedem Netzwerkzugriff abgelehnt, sicher nicht gesendet
        val unreachable = EposXmlSocketTransport("127.0.0.1", 1, connectTimeoutMs = 200, log = {})
        try { unreachable.operate(request("StartTransaction", """"processData":"${"A".repeat(70_000)}"""")); fail() }
        catch (e: TseTransportException) { assertTrue(e.message!!, e.message!!.contains("zu groß")) }

        val p = FakePrinter({ c ->
            c.hello("s1"); c.acceptOpen()
            seen += c.read(); c.send(result(ok("RunTSESelfTest")))
            Thread.sleep(200)
        }).also { printer = it }
        transport(p).operate(request("RunTSESelfTest"), timeoutMs = 500_000)
        assertTrue(seen.single().contains("<timeout>120000</timeout>")) // Handbuch: 100–120000 ms
    }

    @Test fun unreachableHostIsNotExecuted() = runBlocking<Unit> {
        val p = FakePrinter().also { printer = it }
        val port = p.port
        p.stop()
        val t = EposXmlSocketTransport("127.0.0.1", port, connectTimeoutMs = 500, log = {})
        try { t.operate(request("GetStorageInfo")); fail() } catch (e: TseTransportException) {
            assertTrue(e !is TseResultUnknownException)
        }
    }
}
