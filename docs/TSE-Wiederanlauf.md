# TSE: Störungen, Wiederanlauf und verwaiste Transaktionen

Baustein für die **Verfahrensdokumentation** der Kasse (Any.POS mit Epson-TSE). Beschreibt, wie die Kasse
mit Störungen der TSE-Verbindung umgeht. Stand: 22.10.2026.

## Grundsätze

- Jeder Vorgang (Verkauf, Storno, Rückgabe, Kassenbewegung, Abbruch) startet zu Beginn eine TSE-Transaktion
  und schließt sie am Ende mit den Vorgangsdaten (`Kassenbeleg-V1`) ab.
- Ist die TSE nicht erreichbar, wird der Vorgang trotzdem erfasst und auf dem Beleg sowie in der DSFinV-K
  als **TSE-Ausfall** gekennzeichnet. Ausfälle stehen mit Zeit und Grund im Protokoll der Kasse.
- Ein Befehl an die TSE wird **nie blind wiederholt**. Ist unklar, ob die TSE ihn ausgeführt hat
  (Verbindungsabbruch nach dem Senden), fragt die Kasse den Zustand der TSE ab und entscheidet danach.

## Abschluss mit unklarem Ergebnis

1. Der Drucker liefert eine zurückgehaltene Antwort nach dem Wiederverbinden nach → Ergebnis bekannt.
2. Sonst: Ist die Transaktion laut TSE noch offen, kam der Abschluss nachweislich nicht an und wird erneut
   gesendet.
3. Ist sie nicht mehr offen und gehört die letzte Antwort der TSE zu dieser Transaktion, werden deren
   Signaturdaten übernommen.
4. Andernfalls bleibt der Vorgang als TSE-Ausfall gekennzeichnet.

## Verwaiste Transaktionen

Eine TSE-Transaktion kann ohne Vorgang in der Kasse zurückbleiben – etwa wenn die Antwort auf den Start
verloren ging oder die Kasse zwischen Start und Speichern ausfiel.

- Die Kasse gleicht ihre offenen Transaktionen mit der TSE ab: beim Programmstart, nach einem Start mit
  unklarem Ergebnis, wenn die TSE keine weiteren Transaktionen annimmt, und **vor jedem Kassenabschluss**.
- Offene Transaktionen **dieser Kasse** (Client-ID), zu denen kein offener Vorgang existiert, werden als
  Belegabbruch abgeschlossen: Vorgangsart `Kassenbeleg-V1`, Vorgangsdaten
  `AVBelegabbruch^0.00_0.00_0.00_0.00_0.00^` (ohne Umsatz, ohne Zahlung).
  Transaktionen anderer Kassen am selben Drucker werden nie berührt.
- Für jede so abgeschlossene Transaktion legt die Kasse einen **eigenen Vorgang** mit fortlaufender
  Belegnummer an (Vorgangstyp `AVBelegabbruch`, keine Positionen, keine Zahlung, Bediener „System“),
  damit jeder abgesicherte Vorgang in der DSFinV-K erkennbar ist. Die Startzeit der Transaktion ist der Kasse
  nicht bekannt; das Feld bleibt leer, und `TSE_TA_FEHLER` enthält den Hinweis
  „Wiederanlauf: verwaiste TSE-Transaktion als Belegabbruch abgeschlossen, Startzeit unbekannt“.
- Jeder Abgleich steht im Protokoll der Kasse (`TSE_ABGLEICH`).
- Abschaltbar über die Einstellung `TSE_CLOSE_ORPHANS` (Standard: ein).

### Rechtliche Grundlage

- AEAO zu § 146a Nr. 1.12.2.4: Die TSE protokolliert Vorgangsbeginn und Vorgangsbeendigung, „auch bei
  Vorgangsabbruch“.
- DSFinV-K 2.4, Anhang A: Belegabbrüche sind „andere Vorgänge“ und abzusichern.
- DSFinV-K 2.4, Anhang B: „Der Vorgangstyp ‚AVBelegabbruch‘ kennzeichnet alle Vorgänge, die nach
  Transaktionsbeginn abgebrochen werden. Eine tatsächliche Bezahlung darf … nicht erfolgen.“
- DSFinV-K 2.4, Kap. 2.1: Alle abzusichernden Vorgänge sind „zwingend auch in der DSFinV-K als erkennbare
  Vorgänge darzustellen“.
- Gängige Praxis anderer Kassenhersteller: offene Transaktionen vor bzw. beim Tagesabschluss abschließen.

## Verbindung zum Drucker

- Verschlüsselt (TLS, Port 8143) mit vom Administrator bestätigtem Zertifikat des Druckers.
- Das TSE-Gerät wird nach jeder Aktion freigegeben; andere Anwendungen am selben Drucker werden nicht
  blockiert.
- Nach einem Neustart beendet die Kasse ihre alte Sitzung am Drucker, damit die TSE nicht gesperrt bleibt.
- Schreibende TSE-Befehle sind nur möglich, wenn der Administrator den Schreibschutz bewusst aufhebt
  (protokolliert).
