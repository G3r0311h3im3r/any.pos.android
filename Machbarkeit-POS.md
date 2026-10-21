# Machbarkeit: Standalone-POS für Android 13–15

**Stand:** 21.10.2026  
**Zweck:** Erste technische und organisatorische Einschätzung für ein eigenständiges Android-Kassensystem mit Epson-TSE, Bondrucker und Kassenschublade sowie Artikelübernahme aus JTL.

## Kurzbewertung

Das Vorhaben ist grundsätzlich machbar. Android 13–15 ist als Plattform für ein lokales POS geeignet. Die wesentlichen Risiken liegen nicht im Tablet, sondern in der konkreten Epson-TSE-/Drucker-Kombination, dem herstellerseitig unterstützten Zugriffsweg und der steuerlich korrekten Transaktionssteuerung. Diese Punkte müssen mit dem exakten Modell, der Firmware und dem Epson-SDK geklärt werden, bevor die Hardwarearchitektur festgeschrieben wird.

Eine belastbare erste Version sollte zunächst einen klar begrenzten Funktionsumfang haben:

- Artikel, Preise, Steuersätze und Warengruppen importieren
- Verkauf, Rabatt und Storno mit nachvollziehbaren Berechtigungen
- TSE-Transaktion und Bondruck konsistent steuern
- Tagesabschluss-/Exportdaten sowie TSE-Datenexport bereitstellen
- USB-Gerätefehler, Abbruch und Wiederanlauf sichtbar behandeln
- Kundendisplay ausdrücklich nicht enthalten

Für den Import wird in diesem Dokument eine versionierte CSV-Dateischnittstelle vorgeschlagen. Das ist zunächst eine Migrations-/Stammdaten-Schnittstelle, keine behauptete Live-Synchronisation mit JTL-POS. Ob die jeweilige JTL-Version einen direkten Export aus JTL-POS oder nur aus JTL-Wawi ermöglicht, ist anhand des konkreten Systems zu bestätigen.

## Epson-TSE: Zugriff, Aufwand und Schreibverhalten

### Wie lange wird Zugriff benötigt?

Für eine erste Kompatibilitätsprüfung sollte die Hardware für mindestens einen halben bis einen Arbeitstag verfügbar sein. Für einen belastbaren technischen Prototypen ist mit etwa **2–5 Entwicklungstagen** zu rechnen, sobald TSE, Drucker, SDK/Dokumentation und ein lauffähiges Android-Testgerät gleichzeitig vorliegen.

Das ist eine Schätzung für einen begrenzten Hardware-Proof-of-Concept, keine Zusage für eine fertige POS-Integration. Die vollständige Integration samt Fehlerfällen, Bondruck, Schublade, Import und Tests ist abhängig von der Epson-Schnittstelle und ist separat zu planen. Unbekannte oder nicht unterstützte Geräte-/SDK-Kombinationen können den Aufwand deutlich erhöhen.

Der erste Zugriff soll klären:

1. Exakte Epson-TSE-Bezeichnung, Seriennummer, Firmware und Zertifizierungs-/Betriebsstatus
2. Modell und Firmware des Bondruckers sowie die vorgesehene USB-Topologie
3. Verfügbares Epson-SDK, API-Version, Beispielcode und Nutzungsbedingungen
4. Ob die TSE direkt am Tablet, am Drucker oder über eine andere Epson-Komponente angesprochen wird
5. Ob es einen ausdrücklich vorgesehenen Test-/Demo-Modus oder eine separate Test-TSE gibt
6. Welche Transaktions-, Fehler-, Export- und Wiederanlauf-APIs bereitstehen

### Werden dabei steuerrelevante Daten geschrieben?

**Nicht auf einer produktiv verwendeten TSE.** Für Entwicklung und Tests muss eine separate Test-TSE bzw. ein von Epson ausdrücklich für Tests vorgesehener Modus verwendet werden. Vor dem ersten Aufruf, der eine TSE-Transaktion startet, wird die verwendete TSE identifiziert und der Teststatus schriftlich festgehalten.

Eine TSE ist kein rein lesbares Testgerät: Sobald die Anwendung eine Transaktion startet oder abschließt, kann sie Vorgänge und zugehörige fiskalische Daten dauerhaft protokollieren. Deshalb wird die TSE im Prototyp nicht mit echten Verkäufen oder echten Kundendaten getestet. Auch Testvorgänge auf einer separaten Test-TSE können dort dauerhaft gespeichert werden. Die genaue Wirkung einzelner API-Aufrufe ist anhand der Epson-Dokumentation zu verifizieren.

