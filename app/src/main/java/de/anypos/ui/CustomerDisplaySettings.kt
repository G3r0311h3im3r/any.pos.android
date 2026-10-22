package de.anypos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.anypos.AppContainer
import de.anypos.data.User
import de.anypos.display.DisplayServer
import de.anypos.domain.Key
import de.anypos.hardware.EscPos
import kotlinx.coroutines.launch

/** QR-Code als Bild (ZXing), z. B. zum Koppeln eines Kundendisplays. */
@Composable
fun QrImage(data: String, size: Dp = 180.dp) {
    val matrix = remember(data) { QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2)) }
    Canvas(Modifier.size(size).background(Color.White)) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** Kundendisplay: Adresse, QR-Code zum Koppeln, Adresse drucken, neuer Schlüssel (sperrt alle Displays aus). */
@Composable
fun CustomerDisplaySection(container: AppContainer, user: User, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val values by container.settings.values.collectAsState()
    var confirmNewKey by remember { mutableStateOf(false) }
    // Neu berechnen, wenn sich die gespeicherten Einstellungen ändern
    val url = remember(values) { container.customerDisplayUrl() }
    val enabled = values[Key.CUSTOMER_DISPLAY.name] == "true"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        container.displayError?.let { Text(it, color = Danger) }
        when {
            !enabled -> Text("Aus. Einschalten und oben „Speichern“ – dann erscheint hier die Adresse.", style = MaterialTheme.typography.bodySmall)
            url == null -> Text("Keine Netzwerkadresse gefunden – ist das Tablet im WLAN?", color = Danger)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                QrImage(url)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Am Display-Gerät (Tablet/Smartphone im selben WLAN) diesen QR-Code scannen oder die Adresse im Browser öffnen:")
                    Text(url, style = MaterialTheme.typography.titleSmall)
                    Text("Andere IP? Feld „Adresse für Displays“ setzen. Weitere IPs dieses Geräts: " +
                        DisplayServer.localAddresses().joinToString().ifEmpty { "keine" }, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching {
                                    container.printer.print(
                                        EscPos(container.printer.columns).init().align(EscPos.Align.CENTER).bold(true).line("Kundendisplay").bold(false)
                                            .line("Mit dem Display-Gerät scannen").qr(url).line(url).feed(3).cut().bytes(),
                                    )
                                }.onFailure { onMessage("Druck fehlgeschlagen: ${it.message}") }
                            }
                        }) { Text("Adresse drucken") }
                        TextButton(onClick = { confirmNewKey = true }) { Text("Neuer Schlüssel …", color = Danger) }
                    }
                }
            }
        }
    }

    if (confirmNewKey) ConfirmDialog(
        "Neuen Schlüssel erzeugen?",
        "Alle bisher gekoppelten Displays verlieren die Verbindung und müssen mit der neuen Adresse neu geöffnet werden.",
        onDismiss = { confirmNewKey = false },
    ) {
        confirmNewKey = false
        scope.launch {
            container.settings.put(Key.CUSTOMER_DISPLAY_TOKEN, DisplayServer.newToken())
            container.audit.log(user.name, "KUNDENDISPLAY_SCHLUESSEL", "neu erzeugt")
        }
    }
}
