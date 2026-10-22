package de.anypos.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.anypos.AppContainer
import de.anypos.domain.Money
import de.anypos.payment.CardPaymentResult
import de.anypos.payment.SumUpSwitchBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Zahlung läuft in der SumUp-App am Kundendisplay; der Kassierer sieht den Stand und kann abbrechen. */
@Composable
fun SwitchDisplayDialog(pending: SumUpSwitchBridge.Pending, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Kartenzahlung am Kundendisplay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${Money.formatEur(pending.request.amountCents)} – " +
                    if (pending.opened) "der Kunde zahlt gerade in der SumUp-App (Tap to Pay)."
                    else "der Kunde tippt am Display auf „Mit Karte zahlen“.")
                Text("Referenz ${pending.request.reference}", style = MaterialTheme.typography.bodySmall)
                if (pending.opened) Text("Abbrechen ist jetzt nur nötig, wenn keine Rückmeldung kommt – das Ergebnis muss dann in der " +
                    "SumUp-App geprüft werden.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } },
    )
}

/**
 * Öffnet die SumUp-App auf diesem Tablet (genau einmal je Versuch). Kehrt die Kasse ohne Rückruf zurück
 * (z. B. per „Zurück“), wird das Ergebnis nach kurzer Wartezeit als unbekannt gemeldet.
 */
@Composable
fun SwitchTabletLauncher(container: AppContainer, pending: SumUpSwitchBridge.Pending) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var launched by rememberSaveable(pending.request.attemptId) { mutableStateOf(false) }
    LaunchedEffect(pending.request.attemptId) {
        if (launched) return@LaunchedEffect
        launched = true
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.link)))
        } catch (e: ActivityNotFoundException) {
            container.sumupSwitch.finish(pending.request.attemptId,
                CardPaymentResult.Declined("SumUp-App ist auf diesem Tablet nicht installiert – nichts abgebucht."))
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(pending.request.attemptId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && launched) scope.launch {
                // Der Rückruf kommt kurz vor/nach dem Wiedereintritt; danach gilt: keine Rückmeldung
                delay(3_000)
                container.sumupSwitch.abandon(pending.request.attemptId, "Keine Rückmeldung der SumUp-App")
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
