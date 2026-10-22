# Umzug von JTL-POS zu Any.POS – Schritt für Schritt

Diese Anleitung führt Sie durch den Wechsel Ihrer Kasse von **JTL-POS** zu **Any.POS**.
Sie brauchen dafür keine IT-Kenntnisse. Arbeiten Sie die Punkte der Reihe nach ab und haken Sie ab,
was erledigt ist.

**Das Wichtigste vorab**

- Ihr Epson-Bondrucker bleibt. Im Drucker steckt die **TSE** – das ist der kleine Sicherheits-Chip, der
  jeden Verkauf fälschungssicher „unterschreibt“. Any.POS nutzt **denselben Chip** weiter.
- Den Chip **niemals zurücksetzen, neu einrichten oder Daten darauf löschen**. Sonst gehen Daten verloren,
  die Sie 10 Jahre aufbewahren müssen.
- **PINs nie raten.** Nach drei falschen Versuchen sperrt sich der Chip.
- Planen Sie für den Umzugstag etwa **1–2 Stunden außerhalb der Öffnungszeiten** ein.

---

## Teil A – Ein paar Tage vorher: Unterlagen zusammensuchen

### ☐ 1. Die drei Geheimzahlen des Chips (PINs) bereitlegen

Bei der Einrichtung in JTL-POS wurden drei Zahlen festgelegt. Sie brauchen sie für den Umzug:

| Name | Wofür | Länge |
|---|---|---|
| **Admin-PIN** | einmal beim Umzug und für Exporte | 5 Ziffern |
| **TimeAdmin-PIN** | für den täglichen Kassenbetrieb | 5 Ziffern |
| **PUK** | nur für den Notfall (gesperrte PIN entsperren) | 6 Ziffern |

Wo finden? In JTL-POS unter den TSE-Einstellungen, in Ihren Einrichtungs-Unterlagen oder beim Einrichter
bzw. JTL-Support. **Schreiben Sie die Zahlen auf Papier und bewahren Sie es sicher auf – nicht per E-Mail
verschicken.**

Fragen Sie bei der Gelegenheit auch: *„Wurde am Drucker ein eigenes Shared Secret (Geräteschlüssel)
eingestellt?“* Wenn ja, notieren Sie es (8 Zeichen). Wenn nein oder unbekannt, ist das nicht schlimm.

### ☐ 2. Diese Angaben notieren (oder abfotografieren)

- Druckermodell (Typenschild unten am Drucker, z. B. „TM-m30III“)
- **IP-Adresse des Druckers**: Drucker ausschalten, die Taste **Feed** (Papiervorschub) gedrückt halten
  und dabei einschalten. Es druckt ein Statuszettel – darauf steht die „IP Address“, z. B. `192.168.1.50`.
- Seriennummer der TSE und Ablaufdatum des Zertifikats (stehen in JTL-POS in der TSE-Info und auf jedem Bon)

### ☐ 3. Any.POS auf dem Tablet installieren

1. Die Datei `AnyPOS-….apk` auf das Tablet kopieren und antippen. Android fragt, ob Apps aus dieser Quelle
   installiert werden dürfen – mit **Zulassen** bestätigen, dann **Installieren**.
2. Any.POS öffnen, **Admin** antippen, PIN **1234** eingeben, **Anmelden**.
3. Sofort die PIN ändern: **Einstellungen** → ganz unten bei *Benutzer* → **PIN ändern**.

### ☐ 4. Any.POS vorbereiten (geht ohne Drucker und ohne Chip)

In **Einstellungen**:

- **Unternehmen:** Firmenname, Straße, PLZ, Ort, Steuernummer oder USt-IdNr. eintragen → oben **Speichern**.
  Diese Angaben stehen auf jedem Bon und sind Pflicht.
- **Datensicherung:** **Sicherungsordner wählen** (z. B. USB-Stick oder Cloud) und **Passwort festlegen**.
  Das Passwort gut aufbewahren – ohne Passwort lässt sich eine Sicherung nicht zurückholen.
- **TSE:** vorerst auf **keine TSE** lassen.

### ☐ 5. Artikel aus JTL übernehmen

Ihre Artikel (Name, Preis, Steuersatz, Barcode, Warengruppe) holen Sie aus JTL und spielen sie in Any.POS ein.
**Bisherige Verkäufe werden dabei nicht übertragen** – die bleiben in den JTL-Sicherungen aus Punkt 7.

**a) In JTL exportieren** (am PC mit der JTL-Wawi – falls jemand anderes die Wawi betreut, geben Sie ihm
diesen Abschnitt):

1. JTL-Wawi öffnen → **JTL-Ameise** starten (Menü *Admin* bzw. Startmenü).
2. **Export** → **Artikel** → **Artikeldaten**.
3. Diese Felder auswählen:
    - Interne Artikel-ID (kArtikel) – *empfohlen*
    - **Artikelnummer**
    - **Artikelname**
    - **Brutto-Verkaufspreis** (VK Brutto)
    - **Steuersatz**
    - GTIN/EAN (Barcode) – falls Sie scannen
    - Warengruppe – damit die Kasse die Artikel in Gruppen zeigt
    - Aktiv
