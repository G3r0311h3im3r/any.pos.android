package de.anypos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.anypos.AppContainer
import de.anypos.data.Role
import de.anypos.data.User
import de.anypos.domain.Key
import de.anypos.domain.PinHasher
import de.anypos.hardware.UsbPrinterTransport
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, user: User) {
    val scope = rememberCoroutineScope()
    val values by container.settings.values.collectAsState()
    val edits = remember { mutableStateMapOf<Key, String>() }
    var message by remember { mutableStateOf<String?>(null) }
    val users by remember { container.db.users().users() }.collectAsState(initial = emptyList())
    var pinFor by remember { mutableStateOf<User?>(null) }
    var newUser by remember { mutableStateOf(false) }
    var newUserName by remember { mutableStateOf("") }
    var newUserRole by remember { mutableStateOf(Role.CASHIER) }
    var usbInfo by remember { mutableStateOf("") }

    val context = LocalContext.current
    LaunchedEffect(Unit) { usbInfo = UsbPrinterTransport(context.applicationContext).description }

    fun v(k: Key) = edits[k] ?: values[k.name] ?: k.default

    @Composable
    fun field(k: Key, label: String, w: Int = 260) =
        OutlinedTextField(v(k), { edits[k] = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.width(w.dp))

    @Composable
    fun choice(k: Key, options: List<Pair<String, String>>) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> FilterChip(selected = v(k) == value, onClick = { edits[k] = value }, label = { Text(label) }) }
    }

    fun save() = scope.launch {
        edits.forEach { (k, value) ->
            if (value != (values[k.name] ?: k.default)) {
                container.settings.put(k, value.trim())
                container.audit.log(user.name, "EINSTELLUNG", "${k.name}=${value.trim()}")
            }
        }
        edits.clear()
        message = "Gespeichert"
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Einstellungen")
            Button(onClick = { save() }, enabled = edits.isNotEmpty(), modifier = Modifier.padding(start = 24.dp)) { Text("Speichern") }
        }

        Text("Unternehmen (Pflichtangaben auf dem Beleg)", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field(Key.COMPANY_NAME, "Firmenname"); field(Key.COMPANY_STREET, "Straße und Hausnr.")
            field(Key.COMPANY_ZIP, "PLZ", 120); field(Key.COMPANY_CITY, "Ort"); field(Key.COMPANY_COUNTRY, "Land (DEU)", 120)
            field(Key.COMPANY_TAX_NUMBER, "Steuernummer"); field(Key.COMPANY_VAT_ID, "USt-IdNr."); field(Key.REGISTER_ID, "Kassen-ID / Seriennr.")
            field(Key.RECEIPT_FOOTER, "Belegfußzeile", 540)
        }
        HorizontalDivider()

        Text("Bondrucker & Schublade", style = MaterialTheme.typography.titleMedium)
        choice(Key.PRINTER_MODE, listOf("none" to "kein Drucker", "usb" to "USB", "tcp" to "Netzwerk"))
        Text("Erkannt: $usbInfo", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field(Key.PRINTER_HOST, "Netzwerkdrucker IP"); field(Key.PRINTER_COLUMNS, "Zeichen/Zeile (48 = 80 mm)", 220)
        }
        Text("Schubladen-Kick-Pin")
        choice(Key.DRAWER_PIN, listOf("2" to "Pin 2", "5" to "Pin 5"))
        choice(Key.OPEN_DRAWER_ON_CARD, listOf("false" to "Lade nur bei Barzahlung", "true" to "Lade auch bei Karte"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { runCatching { container.printer.testPage() }.onFailure { message = it.message }; usbInfo = container.printer.transport().description } }) { Text("Testdruck") }
            OutlinedButton(onClick = { scope.launch { runCatching { container.printer.openDrawer() }.onFailure { message = it.message } } }) { Text("Schublade testen") }
        }
        HorizontalDivider()

        Text("TSE", style = MaterialTheme.typography.titleMedium)
        choice(Key.TSE_MODE, listOf("none" to "keine TSE", "simulated" to "Simulation (nur Test!)", "epson" to "Epson-TSE"))
        Text("Ohne zertifizierte TSE ist kein steuerlich zulässiger Betrieb möglich. Die Simulation erzeugt ungültige Belege und dient nur Tests/Schulung.",
            style = MaterialTheme.typography.bodySmall)
        if (v(Key.TSE_MODE) == "epson") {
            Text("Verbindung (die TSE steckt im Drucker)")
            choice(Key.TSE_CONNECTION, listOf("usb" to "USB (über den Drucker)", "tcp" to "Netzwerk"))
            val usbTse = v(Key.TSE_CONNECTION).ifBlank { if (v(Key.PRINTER_MODE) == "usb") "usb" else "tcp" } == "usb"
            if (v(Key.TSE_CONNECTION).isBlank()) {
                Text("Nicht gewählt – es gilt die Druckereinstellung: ${if (usbTse) "USB" else "Netzwerk"}", style = MaterialTheme.typography.bodySmall)
            }
            if (usbTse) {
                Text("Über das USB-Kabel des Druckers; kein Host, kein Zertifikat nötig. Drucker auf „USB“ stellen.",
                    style = MaterialTheme.typography.bodySmall)
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field(Key.TSE_HOST, "TSE-Host (leer = Drucker-IP)"); field(Key.TSE_PORT, "Port (leer = Standard)", 200)
            }
            TseEpsonSection(container, user) { message = it }
        }
        HorizontalDivider()

        Text("Kundendisplay", style = MaterialTheme.typography.titleMedium)
        Text("Ein zweites Tablet oder Smartphone im selben WLAN zeigt dem Kunden Warenkorb, Summe, Rückgeld und die " +
            "Trinkgeldabfrage – im Browser, ohne App. Das Display ersetzt nicht den Bon.", style = MaterialTheme.typography.bodySmall)
        choice(Key.CUSTOMER_DISPLAY, listOf("false" to "aus", "true" to "ein"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field(Key.CUSTOMER_DISPLAY_PORT, "Port", 120); field(Key.CUSTOMER_DISPLAY_HOST, "Adresse für Displays (leer = automatisch)", 340)
        }
        CustomerDisplaySection(container, user) { message = it }
        HorizontalDivider()

        Text("Trinkgeld", style = MaterialTheme.typography.titleMedium)
        choice(Key.TIP_ENABLED, listOf("false" to "keine Abfrage", "true" to "vor jeder Zahlung fragen"))
        if (v(Key.TIP_ENABLED) == "true") {
            Text("Trinkgeld geht an")
            choice(Key.TIP_RECIPIENT, listOf("employee" to "Mitarbeitende (ohne USt, kein Umsatz)", "owner" to "Betrieb/Inhaber (Umsatz mit USt)"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field(Key.TIP_PERCENTS, "Prozentstufen (z. B. 5,10)", 220)
                if (v(Key.TIP_RECIPIENT) == "owner") field(Key.TIP_OWNER_TAX_KEY, "USt-Schlüssel (1 = 19 %, 2 = 7 %)", 260)
            }
            choice(Key.TIP_ROUND_UP, listOf("true" to "„Aufrunden“ anbieten", "false" to "nicht aufrunden"))
            Text("Abfrage bei Bar, Karte und Aufteilen, nur bei Verkäufen mit positivem Betrag. Trinkgeld für Mitarbeitende " +
                "steht getrennt und ohne USt auf dem Bon; die Auszahlung erfolgt im Kassenbuch („Trinkgeld auszahlen“). " +
                "Mit dem Steuerberater abstimmen (Konto unten, Verteilung, Lohnunterlagen).", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()

        Text("DATEV / Steuerberater (Konten mit Steuerberater abstimmen; Vorschlag SKR03)", style = MaterialTheme.typography.titleMedium)
        Text("Alle Exporte funktionieren auch ohne E-Mail, Berater- und Mandantennummer. Fehlen die Nummern, ergänzt sie " +
            "der Steuerberater beim DATEV-Import. Das Konto für Kassendifferenzen wird erst gebraucht, wenn eine Differenz gebucht wurde.",
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field(Key.ADVISOR_EMAIL, "E-Mail Steuerberater (optional)", 300)
            field(Key.DATEV_ADVISOR, "Beraternummer (optional)", 220); field(Key.DATEV_CLIENT, "Mandantennummer (optional)", 220)
            field(Key.DATEV_FY_START, "WJ-Beginn (MMTT)", 160); field(Key.DATEV_ACCOUNT_LENGTH, "Sachkontenlänge", 160)
            field(Key.ACC_CASH, "Kasse", 140); field(Key.ACC_REV_19, "Erlöse 19 %", 140); field(Key.ACC_REV_7, "Erlöse 7 %", 140)
            field(Key.ACC_REV_0, "Erlöse 0 %", 140); field(Key.ACC_CARD, "Kartenzahlung", 140); field(Key.ACC_TRANSIT, "Geldtransit", 140)
            field(Key.ACC_PRIVATE_OUT, "Privatentnahme", 140); field(Key.ACC_PRIVATE_IN, "Privateinlage", 140)
            field(Key.ACC_DEPOSIT, "Einzahlung", 140); field(Key.ACC_PAYOUT, "Auszahlung", 140); field(Key.ACC_DIFFERENCE, "Kassendifferenz", 140)
            field(Key.ACC_VOUCHER, "Mehrzweckgutscheine", 180); field(Key.ACC_TIP, "Trinkgeld Mitarbeiter", 180)
        }
        HorizontalDivider()

        Text("Benutzer", style = MaterialTheme.typography.titleMedium)
        users.forEach { u ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${u.name} (${u.role.label()})${if (!u.active) " – deaktiviert" else ""}", Modifier.width(280.dp))
                OutlinedButton(onClick = { pinFor = u }) { Text("PIN ändern") }
                if (u.id != user.id) OutlinedButton(onClick = {
                    scope.launch {
                        container.db.users().upsert(u.copy(active = !u.active))
                        container.audit.log(user.name, "BENUTZER_STATUS", "${u.name} aktiv=${!u.active}")
                    }
                }) { Text(if (u.active) "Deaktivieren" else "Aktivieren") }
            }
        }
        OutlinedButton(onClick = { newUser = true }) { Text("Benutzer anlegen") }
        HorizontalDivider()

        BackupSection(container, user) { message = it }
        HorizontalDivider()
        KioskSection(container, user)
        HorizontalDivider()

        Text("Protokoll", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = {
            scope.launch {
                val broken = container.audit.verify()
                message = if (broken == null) "Protokoll-Hashkette ist intakt." else "Protokoll ab Eintrag $broken manipuliert oder beschädigt!"
            }
        }) { Text("Protokoll prüfen") }
    }

    pinFor?.let { u ->
        TextInputDialog("Neue PIN für ${u.name}", label = "mind. 4 Ziffern", numeric = true, password = true, onDismiss = { pinFor = null }) { pin ->
            pinFor = null
            if (pin.length < 4 || !pin.all { it.isDigit() }) { message = "PIN muss mindestens 4 Ziffern haben"; return@TextInputDialog }
            scope.launch {
                container.db.users().upsert(u.copy(pinHash = PinHasher.hash(pin)))
                container.audit.log(user.name, "PIN_GEAENDERT", u.name)
                message = "PIN geändert"
            }
        }
    }
    if (newUser) androidx.compose.material3.AlertDialog(
        onDismissRequest = { newUser = false },
        title = { Text("Benutzer anlegen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newUserName, { newUserName = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Role.entries.forEach { r -> FilterChip(selected = newUserRole == r, onClick = { newUserRole = r }, label = { Text(r.label()) }) }
                }
                Text("Start-PIN ist 0000 und sollte direkt geändert werden.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = newUserName.isNotBlank(), onClick = {
                val name = newUserName.trim()
                newUser = false; newUserName = ""
                scope.launch {
                    if (users.any { it.name.equals(name, true) }) { message = "Name existiert bereits"; return@launch }
                    container.db.users().upsert(User(name = name, pinHash = PinHasher.hash("0000"), role = newUserRole))
                    container.audit.log(user.name, "BENUTZER_NEU", "$name ${newUserRole.name}")
                }
            }) { Text("Anlegen") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { newUser = false }) { Text("Abbrechen") } },
    )
    message?.let { MessageDialog("Einstellungen", it) { message = null } }
}