Für die spätere Produktivversion gilt: Ein Verkauf darf erst als erfolgreich abgeschlossen gelten, wenn die TSE- und Belegabläufe in einem konsistenten Zustand sind. Bei nicht eindeutigem TSE-Ergebnis darf die App weder stillschweigend einen Erfolg melden noch eine Transaktion blind wiederholen. Der Vorgang muss gesperrt bzw. als klärungsbedürftig angezeigt werden und die Wiederaufnahme muss über dokumentierte TSE-/SDK-Funktionen erfolgen.

> Vor Produktivbetrieb müssen die Epson-Freigabe, die Konformität der konkreten Kassenlösung und die steuerrechtlichen Pflichten durch qualifizierte Stellen bestätigt werden. Dieses Dokument ist keine Rechts- oder Steuerberatung.

## Vorgesehene Hardwarearchitektur

```text
Android-Tablet (USB-C, Android 13–15)
   └─ USB-Host/OTG bzw. geeigneter Powered Hub
        └─ Epson-Bondrucker
             ├─ Kassenschublade am Drawer/Kick-Anschluss des Druckers
             └─ TSE nur gemäß freigegebener Epson-Topologie
```

Die Zeichnung ist ein Prüfmodell und keine bestätigte Verkabelungsanweisung. Insbesondere darf nicht vorausgesetzt werden, dass eine Epson-TSE wie ein beliebiges USB-Gerät parallel zum Drucker am Tablet betrieben werden kann. Epson kann den Zugriff beispielsweise über die Drucker-/TSE-Kombination oder eine bestimmte Schnittstelle vorgeben. Das ist anhand der konkreten Geräte und Epson-Unterlagen zu bestätigen.

Zu prüfen sind:

- Unterstützt das Tablet USB-Host/OTG mit gleichzeitiger Stromversorgung und Laden?
- Reicht die Stromversorgung für den Drucker bzw. ist ein eigener Netzanschluss nötig?
- Benötigen Drucker und TSE getrennte USB-Ports, einen Hub oder eine Netzwerkverbindung?
- Gibt es für Android ein offizielles SDK und Unterstützung für Android 13–15?
- Wie werden USB-Berechtigungen, Wiederverbindung nach Abziehen und App-Neustart gehandhabt?
- Wird die Schublade über den Drucker-Kick-Ausgang angesteuert? Steckerbelegung und Impuls müssen zum Schubladenmodell passen.
- Sind gleichzeitiger Druck, TSE-Kommunikation und Schubladenimpuls vom konkreten Drucker unterstützt?

Die Schublade sollte im Regelfall über den dafür vorgesehenen Anschluss des Bondruckers angesteuert werden, nicht direkt über das Tablet. Auch das muss mit dem Druckermodell bestätigt werden.

## JTL-Datenübernahme: vorgeschlagene Schnittstelle

### Umfang und Herkunft der Daten

Der erste Import übernimmt **Stammdaten**, nicht bestehende Verkäufe oder TSE-Protokolle. Vorgesehen sind Artikelnummer, Name, Preis, Steuersatz, Warengruppe, Barcode und Aktivstatus. Kundenstammdaten sind optional und sollten nur aufgenommen werden, wenn der konkrete Ablauf sie benötigt.

Als Quelle ist zunächst ein CSV-Export aus der vorhandenen JTL-Installation vorgesehen. Je nach JTL-Version und Einrichtung kann der Export aus JTL-Wawi statt direkt aus JTL-POS erfolgen. Ein automatisierter Online-Abgleich, ein JTL-API-Connector und der Import vergangener Kassenbons sind nicht Teil dieser ersten Schnittstelle.

### Importformat `jtl-items-v1`

- Eine UTF-8-kodierte CSV-Datei mit Kopfzeile
- Trennzeichen Semikolon (`;`), Textfelder in doppelten Anführungszeichen
- Dezimaltrennzeichen Punkt (`.`), keine Tausendertrennzeichen
- Datei enthält jeweils den vollständigen Artikelbestand für den Importlauf
- `external_id` muss innerhalb des Datenbestands eindeutig und dauerhaft sein
- Leere optionale Felder werden als leerer Wert übertragen; Pflichtfelder dürfen nicht leer sein
- Unbekannte Spalten können ignoriert werden; unbekannte Schema-Versionen werden abgewiesen
- Der Import wird vollständig validiert, bevor Daten übernommen werden; Fehler führen nicht zu einem stillen Teilimport

