package de.anypos

import de.anypos.backup.BackupCrypto
import de.anypos.backup.BackupException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class BackupCryptoTest {
    private val pw = "geheim-1234".toCharArray()
    private val fast = 10_000 // Iterationen für schnelle Tests; produktiv 210.000

    private fun encrypt(data: ByteArray, password: CharArray = pw): ByteArray =
        ByteArrayOutputStream().also { BackupCrypto.encrypt(data.inputStream(), it, password, fast) }.toByteArray()

    private fun decrypt(data: ByteArray, password: CharArray = pw): ByteArray =
        ByteArrayOutputStream().also { BackupCrypto.decrypt(data.inputStream(), it, password) }.toByteArray()

    private fun expectFailure(data: ByteArray, password: CharArray = pw, text: String) {
        try { decrypt(data, password); fail("Entschlüsselung hätte scheitern müssen") } catch (e: BackupException) {
            assertTrue(e.message, e.message!!.contains(text))
        }
    }

    @Test fun roundTripEmptySmallAndMultiChunk() {
        for (size in listOf(0, 17, (1 shl 20), 3 * (1 shl 20) + 123)) {
            val data = Random(size).nextBytes(size)
            assertArrayEquals("Größe $size", data, decrypt(encrypt(data)))
        }
    }

    @Test fun ciphertextDoesNotContainPlaintext() {
        val data = "SQLite format 3".repeat(1000).toByteArray()
        val enc = encrypt(data)
        assertTrue(!String(enc, Charsets.ISO_8859_1).contains("SQLite format 3"))
    }

    @Test fun wrongPasswordIsDetected() = expectFailure(encrypt(ByteArray(100)), "falsch-123".toCharArray(), "Falsches Passwort")

    @Test fun tamperedDataIsDetected() {
        val enc = encrypt(Random(1).nextBytes(5000))
        enc[enc.size - 100] = (enc[enc.size - 100].toInt() xor 1).toByte()
        expectFailure(enc, text = "Passwort oder beschädigt")
    }

    @Test fun truncationAtBlockBoundaryIsDetected() {
        // Zwei volle Blöcke + Rest; der letzte Block wird komplett entfernt
        val data = Random(2).nextBytes(2 * (1 shl 20) + 10)
        val enc = encrypt(data)
        val lastBlock = 4 + 10 + 16 // Länge + 10 Byte Nutzdaten + Tag
        expectFailure(enc.copyOf(enc.size - lastBlock), text = "beschädigt")
    }

    @Test fun foreignFileIsRejected() = expectFailure("Hallo Welt".toByteArray(), text = "Keine Any.POS-Sicherungsdatei")
}
