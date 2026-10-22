package de.anypos.ui

import android.content.Context

/** SumUp-Kartenterminal ist in diesem Build enthalten (Gradle-Schalter `-Panypos.sumup=true`). */
object SumUpSupport {
    const val AVAILABLE = true

    /** Muss laut SumUp in Application.onCreate laufen. */
    fun init(context: Context) {
        runCatching { com.sumup.reader.sdk.api.SumUpState.init(context) }
    }
}
