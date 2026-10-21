package de.anypos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.anypos.AppContainer
import de.anypos.data.User
import de.anypos.domain.Key
import de.anypos.domain.PinHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LoginScreen(container: AppContainer, onLogin: (User) -> Unit) {
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var selected by remember { mutableStateOf<User?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        users = container.db.users().activeUsers()
        if (users.size == 1) selected = users.first()
    }

    fun tryLogin() {
        val u = selected ?: return
        val entered = pin
        scope.launch {
            val ok = withContext(Dispatchers.Default) { PinHasher.verify(entered, u.pinHash) }
            if (ok) {
                container.audit.log(u.name, "ANMELDUNG")
                onLogin(u)
            } else {
                container.audit.log(u.name, "ANMELDUNG_FEHLGESCHLAGEN")
                error = "PIN falsch"
                pin = ""
            }
        }
    }

    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(360.dp)) {
            Text(container.settings[Key.COMPANY_NAME], style = MaterialTheme.typography.headlineMedium)
            Text("Kasse ${container.settings[Key.REGISTER_ID]}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            Text("Bediener wählen")
            Spacer(Modifier.height(8.dp))
            users.forEach { u ->
                FilterChip(selected = u == selected, onClick = { selected = u; pin = ""; error = null }, label = { Text("${u.name} (${u.role.label()})") })
            }
        }
        Spacer(Modifier.width(48.dp))
        Card {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PIN", style = MaterialTheme.typography.titleMedium)
                Text("•".repeat(pin.length).ifEmpty { " " }, style = MaterialTheme.typography.displaySmall)
                error?.let { Text(it, color = Color(0xFFB71C1C)) }
                Spacer(Modifier.height(12.dp))
                NumPad(onKey = { k ->
                    error = null
                    when (k) {
                        "C" -> pin = ""
                        "⌫" -> pin = pin.dropLast(1)
                        else -> if (pin.length < 8) pin += k
                    }
                })
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Button(onClick = { tryLogin() }, enabled = selected != null && pin.length >= 4) { Text("Anmelden") }
            }
        }
    }
}

fun de.anypos.data.Role.label() = when (this) {
    de.anypos.data.Role.ADMIN -> "Admin"
    de.anypos.data.Role.MANAGER -> "Filialleitung"
    de.anypos.data.Role.CASHIER -> "Kasse"
}
