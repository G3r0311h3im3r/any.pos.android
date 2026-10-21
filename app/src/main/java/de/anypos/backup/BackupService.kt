package de.anypos.backup

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import de.anypos.data.PosDatabase
import de.anypos.domain.AuditLog
import de.anypos.domain.Key
import de.anypos.domain.SettingsRepository
import de.anypos.export.DocumentTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Datensicherung der Kassendatenbank.
 *
 *  - Sicherung: konsistenter Snapshot per `VACUUM INTO`, gepackt (ZIP) und mit [BackupCrypto] verschlüsselt
 *  - automatisch nach jedem Tagesabschluss in einen gewählten Ordner (USB-Stick, NAS, Cloud-Ordner)
 *  - Wiederherstellung wird vorbereitet und erst beim nächsten App-Start angewendet; die bisherige
 *    Datenbank bleibt als Kopie erhalten (keine Daten gehen verloren)
 */
class BackupService(
    private val context: Context,
    private val db: PosDatabase,
    private val settings: SettingsRepository,
    private val audit: AuditLog,
    private val secrets: SecretStore = KeystoreSecretStore(),
) {
    val hasAutoBackup: Boolean get() = settings[Key.BACKUP_TREE_URI].isNotBlank() && settings[Key.BACKUP_SECRET].isNotBlank()

    suspend fun setPassword(password: String) {
        require(password.length >= MIN_PASSWORD) { "Passwort muss mindestens $MIN_PASSWORD Zeichen haben" }
        settings.put(Key.BACKUP_SECRET, secrets.encrypt(password))
    }

    fun hasPassword(): Boolean = settings[Key.BACKUP_SECRET].isNotBlank()

    /** Schreibt eine verschlüsselte Sicherung in [out]. */
    suspend fun backupTo(out: OutputStream, password: String, userName: String) = withContext(Dispatchers.IO) {
        val snapshot = File(context.cacheDir, "backup-snapshot.db")
        snapshot.delete()
        try {
            db.openHelper.writableDatabase.execSQL("VACUUM INTO '${snapshot.absolutePath.replace("'", "''")}'")
            val meta = "app=Any.POS\nschema=${PosDatabase.VERSION}\ncreated=${System.currentTimeMillis()}\nregister=${settings[Key.REGISTER_ID]}\n"
            val pipe = File(context.cacheDir, "backup-plain.zip")
            ZipOutputStream(pipe.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(META)); zip.write(meta.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry(DB_ENTRY)); snapshot.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            try {
                pipe.inputStream().buffered().use { BackupCrypto.encrypt(it, out, password.toCharArray()) }
            } finally {
                pipe.delete()
            }
        } finally {
            snapshot.delete()
        }
        settings.put(Key.BACKUP_LAST, System.currentTimeMillis().toString())
        audit.log(userName, "DATENSICHERUNG", "Sicherung erstellt")
    }

    /** Automatische Sicherung in den eingestellten Ordner; liefert den Dateinamen oder wirft eine Ausnahme. */
    suspend fun autoBackup(userName: String): String {
        val tree = Uri.parse(settings[Key.BACKUP_TREE_URI].ifBlank { throw BackupException("Kein Sicherungsordner eingestellt") })
        val password = secrets.decrypt(settings[Key.BACKUP_SECRET].ifBlank { throw BackupException("Kein Sicherungspasswort eingestellt") })
        val name = fileName(settings[Key.REGISTER_ID])
        val file = withContext(Dispatchers.IO) { DocumentTree.createFile(context, tree, name, "application/octet-stream") }
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(file, "w")!!.use { backupTo(it, password, userName) } }
        return name
    }

    /**
     * Entschlüsselt und prüft eine Sicherung und legt sie zur Übernahme beim nächsten Start bereit.
     * Liefert die Metadaten der Sicherung.
     */
    suspend fun prepareRestore(input: InputStream, password: String, userName: String): Map<String, String> = withContext(Dispatchers.IO) {
        val plain = File(context.cacheDir, "restore-plain.zip")
        try {
            plain.outputStream().buffered().use { BackupCrypto.decrypt(input, it, password.toCharArray()) }
            var meta: Map<String, String>? = null
            val target = pendingFile(context)
            target.delete()
            ZipInputStream(plain.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when (e.name) {
                        META -> meta = String(zip.readBytes()).lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
                        DB_ENTRY -> target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            val m = meta ?: throw BackupException("Sicherung enthält keine Metadaten")
            if (!target.exists() || !isSqlite(target)) throw BackupException("Sicherung enthält keine gültige Datenbank")
            val schema = m["schema"]?.toIntOrNull() ?: 0
            if (schema > PosDatabase.VERSION) {
                target.delete()
                throw BackupException("Sicherung stammt aus einer neueren App-Version (Schema $schema) – bitte App aktualisieren")
            }
            audit.log(userName, "WIEDERHERSTELLUNG_VORBEREITET", "Sicherung vom ${m["created"]?.toLongOrNull()?.let { fmt.format(Instant.ofEpochMilli(it)) }}")
            m
        } catch (e: Exception) {
            pendingFile(context).delete()
            throw e
        } finally {
            plain.delete()
        }
    }

    companion object {
        const val MIN_PASSWORD = 8
        private const val META = "anypos-backup.txt"
        private const val DB_ENTRY = "anypos.db"
        const val EXTENSION = "anyposbak"
        private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Berlin"))
        private val fileFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm").withZone(ZoneId.of("Europe/Berlin"))

        fun fileName(registerId: String, now: Long = System.currentTimeMillis()) =
            "AnyPOS_${registerId.replace(Regex("[^A-Za-z0-9-]"), "_")}_${fileFmt.format(Instant.ofEpochMilli(now))}.$EXTENSION"

        private fun pendingFile(context: Context) = context.getDatabasePath("anypos.db.restore")

        private fun isSqlite(f: File): Boolean = f.inputStream().use { s ->
            val head = ByteArray(16); s.read(head) == 16 && String(head, Charsets.US_ASCII) == "SQLite format 3\u0000"
        }

        /**
         * Muss vor dem Öffnen der Datenbank aufgerufen werden. Übernimmt eine vorbereitete Wiederherstellung;
         * die bisherige Datenbank wird umbenannt und bleibt erhalten. Liefert den Namen der Altkopie oder null.
         */
        fun applyPendingRestore(context: Context): String? {
            val pending = pendingFile(context)
            if (!pending.exists()) return null
            val current = context.getDatabasePath("anypos.db")
            val keep = "anypos.db.vor-wiederherstellung-${System.currentTimeMillis()}"
            // Files.move wirft bei Fehlern statt still false zu liefern -> keine halbe Wiederherstellung
            if (current.exists()) {
                Files.move(current.toPath(), File(current.parentFile, keep).toPath())
                for (suffix in listOf("-wal", "-shm")) {
                    val f = File(current.path + suffix)
                    if (f.exists()) Files.move(f.toPath(), File(current.parentFile, keep + suffix).toPath())
                }
            }
            Files.move(pending.toPath(), current.toPath())
            return keep
        }
    }
}

/** Ablage des Sicherungspassworts für die automatische Sicherung. */
interface SecretStore {
    fun encrypt(plain: String): String
    fun decrypt(stored: String): String
}

/**
 * Verschlüsselt mit einem nicht exportierbaren Schlüssel im Android-Keystore. Das Passwort ist damit nur
 * auf diesem Gerät lesbar; für eine Wiederherstellung auf einem anderen Gerät muss es bekannt sein.
 */
class KeystoreSecretStore : SecretStore {
    private val alias = "anypos-backup-secret"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = ByteArrayOutputStream()
        out.write(c.iv.size); out.write(c.iv); out.write(c.doFinal(plain.toByteArray()))
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    override fun decrypt(stored: String): String {
        val b = Base64.getDecoder().decode(stored)
        val ivLen = b[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 1, ivLen)) }
        return String(c.doFinal(b, 1 + ivLen, b.size - 1 - ivLen))
    }
}
