# Kassenbuch und Export für den Steuerberater

## Kassenbuch in der App

*Kassenbuch* zeigt monatsweise alle **Bargeldbewegungen** mit laufendem Bestand:

- Barumsätze je Beleg und Steuersatz (bei gemischter Zahlung nur der Baranteil, anteilig je Steuersatz)
- Stornos (negativ, mit Verweis auf den Originalbeleg)
- Einzahlung, Auszahlung, Privateinlage, Privatentnahme, Geldtransit (z. B. Bankeinzahlung)
- Kassendifferenz aus dem Tagesabschluss (`DifferenzSollIst`)

Jede Ein-/Auszahlung ist ein eigener Kassenvorgang mit Belegnummer und TSE-Absicherung; es gibt **keine**
nachträglich änderbaren Kassenbuchzeilen. Korrekturen erfolgen nur durch Gegenbuchung.

**Täglicher Ablauf:**
1. Bei Kassenbeginn ggf. Wechselgeld als *Einzahlung* erfassen (nur beim ersten Mal; danach übernimmt der
   Tagesabschluss den gezählten Bestand als Anfangsbestand).
2. Ausgaben aus der Kasse als *Auszahlung*, Bankeinzahlung als *Geldtransit* (Betrag negativ).
3. Abends *Abschluss → Z-Bon*: Bargeld zählen, Betrag eingeben. Differenzen werden automatisch gebucht.

## Export

Im *Kassenbuch* stehen für den gewählten Monat bereit:
- **… speichern** – Datei an einem frei wählbaren Ort ablegen (USB-Stick, Cloud-Ordner, Gerät)
- **… teilen** – über das Android-Teilen-Menü (E-Mail, Messenger, Google Drive, Quick Share …)
- **An Steuerberater senden** – Kassenbuch und DATEV zusammen über das Teilen-Menü; ist unter
  *Einstellungen → DATEV → E-Mail Steuerberater* eine Adresse eingetragen, wird sie vorausgefüllt,
  sonst wählt man den Empfänger selbst

**Alle Exporte funktionieren ohne Angaben zum Steuerberater** (E-Mail, Beraternummer, Mandantennummer).
Rechtlich sind diese Angaben nicht vorgeschrieben (GoBD, KassenSichV und DSFinV-K verlangen sie nicht).
Für die DSFinV-K sind dagegen die Unternehmensdaten Pflicht (Name, Anschrift, Steuernummer oder USt-IdNr.).

Der DSFinV-K-Export (*Abschluss*) kann ebenfalls gespeichert oder geteilt werden. Zusätzlich legt der
**automatische Monatsexport** alle drei Dateien je Monat im Sicherungsordner ab (siehe Datensicherung.md).

> Hinweis Kioskmodus: In der vollen Sperre (Geräteeigentümer) können andere Apps nicht geöffnet werden.
> Teilen dann über den Monatsexport/Sicherungsordner oder Kioskmodus kurz ausschalten.

| Export | Inhalt | Format |
|---|---|---|
| **Export CSV** | Kassenbuch des Monats inkl. Anfangs-/Endbestand | UTF-8 mit BOM, Semikolon, Dezimalkomma – direkt in Excel lesbar |
| **Export DATEV** | Buchungsstapel (EXTF 700): Barumsätze als Tagessumme je Steuersatz, jede Bargeldbewegung einzeln, optional Kartenumsätze | Windows-1252, wie von DATEV erwartet |

### Kontenzuordnung (Einstellungen → DATEV)

Vorbelegt sind **Vorschläge für SKR03**. Sie müssen mit dem Steuerberater abgestimmt werden:

| Zweck | Vorschlag SKR03 | Bemerkung |
|---|---|---|
| Kasse | 1000 | |
| Erlöse 19 % / 7 % | 8400 / 8300 | Automatikkonten, daher kein BU-Schlüssel |
| Erlöse 0 % | 8200 | ggf. abweichend (steuerfrei / nicht steuerbar) |
| Kartenzahlungen | 1360 | Geldtransit bis Gutschrift des Zahlungsdienstleisters |
| Geldtransit / Einzahlung | 1360 | |
| Privatentnahme / -einlage | 1800 / 1890 | |
| Auszahlung | 4900 | sonstige betriebliche Aufwendungen – je nach Zweck anpassen |
| Kassendifferenz | **leer** | vom Steuerberater festzulegen; wird erst verlangt, wenn im Monat eine Kassendifferenz gebucht wurde |
| Mehrzweckgutscheine | **leer** | Verbindlichkeit aus ausgegebenen Gutscheinen; nur nötig, wenn Mehrzweckgutscheine verkauft/eingelöst werden |