| Feld | Pflicht | Bedeutung / Regeln |
|---|---:|---|
| `external_id` | Ja | Dauerhafte ID aus JTL, unverändert bei Folgeimporten |
| `sku` | Ja | Artikelnummer; in der POS eindeutig |
| `name` | Ja | Verkaufsbezeichnung |
| `unit_price` | Ja | Brutto-Verkaufspreis, Dezimalpunkt, z. B. `12.50` |
| `currency` | Ja | ISO-Währungscode, zunächst `EUR` |
| `vat_rate` | Ja | Prozentwert, z. B. `19.00`; Abbildung auf die in der Kasse eingerichtete Steuerkategorie |
| `barcode` | Nein | EAN/Barcode; bei Mehrfachcodes später separate Detaildatei oder Erweiterung |
| `category` | Nein | Warengruppenname |
| `active` | Ja | `true` oder `false`; inaktive Artikel bleiben referenzierbar, sind aber nicht verkaufbar |

Beispiel:

```csv
external_id;sku;name;unit_price;currency;vat_rate;barcode;category;active
JTL-10001;10001;"Kaffee klein";2.50;EUR;19.00;4012345678901;Getränke;true
JTL-10002;10002;"Buch";14.90;EUR;7.00;;Bücher;true
```

**Wichtig:** Das Beispiel zeigt das vorgeschlagene Ziel-CSV, nicht die unveränderte Exportstruktur einer JTL-Version. Beim Import muss die tatsächliche JTL-Exportdatei über einen dokumentierten Mapping-Schritt auf dieses Schema abgebildet werden. Das Mapping der Brutto-/Nettopreise sowie der Steuersätze muss der Betreiber bestätigen. Steuersätze dürfen nicht aus Produkttexten oder Kategorien geraten werden.

### Validierung und Übernahmeverhalten

1. Datei in der POS-Administration auswählen.
2. Schema, Pflichtfelder, Eindeutigkeit, Zahlenformate und Steuerzuordnung prüfen.
3. Vorschau mit Anzahl neuer, geänderter, deaktivierter und fehlerhafter Artikel zeigen.
4. Betreiber bestätigt den Lauf; erst dann werden die gültigen Daten atomar übernommen.
5. Ergebnisbericht mit Lauf-ID, Zeit, Quell-Dateiname und Zeilennummern der Fehler speichern.
6. Bei gleichem `external_id` werden die Stammdaten aktualisiert; bestehende Verkaufsvorgänge bleiben unverändert.
7. Artikel, die in einem späteren Vollimport fehlen, werden nicht automatisch gelöscht oder deaktiviert. Diese Änderung bedarf einer expliziten, vorab angezeigten Bestätigung.

Ein Import muss wiederholbar sein: Das erneute Einspielen derselben Datei darf keine Duplikate erzeugen. Historische Belegpositionen müssen ihren damaligen Artikelnamen, Preis und Steuersatz behalten, auch wenn der Artikel später geändert wird.

## Anwenderanleitung: JTL-Export für die POS vorbereiten

Da JTL-Version und Konfiguration noch nicht bekannt sind, ist dies die allgemeine Anleitung; die konkreten Menübezeichnungen sind vor Auslieferung mit der verwendeten JTL-Version abzugleichen.

1. In JTL die für den Kassenbetrieb relevanten Artikel exportieren. Falls JTL-POS keinen passenden Stammdatenexport anbietet, den Export aus der verbundenen JTL-Wawi verwenden.
2. Mindestens Artikel-ID, Artikelnummer, Bezeichnung, Verkaufspreis, Steuersatz und Aktivstatus exportieren. Barcode und Warengruppe ergänzen, wenn verfügbar.
3. Den Export nicht direkt in der Kasse laden, wenn die Spalten nicht dem Format `jtl-items-v1` entsprechen. Zunächst über eine Tabellenkalkulation oder ein kontrolliertes Transformationsskript auf die oben genannte Spaltenreihenfolge und Semikolontrennung abbilden.
4. `external_id` mit einer stabilen JTL-ID befüllen. Keine laufende Zeilennummer verwenden.
5. Preis- und Steuerwerte prüfen: Ein Beispiel aus jeder Steuergruppe gegen JTL kontrollieren; festlegen, ob JTL den Preis brutto oder netto exportiert und auf `unit_price` (Brutto) umgerechnet wird.
6. CSV als UTF-8 speichern. In Textfeldern enthaltene Semikolons/Anführungszeichen korrekt maskieren lassen; keine Spalten manuell löschen.
7. Datei in der POS zunächst als Vorschau importieren. Fehlerbericht und Summen prüfen und erst danach den Import bestätigen.
8. Nach dem Import Stichproben für Artikelnummer, Barcode, Preis, Steuer und Verkaufbarkeit durchführen. Die JTL-Quelldatei und den POS-Importbericht für die vereinbarte Betriebsdokumentation aufbewahren.

