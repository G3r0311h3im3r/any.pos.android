# Any.POS – Standalone-Kasse für Android-Tablets (Android 13–15)

Offline-Kasse für Einzelhandel/Gastronomie-Theke, Funktionsumfang angelehnt an JTL-POS.
Kotlin, Jetpack Compose, Room (SQLite), keine Cloud-Abhängigkeit.

## Status 0.1.0

| Bereich | Stand |
|---|---|
| Verkauf: Artikelraster, Warengruppen, Suche, Barcode-Scanner (HID), Menge, Preis, Rabatt | ✅ |
| Zahlung bar (Rückgeld, Schnellbeträge) / Karte (externes Terminal) / **Splitzahlung** | ✅ |
| Belegjournal, Kopie, Vollstorno (Gegenbeleg), **Teil-Retoure**, Vorgangsabbruch protokolliert | ✅ |
| **Gutscheine** (Mehrzweck/Einzweck, Restwert, Storno) – `docs/Gutscheine-und-Rueckgabe.md` | ✅ |
| **Verschlüsselte Datensicherung**, automatisch nach Z-Bon, Wiederherstellung – `docs/Datensicherung.md` | ✅ |
| **Kioskmodus** (Bildschirmfixierung / Geräteeigentümer) – `docs/Kioskmodus.md` | ✅ (Stufe 2 noch am Gerät zu testen) |
| Kassenbuch (Einzahlung, Auszahlung, Privat, Geldtransit, Differenz) mit laufendem Bestand | ✅ |
| X-Bon, Z-Bon mit Kassensturz und automatischer Differenzbuchung | ✅ |
| Export Kassenbuch CSV und DATEV-Buchungsstapel (EXTF 700) | ✅ (Import beim Steuerberater testen) |
| Exporte teilen (E-Mail an Steuerberater, Cloud …) und automatischer Monatsexport | ✅ |
| JTL-Artikelimport (JTL-Ameise / `jtl-items-v1`) mit Vorschau, Validierung, Protokoll | ✅ |
| Bondruck ESC/POS über USB (Epson), Schublade über Drucker-Kick, Netzwerkdrucker optional | ✅ ungetestet an Hardware |
| Benutzer mit PIN und Rollen, Protokoll mit Hash-Kette | ✅ |
| TSE-Schnittstelle, Simulation, processData/QR nach DSFinV-K | ✅ |
| **Epson-TSE** | ⏳ Phase 2 (bewusst zurückgestellt, siehe `docs/TSE-Plan.md`) |
| DSFinV-K-Export 2.4 (alle 20 Tabellen, amtliche index.xml, ZIP) | ✅ (erste Prüfung durch Steuerberater empfohlen) |
| Kundenverwaltung, Bonlogo, Statistik | ⏳ geplant |

> **Kein Produktivbetrieb ohne zertifizierte TSE.** Die App zeigt das dauerhaft im roten Banner an.

## Bauen und testen

Voraussetzungen: Android Studio (JDK 21 im `jbr`), Android SDK Platform 37 (compileSdk), min/target Android 13/15.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest   # 65 Unit-/Integrationstests
.\gradlew.bat :app:assembleDebug       # APK: app\build\outputs\apk\debug\app-debug.apk
```

Erstanmeldung: Benutzer **Admin**, PIN **1234** – sofort unter *Einstellungen → Benutzer* ändern.

## Aufbau

```
app/src/main/java/de/anypos/
  data/       Room-Entities und DAOs (Belege unveränderlich, Stornos als Gegenbeleg)
  domain/     PosService (Vorgang/TSE/Beleg), ClosingService (X/Z), Cart, Money, Settings, AuditLog
  tse/        Tse-Schnittstelle, NoTse, SimulatedTse, EpsonTse (Platzhalter), ProcessData
  hardware/   ESC/POS, USB-/TCP-Transport, Belegaufbau (Drucker + Textvorschau)
  importer/   CSV-Parser, JTL-Importer
  export/     Kassenbuch, DATEV, DSFinV-K (Schema aus assets/dsfinvk/index.xml)
  backup/     Verschlüsselte Sicherung und Wiederherstellung
  kiosk/      Kioskmodus (Lock Task, Geräteeigentümer)
  ui/         Compose-Oberfläche (Tablet-Querformat)
docs/         Anleitungen (JTL-Import, Hardware/USB, Kassenbuch/Export, TSE-Plan)
```

Beträge werden durchgehend als ganzzahlige Cent (`Long`) geführt, Steuersätze in Basispunkten.
