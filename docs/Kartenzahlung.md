# Kartenzahlung und Kartenterminal (SumUp)

*Einstellungen → Kartenzahlung*

| Terminal | Verhalten |
|---|---|
| **ohne Terminal (nur buchen)** | wie bisher: „Karte“ bucht die Zahlung, das Kartengerät wird getrennt bedient |
| **Simulation (nur Test!)** | zeigt den Ablauf mit Terminal; Ergebnis wählbar (genehmigt, abgelehnt, abgebrochen, unbekannt) |
| **SumUp** (Solo, Solo Lite) | folgt (Etappe B, SumUp Reader SDK) |

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

## Etappe B: SumUp Reader SDK

- Bibliothek `com.sumup:merchant-sdk` (Repository `https://maven.sumup.com/releases`), Lesegeräte Solo/Solo Lite per
  Bluetooth; Anmeldung mit dem SumUp-Konto in der App; Checkout mit `foreignTransactionId` = Referenz, `tip(...)`.
- SDK 7.x setzt `targetSdk` 36 voraus → Any.POS von 35 auf 36 anheben (Verhaltensänderungen Android 16 prüfen).
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
`ui/CardTerminalLauncher.kt` (Etappe B). Tests: `CardPaymentsTest`.
