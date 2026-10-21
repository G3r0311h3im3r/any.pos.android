package de.anypos.tse

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Software-Simulation für Entwicklung, UI-Tests und Schulung ohne Hardware.
 * Die Signatur ist ein SHA-256 über die Vorgangsdaten und ausdrücklich KEINE gültige TSE-Signatur.
 * Belege werden mit "SIMULATION – KEIN GÜLTIGER BELEG" gedruckt.
 */
class SimulatedTse(
    private val clock: () -> Long = System::currentTimeMillis,
    txStart: Long = 0,
    sigStart: Long = 0,
) : Tse {
    override val name = "TSE-Simulation"
    override val certified = false

    private val txCounter = AtomicLong(txStart)
    private val sigCounter = AtomicLong(sigStart)
    private val starts = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    override suspend fun status() = TseStatus(
        available = true,
        serial = SERIAL,
        message = "Simulation aktiv – Belege sind steuerlich ungültig",
        signatureCounter = sigCounter.get(),
    )

    override suspend fun startTransaction(clientId: String): TseStart {
        val tx = txCounter.incrementAndGet()
        sigCounter.incrementAndGet()
        val now = clock()
        starts[tx] = now
        return TseStart(tx, now, SERIAL)
    }

    override suspend fun finishTransaction(clientId: String, txNumber: Long, processType: String, processData: String): TseFinish {
        val start = starts.remove(txNumber) ?: throw TseException("Transaktion $txNumber ist nicht offen")
        val end = clock()
        val counter = sigCounter.incrementAndGet()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$clientId|$txNumber|$counter|$end|$processType|$processData".toByteArray())
        return TseFinish(
            txNumber = txNumber,
            startTime = start,
            endTime = end,
            signatureCounter = counter,
            signature = Base64.getEncoder().encodeToString(digest),
            serial = SERIAL,
            signatureAlgorithm = "SIMULATION",
            timeFormat = "unixTime",
            publicKey = "SIMULATION",
            certificate = "SIMULATION",
        )
    }

    companion object {
        const val SERIAL = "SIMULATION-0000"
    }
}
