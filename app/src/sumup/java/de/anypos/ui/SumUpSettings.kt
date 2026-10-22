package de.anypos.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sumup.merchant.reader.api.SumUpAPI
import com.sumup.merchant.reader.api.SumUpLogin
import com.sumup.merchant.reader.api.SumUpLoginContract
import de.anypos.AppContainer
import de.anypos.data.User
import de.anypos.domain.Key
import kotlinx.coroutines.launch

/** SumUp: Anmeldung mit dem Händlerkonto, Lesegerät (Solo/Solo Lite) einrichten, abmelden. */
@Composable
fun SumUpSection(container: AppContainer, user: User, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    var refresh by remember { mutableIntStateOf(0) }
    val loggedIn = remember(refresh) { runCatching { SumUpAPI.isLoggedIn() }.getOrDefault(false) }
    val merchant = remember(refresh) { runCatching { SumUpAPI.getCurrentMerchant()?.merchantCode }.getOrNull() }
    val readerConnected = remember(refresh) { runCatching { SumUpAPI.isCardReaderConnected() }.getOrDefault(false) }

    val login = rememberLauncherForActivityResult(SumUpLoginContract()) { result ->
        val code = result.data?.getIntExtra(SumUpAPI.Response.RESULT_CODE, -1)
        refresh++
        scope.launch {
            container.audit.log(user.name, "SUMUP_ANMELDUNG", if (code == 1) "erfolgreich" else "Ergebnis $code")
        }
        if (code != 1 && code != 11) onMessage("SumUp-Anmeldung nicht erfolgreich: " + (result.data?.getStringExtra(SumUpAPI.Response.MESSAGE) ?: "abgebrochen"))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (loggedIn) "Bei SumUp angemeldet" + (merchant?.let { " (Händler $it)" } ?: "") +
                if (readerConnected) " · Lesegerät verbunden" else " · Lesegerät nicht verbunden"
            else "Nicht bei SumUp angemeldet",
            color = if (loggedIn) MaterialTheme.colorScheme.onSurface else Danger,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !loggedIn, onClick = {
                val key = container.settings[Key.SUMUP_AFFILIATE_KEY].trim()
                if (key.isEmpty()) onMessage("Zuerst den Affiliate Key eintragen und oben „Speichern“.")
                else login.launch(SumUpLogin.builder(key).build())
            }) { Text("Bei SumUp anmelden") }
            OutlinedButton(enabled = loggedIn && activity != null, onClick = {
                activity?.let { SumUpAPI.openCardReaderPage(it, REQUEST_CARD_READER) }
            }) { Text("Lesegerät einrichten") }
            TextButton(enabled = loggedIn, onClick = {
                SumUpAPI.logout()
                refresh++
                scope.launch { container.audit.log(user.name, "SUMUP_ABMELDUNG", "") }
            }) { Text("Abmelden") }
            TextButton(onClick = { refresh++ }) { Text("Status aktualisieren") }
        }
        Text("Solo/Solo Lite: am Gerät vom SumUp-Konto abmelden, dann hier „Lesegerät einrichten“ (Bluetooth). " +
            "SumUp braucht Internet; Bluetooth und Standort müssen eingeschaltet sein.", style = MaterialTheme.typography.bodySmall)
    }
}

private const val REQUEST_CARD_READER = 4711
