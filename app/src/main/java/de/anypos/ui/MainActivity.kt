package de.anypos.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.anypos.AnyPosApp
import de.anypos.domain.Key
import de.anypos.kiosk.Kiosk
import de.anypos.payment.SumUpSwitch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var kioskActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Kassenbildschirm bleibt an, solange die App im Vordergrund ist (Tablet lädt per USB)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val container = (application as AnyPosApp).container

        // Kioskmodus folgt der Einstellung; Zurück-Taste verlässt die Kasse dann nicht
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!kioskActive) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                container.settings.values.map { it[Key.KIOSK_MODE.name] == "true" }.distinctUntilChanged().collect { on ->
                    kioskActive = on
                    if (on) Kiosk.enable(this@MainActivity) else Kiosk.disable(this@MainActivity)
                }
            }
        }

        setContent {
            PosTheme { AppRoot(container) }
        }
        handleSumUpCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSumUpCallback(intent)
    }

    /** Rückruf der SumUp-App (Payment Switch): anypos://sumup-callback/<einmalcode>?smp-status=… */
    private fun handleSumUpCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "anypos" || uri.host != "sumup-callback") return
        val nonce = uri.pathSegments.firstOrNull() ?: return
        (application as AnyPosApp).container.sumupSwitch.deliver(nonce, SumUpSwitch.params(uri.toString()))
        intent.data = null
    }
}

@Composable
fun PosTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF7FB3D5), secondary = Color(0xFF81C784))
    } else {
        lightColorScheme(primary = Color(0xFF1F5F8B), secondary = Color(0xFF2E7D32), surfaceVariant = Color(0xFFE8EEF3))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
