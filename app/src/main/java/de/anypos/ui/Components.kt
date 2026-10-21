package de.anypos.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.anypos.domain.Money

@Composable
fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
}

/** Ziffernblock für PIN- und Betragseingabe. */
@Composable
fun NumPad(onKey: (String) -> Unit, modifier: Modifier = Modifier) {
    val keys = listOf(listOf("7", "8", "9"), listOf("4", "5", "6"), listOf("1", "2", "3"), listOf("C", "0", "⌫"))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    FilledTonalButton(onClick = { onKey(k) }, modifier = Modifier.size(width = 84.dp, height = 64.dp)) {
                        Text(k, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}

/** Dialog zur Eingabe eines Geldbetrags (Cent-Eingabe über Ziffernblock: "1250" = 12,50). */
@Composable
fun AmountDialog(title: String, initialCents: Long = 0, allowNegative: Boolean = false, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var digits by remember { mutableStateOf(if (initialCents > 0) initialCents.toString() else "") }
    var negative by remember { mutableStateOf(false) }
    val cents = (digits.toLongOrNull() ?: 0) * (if (negative) -1 else 1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Money.formatEur(cents), style = MaterialTheme.typography.displaySmall)
                NumPad(onKey = { k ->
                    digits = when (k) {
                        "C" -> ""
                        "⌫" -> digits.dropLast(1)
                        else -> if (digits.length < 9) (digits + k).trimStart('0') else digits
                    }
                })
                if (allowNegative) TextButton(onClick = { negative = !negative }) { Text(if (negative) "Vorzeichen: Ausgabe (−)" else "Vorzeichen: Einnahme (+)") }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(cents) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String = "", label: String = "", numeric: Boolean = false, password: Boolean = false, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Text),
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * Dialog mit frei wählbarer Breite. Das Material-3-AlertDialog begrenzt Inhalte auf 560 dp,
 * was für Eingabebereich plus Ziffernblock nicht reicht.
 */
@Composable
fun WideDialog(
    title: String, onDismiss: () -> Unit, width: androidx.compose.ui.unit.Dp = 720.dp,
    buttons: @Composable () -> Unit, content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.width(width),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { buttons() }
            }
        }
    }
}

@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Belegvorschau in Festbreitenschrift, so wie er gedruckt wird. */
@Composable
fun ReceiptPreviewDialog(text: String, onDismiss: () -> Unit, actions: @Composable () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.heightIn(max = 560.dp).width(440.dp).verticalScroll(rememberScrollState())) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp)
            }
        },
        confirmButton = { Row { actions(); TextButton(onClick = onDismiss) { Text("Schließen") } } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String = "Ja", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

fun Context.writeUri(uri: Uri, bytes: ByteArray) {
    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
}

fun Context.readUri(uri: Uri): ByteArray = contentResolver.openInputStream(uri)!!.use { it.readBytes() }

fun Context.displayName(uri: Uri): String =
    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