Vor dem Produktivstart sind die obigen Schritte mit einem repräsentativen Export der konkreten JTL-Version zu erproben und Menüpfade/Screenshots in einer versionsspezifischen Betriebsanleitung zu ergänzen.

## Testumgebung: benötigte Ausstattung und Zugänge

### Hardware

- Android-Tablet mit Android 13, 14 oder 15, exakt benanntes Modell und USB-C-Adapter
- Das genaue Epson-TSE-Modell; möglichst eine separate Test-TSE oder offiziell freigegebene Testumgebung
- Der vorgesehene Epson-Bondrucker, Netzteil, Kabel und Firmware-Version
- Kompatible Kassenschublade mit bekanntem Anschluss-/Kick-Verhalten
- Falls erforderlich: powered USB-Hub bzw. OTG-Adapter, der mit Tablet und Drucker getestet werden soll
- Optional: zweites Tablet oder zweiter Drucker für Wiederverbindungs-/Kompatibilitätstests

### Software und Unterlagen

- Epson SDK/API-Dokumentation für genau diese Geräte und Android-Version
- Epson-Beispielprojekt, Bibliotheken, Treiber und Nutzungs-/Lizenzbedingungen
- Epson-Anweisung für Testbetrieb, TSE-Statusabfrage, Transaktionsablauf und Datenexport
- Modell-/Firmware-/Seriennummern, Konfigurations- und Anschlussplan
- JTL-Version, Exportbeispiel mit anonymisierten Daten und Beschreibung der Quellfelder
- Erwartete Steuersätze, Währung, Preislogik (brutto/netto) und Artikel-ID-Regeln
- Testfälle für Verkauf, Abbruch, Storno, Rabatt, mehrere Steuersätze, USB-Trennung, Drucker-/TSE-Ausfall und Neustart

Keine Zugangsdaten, echten Kundendaten oder produktiven TSE-Transaktionen für die erste Testphase bereitstellen. Falls ein Gerät nicht als Testgerät gekennzeichnet werden kann, vor Nutzung schriftlich mit Epson klären, welche Daten die vorgesehenen Prüfaufrufe erzeugen.

## Grobe Umsetzungsschätzung

Die Werte sind Entwicklungsschätzungen bei verfügbarer Dokumentation und funktionsfähiger Hardware, keine Festpreis- oder Lieferzusage.

| Arbeitspaket | Grobe Größenordnung |
|---|---:|
| Epson-/USB-Kompatibilitätsprüfung und TSE-Proof-of-Concept | 2–5 Arbeitstage |
| CSV-Import, Validierung, Mapping und Importbericht | 3–6 Arbeitstage |
| POS-Grundfunktionen, Belegfluss und Hardwareintegration | 2–4 Wochen |
| Robustheit, Fehlerfälle, Testläufe und Pilot | 1–3 Wochen |

Das Projekt sollte nach dem Epson-Proof-of-Concept neu geschätzt werden. Nicht enthalten sind rechtliche Prüfung, Zertifizierung/Freigaben, Produktiv-Rollout, Hardwarebeschaffung und ein späterer Live-Abgleich mit JTL.

## Offene Entscheidungen vor dem Implementierungsstart

1. Exakte TSE-, Drucker-, Schubladen- und Tablet-Modelle sowie Firmwarestände
2. Bestätigung, dass für die vorhandene Epson-TSE ein dokumentiertes Android-SDK bzw. ein unterstützter Zugriffsweg existiert
3. Verfügbarkeit einer separaten Test-TSE/Testumgebung und schriftliche Epson-Anweisung zu Testtransaktionen
4. JTL-Version und reale anonymisierte Beispielexporte
5. Gewünschte POS-Mindestfunktionen, Bonlayout, Storno-/Rabattregeln und Bedienerrollen
6. Offline-Verhalten und gewünschte Sicherung/Exportstrategie
7. Verantwortliche Person für steuerliche Anforderungen und Abnahme

**Fazit:** Die technische Umsetzung ist plausibel, aber die Epson-Schnittstelle ist der entscheidende Machbarkeits-Gate. Für den ersten Test wird nur eine ausdrücklich als Test verwendete TSE empfohlen. Eine endgültige Aufwandsschätzung und belastbare USB-Verdrahtung sind erst nach Identifikation der konkreten Epson-Modelle und Prüfung der passenden SDK-Unterlagen möglich.
