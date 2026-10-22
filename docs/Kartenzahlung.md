# Kartenzahlung und Kartenterminal (SumUp)

*Einstellungen → Kartenzahlung*

| Terminal | Verhalten |
|---|---|
| **ohne Terminal (nur buchen)** | Kartengerät wird getrennt bedient (z. B. SumUp Solo, SumUp-App, EC-Terminal): „Karte“ zeigt den Betrag, **gebucht wird erst nach „Genehmigt – buchen“**; „Abbrechen“ lässt den Vorgang offen |
| **Simulation (nur Test!)** | zeigt den Ablauf mit Terminal; Ergebnis wählbar (genehmigt, abgelehnt, abgebrochen, unbekannt) |
| **SumUp** (Solo, Solo Lite) | vorbereitet, **standardmäßig nicht im Build** (siehe unten); SumUp Reader SDK, Lesegerät per Bluetooth |

**Ohne Terminal:** Erst am Kartengerät zahlen lassen, dann bestätigen. Beide Geräte sind nicht verbunden – beim
Tagesabschluss „Unbar“ auf dem Z-Bon mit der Tagesübersicht des Kartenanbieters vergleichen. Bei Trinkgeld den
Gesamtbetrag eingeben, den die Kasse anzeigt.

## Ablauf mit Terminal

1. **Karte** (ggf. nach der Trinkgeldabfrage) → ein **Zahlungsversuch** wird gespeichert, *bevor* das Terminal startet.
   Referenz `<Kassen-ID>-<Belegnummer>-<Versuch>`, nie wiederverwendet; sie geht an SumUp als eigene
   Transaktions-ID (verhindert Doppelabbuchung).
2. Ergebnis:

| Ergebnis | Kasse |
|---|---|
| genehmigt | Beleg wird abgeschlossen (TSE); Zahlung mit Transaktionscode, Kartenmarke, letzten 4 Ziffern; Bon zeigt sie |
| abgelehnt / abgebrochen | Vorgang bleibt offen: erneut versuchen (neue Referenz) oder bar |
| **unbekannt** (Verbindung weg) | **weder abschließen noch neu abbuchen**: Kassierer prüft in der SumUp-App unter *Transaktionen* und wählt „abgebucht“ (Beleg wird abgeschlossen) oder „nicht abgebucht“ |

3. Unter 1,00 € nimmt SumUp keine Kartenzahlung an → bar.
4. **Aufteilen:** je Beleg ein Kartenanteil über das Terminal.
5. **Absturz/Neustart während einer Zahlung:** Der Vorgang wird beim Wiederanlauf als Abbruch abgeschlossen; die
   Kartenzahlung erscheint an der Kasse als **„Ungeklärte Kartenzahlung“**. Wurde abgebucht, gibt es dazu keinen Beleg
   → in SumUp erstatten oder den Verkauf neu erfassen.
6. **Notfall „ohne Terminal buchen“** (Einstellung, Standard erlaubt): nach Ablehnung/Abbruch, wenn an einem anderen
   Gerät genehmigt wurde; wird protokolliert (`KARTE_OHNE_TERMINAL`).
7. **Storno/Retoure:** Die Erstattung wird gebucht; die Kasse weist darauf hin, sie im SumUp-Händlerbereich bzw. in der
   SumUp-App auszulösen (das SDK kann nicht erstatten).

Trinkgeld geht an SumUp getrennt (Betrag ohne Trinkgeld + Trinkgeld); SumUps eigene Trinkgeldabfrage am Gerät bleibt aus.

Protokoll: `KARTE_START`, `KARTE_ERGEBNIS`, `KARTE_GEKLAERT`, `KARTE_UNTERBROCHEN`, `KARTE_OHNE_TERMINAL`.

## SumUp Reader SDK (Etappe B, vorbereitet 22.10.2026 – Test mit Gerät steht aus)

