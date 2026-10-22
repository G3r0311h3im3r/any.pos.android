# Umstellung von JTL-POS auf Any.POS – was vorher gesichert werden muss und welche Daten wir brauchen

*Schreiben an den Betreiber der Kasse. Bitte zusammen mit dem Steuerberater durchgehen.*

---

Hallo,

wir bereiten die Umstellung der Kasse von JTL-POS auf Any.POS vor. Der Epson-Bondrucker und die daran
angeschlossene Epson-TSE (technische Sicherheitseinrichtung) bleiben erhalten und werden vom neuen
Kassensystem weiter genutzt. Damit das reibungslos und rechtssicher läuft, brauchen wir vorab ein paar
Dinge von dir. Bitte nichts davon löschen oder zurücksetzen – die TSE darf nicht neu eingerichtet werden,
sonst gehen die Daten der bisherigen Kasse verloren.

## 1. Zugangsdaten der TSE (bitte sicher aufbewahren, nicht per E-Mail schicken)

Bei der Einrichtung der TSE in JTL-POS wurden drei Geheimnisse festgelegt. Ohne diese kann die neue Kasse
die TSE nicht übernehmen:

| Was | Wofür | Wo zu finden |
|---|---|---|
| **Admin-PIN** der TSE | einmalige Übernahme der TSE durch Any.POS | JTL-POS → Einstellungen → TSE / Fiskal; ggf. beim Einrichter der JTL-Kasse |
| **TimeAdmin-PIN** der TSE | täglicher Betrieb (Zeit setzen, Belege signieren) | ebenda |
| **PUK** der TSE | Entsperren, falls eine PIN zu oft falsch eingegeben wurde | ebenda |

Falls diese Werte nirgends notiert sind: Bitte beim JTL-Support bzw. beim Einrichter der Kasse nachfragen.
JTL-POS zeigt sie unter Umständen in der TSE-Konfiguration an. **Nicht raten und nicht ausprobieren** –
nach mehreren Fehlversuchen sperrt sich die TSE.

Außerdem wissen wir noch nicht, ob JTL-POS am Drucker einen eigenen „Shared Secret“ (Geräteschlüssel)
hinterlegt hat. Das klären wir in einem ersten, rein lesenden Test am Drucker; dafür muss nichts
vorbereitet werden.

## 2. Angaben zur Hardware und zur TSE

Bitte vom Typenschild bzw. aus JTL-POS (TSE-Info) notieren oder fotografieren:

- Druckermodell und Seriennummer (Typenschild auf der Unterseite, z. B. „TM-m30III“, „C31CK50…“)
- Seriennummer der TSE (lange Hex-Zeichenfolge; JTL-POS zeigt sie in der TSE-Info und auf jedem Bon)
- Ablaufdatum des TSE-Zertifikats (steht in der TSE-Info)
- Kassen-ID / Client-ID, unter der JTL-POS die TSE nutzt
- Falls der Drucker im Netzwerk hängt: seine IP-Adresse (steht auf dem Status-Ausdruck des Druckers)
- Aus der ursprünglichen Meldung ans Finanzamt (siehe Punkt 4): die dort gemeldete Seriennummer des
  Aufzeichnungssystems und der TSE sowie das Anschaffungsdatum

## 3. Sicherungen, die VOR dem Umstellungstag angefertigt werden müssen

Diese Daten müssen zehn Jahre aufbewahrt werden (GoBD). Nach der Umstellung sind sie aus JTL-POS
unter Umständen nicht mehr bequem erreichbar, darum vorher exportieren:

1. **Letzter Tagesabschluss (Z-Bericht) in JTL-POS** am Umstellungstag, keine offenen Vorgänge lassen.
2. **DSFinV-K-Export aus JTL-POS** über den gesamten Nutzungszeitraum (vom ersten Tag bis zum letzten
   Tagesabschluss). Das ist das Format, das das Finanzamt bei einer Kassen-Nachschau verlangt.
3. **TSE-Export (TAR-Datei)** aus JTL-POS. Das sind die signierten Protokolldaten der TSE. **Dabei die
   Option „Daten auf der TSE löschen“ nicht wählen.** Die Daten bleiben zusätzlich auf der TSE.
4. **Sicherung der JTL-POS-Datenbank** (JTL-POS bietet dafür eine Backup-Funktion) – als Rückfallebene.
5. **Export der Stammdaten** (Artikel, Warengruppen, Steuersätze, ggf. Kunden) als CSV. Diese übernehmen wir
   in Any.POS; das Format ist in `docs/JTL-Datenuebernahme.md` beschrieben.
6. **Kassenbuch / Tagesabschlüsse** der letzten Monate als PDF oder Ausdruck, falls nicht im DSFinV-K-Export enthalten.
7. Die bestehende **Verfahrensdokumentation** der Kasse (sofern vorhanden) – sie wird für Any.POS fortgeschrieben.

Alle Sicherungen bitte an zwei Orten ablegen (z. B. USB-Stick + Cloud/Server) und den Steuerberater
informieren, wo sie liegen.

## 4. Meldung ans Finanzamt (§ 146a Abs. 4 AO)

Seit 2025 müssen elektronische Kassen samt TSE über das ELSTER-Portal („Mein ELSTER“ → Mitteilung über
elektronische Aufzeichnungssysteme) gemeldet werden. Ein Kassenwechsel ist eine solche Mitteilung:

- **Außerbetriebnahme** von JTL-POS (Datum des letzten Tagesabschlusses)
- **Inbetriebnahme** von Any.POS mit derselben TSE (Datum der Umstellung)
- Frist: innerhalb eines Monats nach der Änderung

Die dafür nötigen Angaben (Seriennummern, Art des Systems, Datum) stellen wir zusammen. Bitte mit dem
Steuerberater abstimmen, wer die Meldung abgibt – ob die ursprüngliche Meldung für JTL-POS überhaupt
erfolgt ist, bitte ebenfalls prüfen.

## 5. Ablauf am Umstellungstag (Vorschau)

1. Letzter Verkauf in JTL-POS, Tagesabschluss, Exporte nach Punkt 3.
2. Any.POS übernimmt die TSE (dafür wird die Admin-PIN einmalig im Gerät eingegeben, sie wird nicht gespeichert).
3. Testbeleg in Any.POS, Prüfung von TSE-Daten und QR-Code auf dem Bon.
4. JTL-POS nicht mehr für Verkäufe benutzen, aber nicht deinstallieren, bis der Steuerberater grünes Licht gibt.
5. Meldung ans Finanzamt nach Punkt 4.

Bis dahin entwickeln und testen wir die TSE-Anbindung ausschließlich gegen eine Simulation und eine
separate Entwickler-TSE von Epson. Die produktive TSE wird vor der Umstellung nur ausgelesen, es werden
keine Testbelege darauf erzeugt.

Bei Fragen einfach melden.

Viele Grüße
Michael
