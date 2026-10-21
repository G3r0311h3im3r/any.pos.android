package de.anypos.domain

import de.anypos.data.AuditDao
import de.anypos.data.AuditEntry
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinHasher {
    private const val ITERATIONS = 120_000

    fun hash(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "pbkdf2$$ITERATIONS$${b64(salt)}$${b64(derive(pin, salt, ITERATIONS))}"
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val expected = Base64.getDecoder().decode(parts[3])
        val actual = derive(pin, Base64.getDecoder().decode(parts[2]), parts[1].toInt())
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}

/**
 * Protokoll für Einstellungs-, Stammdaten- und Systemereignisse (Login, Schublade ohne Verkauf,
 * Import, TSE-Ausfall …). Jeder Eintrag enthält den Hash des Vorgängers; eine nachträgliche
 * Änderung in der Datenbank bricht die Kette und ist mit [verify] erkennbar.
 */
class AuditLog(private val dao: AuditDao) {

    suspend fun log(userName: String, action: String, details: String = "") {
        val prev = dao.last()?.hash ?: GENESIS
        val ts = System.currentTimeMillis()
        dao.insert(AuditEntry(timestamp = ts, userName = userName, action = action, details = details, prevHash = prev, hash = hashOf(prev, ts, userName, action, details)))
    }

    /** Liefert die ID des ersten ungültigen Eintrags oder null, wenn die Kette intakt ist. */
    suspend fun verify(): Long? {
        var prev = GENESIS
        for (e in dao.all()) {
            if (e.prevHash != prev || e.hash != hashOf(prev, e.timestamp, e.userName, e.action, e.details)) return e.id
            prev = e.hash
        }
        return null
    }

    companion object {
        const val GENESIS = "0"

        fun hashOf(prev: String, ts: Long, user: String, action: String, details: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest("$prev|$ts|$user|$action|$details".toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
