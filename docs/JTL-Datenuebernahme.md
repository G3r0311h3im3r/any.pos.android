# Anleitung: Datenübernahme aus JTL in Any.POS

**Zielgruppe:** Betreiber/Administrator der Kasse  
**Gilt für:** Any.POS ab 0.1.0, Import-Format `jtl-items-v1` und JTL-Ameise-Exporte

> Die JTL-Menübezeichnungen unten beziehen sich auf JTL-Wawi 1.x mit JTL-Ameise. Je nach installierter
> Version können Bezeichnungen abweichen. Bitte beim ersten Durchlauf Screenshots ergänzen.

## Was wird übernommen – und was nicht

| Wird übernommen | Wird **nicht** übernommen |
|---|---|
| Artikelnummer, Bezeichnung, Bruttopreis, Steuersatz | Bisherige Verkäufe/Belege aus JTL-POS |
| Barcode (GTIN/EAN), Warengruppe, Aktiv-Status | TSE-Daten/Signaturen der alten Kasse |
| Stabile JTL-Artikel-ID (für spätere Aktualisierungen) | Kunden, Gutscheine, Lagerbestände |

**Wichtig – Aufbewahrung der Altdaten:** Die Kassendaten aus JTL-POS (Einzelaufzeichnungen, DSFinV-K-Export,
TSE-Export) müssen **vor dem Umstieg** vollständig exportiert und 10 Jahre aufbewahrt werden. Any.POS übernimmt
diese Daten nicht; sie bleiben Unterlagen des alten Kassensystems.

## Schritt 1: Artikel in JTL-Wawi exportieren (JTL-Ameise)

JTL-POS bezieht seine Artikel aus der JTL-Wawi. Der Export erfolgt daher aus der Wawi:

1. JTL-Wawi öffnen → Menü **Admin → JTL-Ameise** (bzw. Ameise über das Startmenü starten).
2. Reiter **Export** → Bereich **Artikel** → **Artikeldaten** wählen.
3. Folgende Felder auswählen (Spaltennamen werden von Any.POS automatisch erkannt):

   | Feld in JTL | Pflicht | Erkannte Spaltennamen in Any.POS |
   |---|---|---|
   | Interne Artikel-ID (kArtikel) | empfohlen | `kArtikel`, `Artikel-ID`, `Interne Artikel-ID`, `external_id` |
   | Artikelnummer | ja | `Artikelnummer`, `Art.Nr.`, `sku` |
   | Artikelname | ja | `Artikelname`, `Bezeichnung`, `name` |
   | Brutto-Verkaufspreis | ja* | `VK Brutto`, `Brutto-VK`, `Standard-VK Brutto`, `unit_price` |
   | Netto-Verkaufspreis | ja* | `VK Netto`, `Netto-VK`, `Standard-VK Netto` |
   | Steuersatz | ja | `Steuersatz`, `MwSt`, `USt`, `vat_rate` |
   | GTIN/EAN | nein | `GTIN`, `EAN`, `Barcode` |
   | Warengruppe/Kategorie | nein | `Warengruppe`, `Kategorie`, `category` |
   | Aktiv | nein | `Aktiv`, `Artikel aktiv`, `active` |

   \* Brutto **oder** Netto. Ist nur Netto vorhanden, rechnet Any.POS mit dem Steuersatz auf Brutto um
   (kaufmännisch auf den Cent gerundet). **Empfehlung: Bruttopreis exportieren**, damit die Kasse exakt die
   Preise aus JTL-POS verwendet.
4. Optional nur die POS-relevanten Artikel filtern (z. B. Artikel, die in JTL-POS aktiv sind).
5. Format: **CSV**, Trennzeichen **Semikolon**, Textqualifizierer **"**, Kodierung **UTF-8**
   (Windows-1252 wird ebenfalls erkannt). Speichern, z. B. `jtl_artikel_2026-10-21.csv`.

## Schritt 2: Datei auf das Tablet bringen

Eine der folgenden Möglichkeiten:
- USB-Stick per OTG-Adapter, oder
- Cloud-Speicher (Google Drive, Nextcloud …) – die Datei wird über die Android-Dateiauswahl geöffnet, oder
- per USB-Kabel vom PC in den Ordner *Download* des Tablets kopieren.

## Schritt 3: Import in Any.POS

1. Als **Admin** anmelden → **Import**.
2. **CSV-Datei wählen …** → Datei öffnen.
3. Die Vorschau prüfen:
   - *Erkannte Spalten*: Jede Pflichtspalte muss zugeordnet sein.
   - *Neu / Geändert / Unverändert / Fehler*.
   - Fehlerzeilen werden mit Zeilennummer angezeigt (z. B. unbekannter Steuersatz, doppelte Artikelnummer,
     ungültiger Preis). **Solange Fehler vorhanden sind, ist kein Import möglich** – es gibt keinen Teilimport.
4. Fehler in JTL bzw. in der CSV korrigieren und erneut wählen.
5. **Import bestätigen**. Die Übernahme erfolgt in einem Schritt (alles oder nichts) und wird mit Datum,
   Benutzer, Dateiname und Ergebnis im Importprotokoll gespeichert.

## Schritt 4: Kontrolle nach dem Import

- Unter **Artikel** Stichproben je Steuersatz prüfen (Preis, Steuersatz, Barcode).
- Einen Testartikel per Scanner erfassen (nur mit TSE-Simulation bzw. vor Inbetriebnahme!).
- JTL-Exportdatei und Importdatum für die Verfahrensdokumentation aufbewahren.

## Regeln für wiederholte Importe

- Zuordnung erfolgt zuerst über die **JTL-Artikel-ID**, sonst über die **Artikelnummer**.
- Dieselbe Datei erneut einzuspielen erzeugt keine Duplikate (alles „unverändert“).
- Artikel, die in der Datei fehlen, werden **nicht** gelöscht oder deaktiviert (Hinweis in der Vorschau).
  Deaktivieren Sie solche Artikel bewusst über `Aktiv = N` in der Exportdatei oder in der Artikelverwaltung.
- Bereits gebuchte Belege bleiben unverändert: Name, Preis und Steuersatz werden je Belegposition gespeichert.

## Zielformat `jtl-items-v1` (für eigene Skripte/Tabellen)

```csv
external_id;sku;name;unit_price;currency;vat_rate;barcode;category;active
JTL-10001;10001;"Kaffee klein";2.50;EUR;19.00;4012345678901;Getränke;true
JTL-10002;10002;"Buch";14.90;EUR;7.00;;Bücher;true
```

- `unit_price` = Bruttopreis; Dezimalpunkt oder -komma werden akzeptiert.
- `vat_rate` muss einem eingerichteten Steuersatz entsprechen: 19, 7, 7,8, 5,5 oder 0.
- `active`: true/false, ja/nein, Y/N, 1/0.
- Optional: `open_price` (true = Preis wird beim Verkauf abgefragt).

Eine Beispieldatei liegt unter `docs/samples/jtl-items-v1-beispiel.csv`.
