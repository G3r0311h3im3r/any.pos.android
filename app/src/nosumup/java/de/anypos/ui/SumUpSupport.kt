package de.anypos.ui

import android.content.Context

/**
 * SumUp-Kartenterminal ist vorbereitet (Quellen unter `src/sumup`), aber in diesem Build nicht enthalten –
 * kein SDK, keine Bluetooth-/Standortrechte. Einschalten beim Bauen mit `-Panypos.sumup=true`.
 */
object SumUpSupport {
    const val AVAILABLE = false

    fun init(context: Context) = Unit
}
