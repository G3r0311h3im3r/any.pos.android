package de.anypos.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Eine Zeile auf dem Kundendisplay (Beträge bereits formatiert). */
data class DisplayLine(
    val name: String,
    val quantity: String?,
    val unitPrice: String?,
    val total: String,
    val discount: String?,
    val highlight: Boolean = false,
)

/** Was das Kundendisplay zeigt. Es zeigt nur an; einzige Eingabe ist die Trinkgeldauswahl ([CustomerDisplayHub.choose]). */
sealed interface DisplayState {
    val shop: String

    data class Idle(override val shop: String) : DisplayState

    /** [notice]: Hinweis an den Kunden, z. B. nach einer abgelehnten Kartenzahlung. */
    data class Basket(override val shop: String, val lines: List<DisplayLine>, val totalCents: Long, val notice: String? = null) : DisplayState

    /** Kartenzahlung über die SumUp-App auf dem Display-Smartphone: Kunde tippt [link], zahlt per Tap to Pay. */
    data class CardPay(override val shop: String, val amountCents: Long, val link: String, val nonce: String, val opened: Boolean) : DisplayState

    data class TipAsk(
        override val shop: String,
        val askId: String,
        val baseCents: Long,
        val options: List<TipOption>,
        val chosen: String? = null,
    ) : DisplayState

    data class Paid(
        override val shop: String,
        val totalCents: Long,
        val tipCents: Long,
        val givenCents: Long?,
        val changeCents: Long?,
    ) : DisplayState
}

/** Auswahl des Kunden am Display zu einer bestimmten Trinkgeldfrage. */
data class TipChoice(val askId: String, val optionId: String)

/**
 * Verbindet die Kasse mit den Kundendisplays: die Kasse veröffentlicht den Zustand, jedes Display bekommt ihn
 * sofort (auch beim späteren Verbinden). Eingaben vom Display gelten nur für die gerade gestellte Trinkgeldfrage
 * und werden erst wirksam, wenn der Kassierer sie übernimmt.
 */
class CustomerDisplayHub {
    private val _state = MutableStateFlow<DisplayState>(DisplayState.Idle(""))
    val state: StateFlow<DisplayState> = _state.asStateFlow()

    private val _choices = MutableSharedFlow<TipChoice>(extraBufferCapacity = 8)
    val choices: SharedFlow<TipChoice> = _choices.asSharedFlow()

    fun publish(state: DisplayState) { _state.value = state }

    /** @return false, wenn keine passende Frage offen ist oder die Auswahl nicht angeboten wurde. */
    fun choose(askId: String, optionId: String): Boolean {
        val ask = _state.value as? DisplayState.TipAsk ?: return false
        if (ask.askId != askId || ask.options.none { it.id == optionId }) return false
        _state.value = ask.copy(chosen = optionId)
        _choices.tryEmit(TipChoice(askId, optionId))
        return true
    }

    companion object {
        /** JSON für die Display-Seite; Beträge formatiert, damit die Seite nichts rechnen muss. */
        fun json(s: DisplayState): String = buildJsonObject {
            put("shop", s.shop)
            when (s) {
                is DisplayState.Idle -> put("mode", "idle")
                is DisplayState.Basket -> {
                    put("mode", "cart")
                    put("total", Money.formatEur(s.totalCents))
                    put("notice", s.notice)
                    putJsonArray("lines") {
                        s.lines.forEach { l ->
                            addJsonObject {
                                put("name", l.name)
                                put("qty", l.quantity)
                                put("unit", l.unitPrice)
                                put("total", l.total)
                                put("discount", l.discount)
                                put("hl", l.highlight)
                            }
                        }
                    }
                }
                is DisplayState.TipAsk -> {
                    put("mode", "tip")
                    put("ask", s.askId)
                    put("total", Money.formatEur(s.baseCents))
                    put("chosen", s.chosen)
                    putJsonArray("options") {
                        s.options.forEach { o ->
                            addJsonObject {
                                put("id", o.id)
                                put("label", o.label)
                                put("amount", if (o.cents == 0L) null else "+ " + Money.formatEur(o.cents))
                                put("total", Money.formatEur(s.baseCents + o.cents))
                            }
                        }
                    }
                }
                is DisplayState.CardPay -> {
                    put("mode", "card")
                    put("total", Money.formatEur(s.amountCents))
                    put("link", s.link)
                    put("nonce", s.nonce)
                    put("opened", s.opened)
                }
                is DisplayState.Paid -> {
                    put("mode", "paid")
                    put("total", Money.formatEur(s.totalCents))
                    putEur("tip", s.tipCents.takeIf { it != 0L })
                    putEur("given", s.givenCents)
                    putEur("change", s.changeCents?.takeIf { it > 0 })
                }
            }
        }.toString()

        private fun JsonObjectBuilder.putEur(key: String, cents: Long?) = put(key, cents?.let { Money.formatEur(it) })
    }
}
