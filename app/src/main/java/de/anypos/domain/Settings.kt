package de.anypos.domain

import de.anypos.data.Setting
import de.anypos.data.SettingDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Alle Konfigurationsschlüssel mit Standardwerten an einer Stelle. */
enum class Key(val default: String) {
    COMPANY_NAME("Mein Laden"),
    COMPANY_STREET(""),
    COMPANY_ZIP(""),
    COMPANY_CITY(""),
    COMPANY_COUNTRY("DEU"),        // ISO 3166 alpha-3 (DSFinV-K)
    COMPANY_TAX_NUMBER(""),
    COMPANY_VAT_ID(""),
    REGISTER_ID("KASSE-01"),
    RECEIPT_FOOTER("Vielen Dank für Ihren Einkauf!"),

    PRINTER_MODE("none"),          // none | usb | tcp
    PRINTER_HOST("192.168.1.50"),
    PRINTER_COLUMNS("48"),         // 80 mm Font A = 48 Zeichen
    DRAWER_PIN("2"),               // 2 oder 5
    OPEN_DRAWER_ON_CARD("false"),

    TSE_MODE("none"),              // none | simulated | epson
    TSE_CONNECTION(""),            // usb (ESC/POS über das Druckerkabel) | tcp (ePOS Device XML); leer = wie PRINTER_MODE
    TSE_HOST(""),                  // TSE-Host (Epson-Drucker); leer = PRINTER_HOST
    TSE_PORT(""),                  // ePOS Device XML; leer = 8143 mit TLS, 8009 ohne
    TSE_TLS("true"),               // TLS zum Drucker; ohne TLS ginge die TimeAdmin-PIN im Klartext übers Netz
    TSE_TLS_FINGERPRINT(""),       // vom Admin bestätigter SHA-256-Fingerabdruck des Druckerzertifikats
    TSE_WRITE_ENABLED("false"),    // Schreibschutz: nur lesende TSE-Aufrufe, solange false
    TSE_TIMEADMIN_PIN(""),         // mit Android-Keystore verschlüsselt, nie protokolliert
    TSE_SHARED_SECRET(""),         // mit Android-Keystore verschlüsselt; leer = EPSONKEY
    TSE_CLOSE_ORPHANS("true"),     // verwaiste TSE-Transaktionen als Belegabbruch abschließen (mit Steuerberater abstimmen)

    DATEV_ADVISOR(""),
    DATEV_CLIENT(""),
    DATEV_FY_START("0101"),
    DATEV_ACCOUNT_LENGTH("4"),
    ACC_CASH("1000"),              // SKR03 Kasse
    ACC_REV_19("8400"),
    ACC_REV_7("8300"),
    ACC_REV_0("8200"),
    ACC_CARD("1360"),              // Geldtransit für Kartenzahlungen
    ACC_TRANSIT("1360"),
    ACC_PRIVATE_OUT("1800"),
    ACC_PRIVATE_IN("1890"),
    ACC_DEPOSIT("1360"),
    ACC_PAYOUT("4900"),
    ACC_DIFFERENCE(""),            // mit Steuerberater festzulegen
    ACC_VOUCHER(""),               // Mehrzweckgutscheine, mit Steuerberater festzulegen
    ACC_TIP(""),                   // Trinkgeld Mitarbeiter (Verbindlichkeit), mit Steuerberater festzulegen
    TIP_ENABLED("false"),          // Trinkgeldabfrage vor der Zahlung
    TIP_RECIPIENT("employee"),     // employee (TrinkgeldAN, ohne USt) | owner (TrinkgeldAG, Umsatz)
    TIP_PERCENTS("5,10"),          // Prozentstufen der Abfrage
    TIP_ROUND_UP("true"),          // „Aufrunden auf volle Euro“ als weitere Stufe
    TIP_OWNER_TAX_KEY("1"),        // USt-Schlüssel für Trinkgeld an den Betrieb (1 = 19 %), mit Steuerberater abstimmen
    CUSTOMER_DISPLAY("false"),     // Kundendisplay (Webseite im WLAN)
    CUSTOMER_DISPLAY_PORT("8090"),
    CUSTOMER_DISPLAY_TOKEN(""),    // zufälliger Schlüssel in der Adresse; neu erzeugen sperrt alle Displays aus
    CUSTOMER_DISPLAY_HOST(""),     // Adresse für Displays; leer = IP des Geräts

    BACKUP_TREE_URI(""),           // Zielordner der automatischen Sicherung (SAF)
    BACKUP_SECRET(""),             // Sicherungspasswort, mit Android-Keystore verschlüsselt
    BACKUP_LAST(""),               // Zeitpunkt der letzten erfolgreichen Sicherung (ms)
    EXPORT_LAST_MONTH(""),         // zuletzt automatisch exportierter Monat (yyyy-MM)
    ADVISOR_EMAIL(""),             // E-Mail des Steuerberaters für "An Steuerberater senden"
    KIOSK_MODE("false"),
}

class SettingsRepository(private val dao: SettingDao, scope: CoroutineScope) {

    private val state = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = state

    init {
        scope.launch { dao.all().collect { list -> state.value = list.associate { it.key to it.value } } }
    }

    operator fun get(key: Key): String = state.value[key.name] ?: key.default

    fun int(key: Key): Int = get(key).toIntOrNull() ?: key.default.toInt()
    fun bool(key: Key): Boolean = get(key) == "true"

    /** Lädt die Einstellungen synchron, damit sie vor dem ersten Vorgang sicher verfügbar sind. */
    suspend fun load(): Map<String, String> = dao.allOnce().associate { it.key to it.value }.also { state.value = it }

    /** Speichert und aktualisiert den Zustand sofort (nicht erst über die Datenbankbeobachtung). */
    suspend fun put(key: Key, value: String) {
        dao.put(Setting(key.name, value))
        state.value = state.value + (key.name to value)
    }
}