**Nicht im Standard-Build** (Entscheidung 22.10.2026): Der SumUp-Code liegt unter `app/src/sumup/java`, im normalen
Build wird `app/src/nosumup/java` verwendet (kein SDK, keine Bluetooth-/Standortrechte, „SumUp“ erscheint nicht in den
Einstellungen, APK ca. 3 MB). Einschalten beim Bauen:

    ./gradlew :app:assembleRelease -Panypos.sumup=true          (Bash)
    .\gradlew.bat :app:assembleRelease "-Panypos.sumup=true"    (PowerShell: Anführungszeichen nötig)

Im Emulator wurde mit eingeschaltetem SDK geprüft: Anmeldung bei SumUp (Händler M7R8YXW3, Affiliate Key für
`de.anypos`), Bezahlvorgang ohne Lesegerät → „abgelehnt, nichts abgebucht“.

Tap to Pay (Smartphone/Tablet als Terminal) ist ein eigenes SumUp-SDK mit Zugang nur auf Anfrage, NFC am Kassen-Tablet,
`targetSdk` 36 und gesperrten Entwickleroptionen – zurückgestellt.

- Bibliothek `com.sumup:merchant-sdk:7.1.0` (Repository `https://maven.sumup.com/releases`, nur für die Gruppen
  `com.sumup.*` und `net.sf.smc` freigegeben). Verlangt Core Library Desugaring; `targetSdk` 35 genügt.
- Das APK wächst dadurch von ca. 3 MB auf ca. 35 MB. Neue Berechtigungen aus dem SDK: Bluetooth (Scan/Connect),
  Standort, Netzwerkstatus, Wake-Lock, Vordergrunddienst, Start nach dem Booten.
- *Einstellungen → Kartenzahlung → SumUp*: Affiliate Key eintragen und **Speichern**, dann **Bei SumUp anmelden**
  (Anmeldemaske von SumUp in der App), **Lesegerät einrichten** (Solo/Solo Lite koppeln), **Abmelden**.
- Bezahlvorgang: `total` = Betrag ohne Trinkgeld, `tip` = Trinkgeld, `foreignTransactionId` = Referenz,
  Wiederholungsstrategie aktiv (bei Verbindungsabbruch „Status unbekannt“ statt „fehlgeschlagen“), Erfolgsbildschirm
  von SumUp übersprungen. Der Vorgang startet je Versuch genau einmal (auch bei Bildschirmdrehung).
- Auswertung (`payment/SumUpResults.kt`): **abgelehnt** nur, wenn sicher nichts abgebucht wurde (Anmeldung fehlt,
  Bluetooth/Standort aus, Karte abgelehnt …). **Unbekannt** bei unbekanntem Status, schon vorhandener Referenz,
  fehlender Rückgabe, Erfolg ohne Transaktionscode oder **abweichendem Betrag**.
- Später möglich: Status bei „unbekannt“ automatisch über die SumUp-API abfragen, automatische Erstattung,
  Offline-Zahlungen (Solo Lite, Freischaltung durch SumUp).

### Voraussetzungen beim Betreiber

1. SumUp-Händlerkonto.
2. Im SumUp-Entwicklerportal: **Affiliate Key** mit der Anwendungskennung **`de.anypos`** (genau die App-ID von Any.POS).
   Sandbox-Konto zum Testen ohne echtes Geld.
3. Den Affiliate Key in *Einstellungen → Kartenzahlung* eintragen (nicht ins Repository).
4. Solo oder Solo Lite; für Bluetooth vom SumUp-Konto am Gerät abmelden.

## Technik

`payment/CardPayments.kt` (Versuche, Ergebnis, Klärung, Wiederanlauf), Tabelle `card_attempt` (DB-Version 4),
`Payment.providerTxCode/cardScheme/cardLast4`, `PosViewModel` (Ablauf), `ui/CardDialogs.kt`,
`ui/CardTerminalLauncher.kt` (SumUp-Bezahlvorgang), `ui/SumUpSettings.kt`, `payment/SumUpResults.kt`.
Tests: `CardPaymentsTest`, `SumUpResultsTest`.
