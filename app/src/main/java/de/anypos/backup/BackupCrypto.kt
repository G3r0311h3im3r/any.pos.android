package de.anypos.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Passwortbasierte, blockweise Verschlüsselung (AES-256-GCM, Schlüssel per PBKDF2-HMAC-SHA256).
 *
 * Format: MAGIC | Iterationen (int) | Salt (16) | Nonce-Präfix (4) | Blöcke …
 * Block: Länge (int) | Chiffretext inkl. Tag. Nonce = Präfix + Blocknummer; Zusatzdaten = Blocknummer + Endkennung.
 * Vertauschte, fehlende oder abgeschnittene Blöcke sowie ein falsches Passwort werden erkannt.
 */
object BackupCrypto {
    private val MAGIC = "ANYPOSBK1".toByteArray(Charsets.US_ASCII)
    const val ITERATIONS = 210_000
    private const val CHUNK = 1 shl 20
    private const val TAG_BITS = 128

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray, iterations: Int = ITERATIONS) {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val prefix = ByteArray(4).also(rnd::nextBytes)
        val key = deriveKey(password, salt, iterations)
        val out = DataOutputStream(output)
        out.write(MAGIC); out.writeInt(iterations); out.write(salt); out.write(prefix)

        val buf = ByteArray(CHUNK)
        var next = ByteArray(CHUNK)
        var nextLen = readFully(input, next)
        var index = 0L
        while (true) {
            // Ein Block Vorausschau, damit der letzte Block als solcher gekennzeichnet werden kann
            System.arraycopy(next, 0, buf, 0, nextLen)
            val len = nextLen
            nextLen = if (len == CHUNK) readFully(input, next) else 0
            val last = nextLen == 0
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
            cipher.updateAAD(aad(index, last))
            val ct = cipher.doFinal(buf, 0, len)
            out.writeInt(ct.size); out.write(ct)
            index++
            if (last) break
        }
        out.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val pin = PushbackInputStream(input, 1)
        val inp = DataInputStream(pin)
        val magic = ByteArray(MAGIC.size)
        try { inp.readFully(magic) } catch (e: EOFException) { throw BackupException("Keine Any.POS-Sicherungsdatei", e) }
        if (!magic.contentEquals(MAGIC)) throw BackupException("Keine Any.POS-Sicherungsdatei")
        val iterations = inp.readInt()
        if (iterations !in 10_000..10_000_000) throw BackupException("Sicherungsdatei beschädigt")
        val salt = ByteArray(16).also { inp.readFully(it) }
        val prefix = ByteArray(4).also { inp.readFully(it) }
        val key = deriveKey(password, salt, iterations)
        var index = 0L
        while (true) {
            val len = try { inp.readInt() } catch (e: EOFException) { throw BackupException("Sicherungsdatei unvollständig", e) }
            if (len !in 16..(CHUNK + 16)) throw BackupException("Sicherungsdatei beschädigt")
            val ct = ByteArray(len).also {
                try { inp.readFully(it) } catch (e: EOFException) { throw BackupException("Sicherungsdatei unvollständig", e) }
            }
            // Ohne weitere Daten muss dies der als "letzter" gekennzeichnete Block sein
            val peek = pin.read()
            val last = peek == -1
            if (!last) pin.unread(peek)
            val plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
                cipher.updateAAD(aad(index, last))
                cipher.doFinal(ct)
            } catch (e: AEADBadTagException) {
                throw BackupException(if (index == 0L) "Falsches Passwort oder beschädigte Sicherung" else "Sicherungsdatei beschädigt oder manipuliert", e)
            }
            output.write(plain)
            index++
            if (last) break
        }
        output.flush()
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, iterations, 256)).encoded
        return SecretKeySpec(bytes, "AES")
    }

    /** 4 Byte Zufall + 8 Byte Blocknummer; der Schlüssel ist je Sicherung neu (eigenes Salt). */
    private fun nonce(prefix: ByteArray, index: Long): ByteArray = ByteBuffer.allocate(12).put(prefix).putLong(index).array()

    private fun aad(index: Long, last: Boolean): ByteArray = ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array()

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
