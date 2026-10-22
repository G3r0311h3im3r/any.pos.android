package de.anypos.tsemock

import java.net.InetAddress
import java.time.LocalTime
import kotlin.system.exitProcess

private const val USAGE = """Mock-TSE (Epson ePOS Device XML) – KEINE echte TSE, keine gültigen Belege.

Aufruf: ./gradlew :tse-mock:run --args="[--port 8009] [--scenario FRESH|JTL_TAKEOVER|OPERATIONAL]
        [--secret EPSONKEY] [--latency-ms 0] [--bound-to-other-host] [--localhost] [--tls] [--escpos]"
        --tls: TLS wie Port 8143 am Drucker (Testzertifikat des Mocks), Standardport dann 8143
        --escpos: zusätzlich TSE per ESC/POS (wie über USB) auf Port 9100

Befehle auf der Konsole:
  status                         Merkmale anzeigen (GetStorageInfo)
  power                          Neustart des Druckers simulieren (Anmeldungen, Zeit, Self-Test verfallen)
  drop                           Netzwerkausfall: alle Verbindungen trennen
  inject <CODE> [Funktion]       nächster Aufruf liefert CODE
  fault <STÖRUNG> [Funktion]     DROP_BEFORE_EXECUTE | DROP_AFTER_EXECUTE | NO_ANSWER
  quit                           beenden"""

fun main(args: Array<String>) {
    var port: Int? = null
    var tls = false
    var escpos = false
    var config = MockConfig()
    var bind: InetAddress? = null // alle Schnittstellen, damit das Tablet den Dev-PC erreicht
    val it = args.iterator()
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--port" -> port = it.next().toInt()
            "--scenario" -> config = config.copy(scenario = Scenario.valueOf(it.next().uppercase()))
            "--secret" -> config = config.copy(sharedSecret = it.next())
            "--latency-ms" -> config = config.copy(signatureLatencyMs = it.next().toLong())
            "--bound-to-other-host" -> config = config.copy(boundToOtherHost = true)
            "--localhost" -> bind = InetAddress.getLoopbackAddress()
            "--tls" -> tls = true
            "--escpos" -> escpos = true
            "--help", "-h" -> { println(USAGE); return }
            else -> { System.err.println("Unbekanntes Argument: $arg\n\n$USAGE"); exitProcess(2) }
        }
    }

    val tse = MockTse(config)
    val server = MockTseServer(tse, port ?: if (tls) 8143 else 8009, bind, if (tls) MockTseServer.tlsContext() else null) {
        println("${LocalTime.now().withNano(0)}  $it")
    }.start()
    val escServer = if (escpos) MockEscPosServer(tse, 9100, bind) { println("${LocalTime.now().withNano(0)}  $it") }.start() else null
    escServer?.let { println("ESC/POS-Zugang (wie USB) auf Port ${it.port}") }
    println("Mock-TSE lauscht auf Port ${server.port}${if (tls) " (TLS)" else ""} (${bind?.hostAddress ?: "alle Schnittstellen"}), Szenario ${config.scenario}")
    println("Seriennummer (hex): ${tse.serialNumber.joinToString("") { "%02X".format(it) }}")
    println("Testwerte: Admin-PIN ${config.adminPin}, TimeAdmin-PIN ${config.timeAdminPin}, Secret ${config.sharedSecret}")
    println("'help' zeigt die Befehle.")

    while (true) {
        val line = readlnOrNull()?.trim() ?: run { Thread.currentThread().join(); return }
        val parts = line.split(Regex("\\s+"))
        when (parts[0].lowercase()) {
            "" -> Unit
            "status" -> println(tse.handle("""{"storage":{"type":"COMMON","vendor":""},"function":"GetStorageInfo","input":{},"compress":{"required":false,"type":""}}"""))
            "power" -> { tse.powerCycle(); println("Neustart simuliert") }
            "drop" -> { server.dropAllConnections(); escServer?.dropAllConnections(); println("Verbindungen getrennt") }
            "inject" -> if (parts.size >= 2) { tse.inject(parts[1], parts.getOrNull(2)); println("eingeplant") } else println(USAGE)
            "fault" -> runCatching { server.injectFault(Fault.valueOf(parts[1].uppercase()), parts.getOrNull(2)) }
                .onSuccess { println("eingeplant") }.onFailure { println(USAGE) }
            "quit", "exit" -> { server.close(); escServer?.close(); return }
            else -> println(USAGE)
        }
    }
}
