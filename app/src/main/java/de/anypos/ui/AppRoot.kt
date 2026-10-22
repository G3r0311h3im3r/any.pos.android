package de.anypos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.anypos.AppContainer
import de.anypos.data.Role
import de.anypos.data.User
import de.anypos.domain.Key
import de.anypos.tse.TseStatus
import kotlinx.coroutines.delay

enum class Screen(val title: String, val icon: ImageVector, val minRole: Role) {
    POS("Kasse", Icons.Filled.PointOfSale, Role.CASHIER),
    RECEIPTS("Belege", Icons.AutoMirrored.Filled.ReceiptLong, Role.CASHIER),
    CASHBOOK("Kassenbuch", Icons.AutoMirrored.Filled.MenuBook, Role.MANAGER),
    CLOSING("Abschluss", Icons.Filled.Calculate, Role.MANAGER),
    ARTICLES("Artikel", Icons.Filled.Inventory2, Role.MANAGER),
    IMPORT("Import", Icons.Filled.UploadFile, Role.ADMIN),
    SETTINGS("Einstellungen", Icons.Filled.Settings, Role.ADMIN),
}

fun Role.allows(min: Role) = this.ordinal <= min.ordinal

@Composable
fun AppRoot(container: AppContainer) {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { container.bootstrap(); ready = true }
    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    var user by remember { mutableStateOf<User?>(null) }
    var screen by remember { mutableStateOf(Screen.POS) }
    val current = user

    Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
        if (current == null) {
            LoginScreen(container, onLogin = { u ->
                user = u
                screen = Screen.POS
            })
            return@Surface
        }
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                Spacer(Modifier.height(8.dp))
                Screen.entries.filter { current.role.allows(it.minRole) }.forEach { s ->
                    NavigationRailItem(
                        selected = s == screen,
                        onClick = { screen = s },
                        icon = { Icon(s.icon, contentDescription = s.title) },
                        label = { Text(s.title) },
                    )
                }
                Spacer(Modifier.weight(1f))
                NavigationRailItem(
                    selected = false,
                    onClick = { user = null },
                    icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Abmelden") },
                    label = { Text(current.name) },
                )
            }
            Column(Modifier.fillMaxSize()) {
                TseBanner(container)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.POS -> PosScreen(container, current)
                        Screen.RECEIPTS -> ReceiptsScreen(container, current)
                        Screen.CASHBOOK -> CashBookScreen(container, current)
                        Screen.CLOSING -> ClosingScreen(container, current)
                        Screen.ARTICLES -> ArticlesScreen(container, current)
                        Screen.IMPORT -> ImportScreen(container, current)
                        Screen.SETTINGS -> SettingsScreen(container, current)
                    }
                }
            }
        }
    }
}

/** Dauerhafter Hinweis, solange keine zertifizierte TSE angebunden ist. */
@Composable
fun TseBanner(container: AppContainer) {
    val values by container.settings.values.collectAsState()
    val mode = values[Key.TSE_MODE.name] ?: Key.TSE_MODE.default
    val tseHost = listOf(Key.TSE_CONNECTION, Key.PRINTER_MODE, Key.TSE_HOST, Key.TSE_PORT, Key.TSE_TLS, Key.TSE_TLS_FINGERPRINT).map { values[it.name] }
    val status by produceState<TseStatus?>(null, mode, tseHost, values[Key.PRINTER_HOST.name]) {
        // regelmäßig aktualisieren, sonst bleibt ein alter Fehler stehen
        while (true) {
            value = container.tse().status()
            delay(60_000)
        }
    }
    val tse = container.tse()
    if (tse.certified && status?.available == true) return
    Surface(color = Color(0xFFB71C1C), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${tse.name}: ${status?.message ?: "…"}", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}