4. Nur die Artikel auswählen, die an der Kasse verkauft werden (z. B. alle in JTL-POS aktiven Artikel).
5. Als **CSV** speichern: Trennzeichen **Semikolon (;)**, Zeichensatz **UTF-8**.
   Dateiname zum Beispiel `artikel_umzug.csv`.

> Die Bezeichnungen können je nach JTL-Version leicht abweichen. Wichtig sind vor allem Artikelnummer,
> Name, **Bruttopreis** und Steuersatz. Die Spaltennamen erkennt Any.POS selbst.

**b) Datei auf das Tablet bringen** – eine Möglichkeit genügt:

- auf einen USB-Stick kopieren und den Stick (mit Adapter) ans Tablet stecken, oder
- in Google Drive / Ihre Cloud legen, oder
- per Kabel vom PC in den Ordner *Download* des Tablets kopieren.

**c) In Any.POS einspielen**

1. Als Admin angemeldet: links **Import** → **CSV-Datei wählen …** → Datei antippen.
2. Any.POS zeigt eine **Vorschau**: wie viele Artikel *neu* sind, welche Spalten erkannt wurden und ob es
   **Fehler** gibt (mit Zeilennummer, z. B. „unbekannter Steuersatz“ oder „doppelte Artikelnummer“).
3. Gibt es Fehler: **Verwerfen**, die Stelle in JTL korrigieren, neu exportieren und wieder einspielen.
   Solange Fehler da sind, wird nichts übernommen – es kann also nichts halb kaputtgehen.
4. Ohne Fehler: **Import bestätigen**.

**d) Kurz prüfen**

- Links **Artikel** öffnen und ein paar Artikel ansehen: Stimmen Preis und Steuersatz (19 % / 7 %)?
- Falls Sie scannen: einen Barcode im Suchfeld der Kasse scannen – der Artikel muss erscheinen.
  *(Noch nicht verkaufen, solange keine TSE eingerichtet ist.)*

**Gut zu wissen**

- Sie können den Import **jederzeit wiederholen**, z. B. am Umzugstag mit den aktuellsten Preisen. Es
  entstehen keine doppelten Artikel; geänderte Preise werden übernommen.
- Artikel, die in der Datei fehlen, werden **nicht** gelöscht. Nicht mehr gewünschte Artikel in JTL auf
  „inaktiv“ setzen und neu einspielen oder in Any.POS unter **Artikel** deaktivieren.
- Technische Einzelheiten für Ihren Betreuer: `JTL-Datenuebernahme.md`.

---

## Teil B – Am Umzugstag: JTL-POS sauber beenden

### ☐ 6. Letzter Tag mit JTL-POS

1. Letzten Verkauf in JTL-POS abschließen. **Kein Vorgang darf offen sein.**
2. **Tagesabschluss (Z-Bericht)** in JTL-POS machen.

### ☐ 7. Daten aus JTL-POS sichern (10 Jahre aufbewahren!)

In JTL-POS exportieren:

- **DSFinV-K-Export** für den **gesamten** Zeitraum, in dem Sie JTL-POS genutzt haben
- **TSE-Export (TAR-Datei)** – dabei **„Daten auf der TSE löschen“ NICHT anhaken**
- **Sicherung der JTL-POS-Daten** (Backup-Funktion von JTL-POS)

Alles an **zwei Orten** speichern (z. B. USB-Stick und Cloud) und dem Steuerberater sagen, wo es liegt.

### ☐ 8. JTL-POS vom Chip abmelden

Melden Sie JTL-POS wie vorbereitet vom Chip ab (nach der Anleitung von JTL bzw. Ihres Einrichters).
Danach JTL-POS **nicht mehr zum Kassieren benutzen** – aber auch **nicht deinstallieren**, bis der
Steuerberater zustimmt.

---

## Teil C – Am Umzugstag: Any.POS mit dem Chip verbinden

Das Tablet muss im **selben Netzwerk (WLAN)** wie der Drucker sein.

### ☐ 9. Drucker in Any.POS eintragen

**Einstellungen → Bondrucker & Schublade:** **Netzwerk** wählen, bei *Netzwerkdrucker IP* die IP-Adresse
vom Statuszettel eintragen → oben **Speichern** → **Testdruck**. Es muss ein Zettel kommen.

### ☐ 10. Chip einrichten

**Einstellungen → TSE:**

1. **Epson** antippen → oben **Speichern**. Das Feld *TSE-Host* leer lassen (dann wird die Drucker-IP genommen).
2. **Zertifikat des Druckers bestätigen** → es erscheint ein Fenster mit einer langen Zeichenfolge → **Vertrauen**.
   *(Die Verbindung zum Drucker ist damit verschlüsselt.)*