Einzweckgutscheine werden bei Ausgabe auf das Erlöskonto ihres Steuersatzes gebucht (versteuert), ihre
Einlösung als Gegenposition auf demselben Konto.

Wirtschaftsjahresbeginn und Sachkontenlänge sind vorbelegt (01.01., 4 Stellen). **Beraternummer und
Mandantennummer sind optional:** Sie ordnen den Buchungsstapel nur beim Import in DATEV einem Mandanten zu.
Fehlen sie, bleiben die Felder in der Kopfzeile (Zeile 1 der Datei) leer; der Steuerberater ergänzt sie beim
Import bzw. in der Kopfzeile. Die App weist beim Export darauf hin.

> Der DATEV-Export ist gegen die EXTF-Spezifikation aufgebaut, muss aber beim ersten Mal vom Steuerberater
> testweise importiert werden. Erst nach dessen Bestätigung produktiv nutzen.

## DSFinV-K-Export (Kassen-Nachschau, Außenprüfung)

*Abschluss → DSFinV-K-Export …* → Monat oder gesamten Zeitraum wählen → ZIP speichern.

Das ZIP enthält die unveränderte amtliche `index.xml` (DSFinV-K 2.4, Datenlieferant wird eingetragen),
die DTD `gdpdu-01-09-2004.dtd` und alle 20 CSV-Dateien (UTF-8, Semikolon, Dezimalkomma). Exportiert werden
alle **Tagesabschlüsse (Z-Bons)**, die im Zeitraum erstellt wurden – Belege ohne Z-Bon sind noch nicht
exportierbar, daher vor dem Export den Tagesabschluss machen.

Vor dem Export wird geprüft (Export wird sonst verweigert): Firmenname, Straße, PLZ, Ort, Land (ISO, z. B.
`DEU`), Steuernummer **oder** USt-IdNr., Kassen-ID, mindestens ein Z-Bon im Zeitraum.

Hinweise nach dem Export (Export erfolgt trotzdem, aber vor einer Prüfung klären):
- Vorgänge ohne TSE-Signatur (TSE-Ausfall) – Ausfall muss dokumentiert sein
- Vorgänge aus der TSE-Simulation – steuerlich ungültig
- fehlendes TSE-Zertifikat – aus dem TSE-Export (TAR) nachreichen

| Thema | Umsetzung |
|---|---|
| BON_ID | Belegnummer (fortlaufend, kassenweit eindeutig) |
| Storno | eigener Beleg mit umgekehrten Vorzeichen, `BON_STORNO = 1`, Verweis in `references.csv` (Typ `Transaktion`, inkl. Z-Nr. des Originals) |
| Vorgangsabbruch | `BON_TYP = AVBelegabbruch` mit Positionen; nicht in Abschlusssummen |
| Rabatt | Positionsbetrag nach Rabatt; Grundpreis und Rabatt in `itemamounts.csv` |
| USt | je Position mit 5 Nachkommastellen, je Beleg wie gedruckt (2 Stellen) |
| Kassenbewegungen | Einzelpositionen mit GV-Typ (Anfangsbestand, Einzahlung, Auszahlung, Privatentnahme/-einlage, Geldtransit, DifferenzSollIst), USt-Schlüssel 5 |
| TSE-Zeiten | UTC mit Millisekunden; übrige Zeitstempel ISO 8601 mit Zeitzone |

> Empfehlung: Den ersten echten Export vom Steuerberater mit dessen Prüfsoftware (z. B. IDEA mit
> DSFinV-K-Prüfmakros) einlesen lassen, bevor die Kasse produktiv geht.

## Noch nicht enthalten (geplant)

- TSE-TAR-Export (kommt mit der Epson-TSE-Anbindung).
- Exportzeitraum frei wählbar (derzeit Monat oder alles).
