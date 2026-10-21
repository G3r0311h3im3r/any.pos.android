package de.anypos.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.anypos.export.ExportFile
import java.io.File

/**
 * Teilt Exportdateien über das Android-Teilen-Menü (E-Mail, Messenger, Cloud, Nearby Share …).
 * Die Dateien liegen nur kurz im App-Cache und werden per FileProvider schreibgeschützt freigegeben.
 */
fun Context.shareFiles(files: List<ExportFile>, subject: String, text: String, email: String? = null) {
    val dir = File(cacheDir, "share").apply { deleteRecursively(); mkdirs() }
    val uris = files.map { f ->
        val file = File(dir, f.name).apply { writeBytes(f.bytes) }
        FileProvider.getUriForFile(this, "$packageName.exports", file)
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single()).setType(files.single().mime)
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).setType("*/*")
    }.apply {
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
        email?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
        // ClipData sorgt dafür, dass die Leseberechtigung an die Ziel-App weitergegeben wird
        clipData = ClipData.newRawUri(files.first().name, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, subject))
}