3. **TimeAdmin-PIN setzen** → TimeAdmin-PIN eingeben → OK.
4. Nur falls es ein eigenes Shared Secret gibt (Punkt 1): **Shared Secret setzen** → eingeben → OK.
5. Den Schalter **Schreibschutz** einschalten, sodass dort **„Schreibzugriff auf die TSE ERLAUBT“** steht,
   und im Fenster **Erlauben** antippen. Das ist für den Kassenbetrieb nötig – ohne diesen Schalter kann
   die Kasse keine Bons unterschreiben.

### ☐ 11. Chip übernehmen

1. **TSE übernehmen …** antippen.
2. Oben müssen alle Punkte einen grünen Haken haben. Steht dort „ECHTE TSE“, ist alles richtig.
3. Häkchen bei *„Die schriftliche Freigabe des Betreibers … liegt vor“* setzen und darunter Ihren Namen und
   das Datum eintragen (Sie sind der Betreiber).
4. Die **Admin-PIN** eingeben – **einmal, sorgfältig, nicht raten**.
5. **Registrierte Kassen lesen** antippen.
6. Erscheint **„KASSE-01“ registrieren**: antippen und mit **Registrieren** bestätigen.
   Steht dort schon „KASSE-01 ist an der TSE registriert“, ist nichts zu tun.
7. **Bereitschaft prüfen** antippen. Unten muss stehen: **„✓ Bereitschaft: Anmeldung und Zeit in Ordnung“**.
8. **Schließen**.

Meldet die Kasse etwas wie *„PIN falsch“*, *„Shared Secret passt nicht“* oder *„anderer Drucker“*:
**nicht weiter probieren**, sondern Ihren Ansprechpartner anrufen.

### ☐ 12. Erster Verkauf und Bon prüfen

Machen Sie den ersten echten Verkauf. Auf dem Bon muss unten stehen:

- **TSE-Daten** mit Seriennummer, Transaktion, Signaturzähler, Start und Ende, Signatur
- ein **QR-Code**

Steht dort **„TSE AUSGEFALLEN“** oder **„MOCK“**: Ansprechpartner anrufen.

Die rote Leiste oben in der Kasse zeigt den Zustand des Chips. Dort sollte *„initialisiert · Self-Test ok ·
Zeit gesetzt“* stehen. *(Die Leiste bleibt sichtbar, bis die Kasse offiziell abgenommen ist.)*

---

## Teil D – Nach dem Umzug

### ☐ 13. Finanzamt informieren (innerhalb von 1 Monat)

Über **Mein ELSTER** → *„Mitteilung über elektronische Aufzeichnungssysteme“*:

- JTL-POS **außer Betrieb** (Datum des letzten Tagesabschlusses)
- Any.POS **in Betrieb** mit derselben TSE (Datum des Umzugs)

Am einfachsten macht das Ihr Steuerberater – geben Sie ihm die Angaben aus Punkt 2.

### ☐ 14. Jeden Tag

- Abends in Any.POS **Abschluss** → **Z-Bon / Tagesabschluss …**.

### ☐ 15. Für den Steuerberater

- **Kassenbuch** → **An Steuerberater senden** (Kassenbuch und DATEV-Datei). Eine E-Mail-Adresse, Berater-
  oder Mandantennummer müssen dafür **nicht** eingetragen sein.
- **Abschluss** → **DSFinV-K-Export …** (das Datenformat, das das Finanzamt bei einer Prüfung verlangt).
- Gelegentlich (z. B. einmal im Jahr, außerhalb der Öffnungszeiten): **Einstellungen → TSE →
  TSE-Daten exportieren …** (braucht die Admin-PIN). Während des Exports kann nicht kassiert werden.

---

## Wenn etwas nicht klappt

| Was Sie sehen | Was Sie tun |
|---|---|
| „TSE nicht erreichbar“ | Ist der Drucker an und im WLAN? Tablet im selben WLAN? Drucker kurz aus- und einschalten. |
| Beim Zertifikat-Bestätigen erscheint ein Fehler | Drucker-IP prüfen (Punkt 2). Hilft das nicht: Ansprechpartner anrufen – **nicht** selbst die Verschlüsselung ausschalten. |
| „Druckerzertifikat hat sich geändert“ | Nicht einfach neu bestätigen – Ansprechpartner anrufen. |
| Import zeigt Fehler | Zeilennummer und Grund notieren, in JTL korrigieren, neu exportieren, erneut einspielen. |
| „PIN falsch“ / „PIN gesperrt“ | Nicht weiter probieren. Gesperrte PINs lassen sich nur mit dem PUK entsperren. |
| „Kasse nicht registriert“ | Punkt 11 wiederholen. |
| Bon zeigt „TSE AUSGEFALLEN“ | Weiter kassieren ist erlaubt, der Ausfall ist auf dem Bon vermerkt. Ursache beheben lassen. |

**Ansprechpartner:** ______________________ **Telefon:** ______________________
