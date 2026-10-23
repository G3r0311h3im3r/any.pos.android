# Umzug von JTL-POS zu Any.POS – Schritt für Schritt

Diese Anleitung führt Sie durch den Wechsel Ihrer Kasse von **JTL-POS** zu **Any.POS**.
Sie brauchen dafür keine IT-Kenntnisse. Arbeiten Sie die Punkte der Reihe nach ab und haken Sie ab,
was erledigt ist.

**Das Wichtigste vorab**

- Ihr Epson-Bondrucker bleibt. Im Drucker steckt die **TSE** – das ist der kleine Sicherheits-Chip
  (meist ein USB-Stick in einer Buchse am Drucker), der jeden Verkauf fälschungssicher „unterschreibt“.
  Any.POS nutzt **denselben Chip** weiter.
- **Der Chip bleibt im Drucker stecken.** Stecken Sie ihn nie ans Tablet um – dort funktioniert er nicht.
  Das Tablet spricht den Chip immer über den Drucker an.
- Den Chip **niemals zurücksetzen, neu einrichten oder Daten darauf löschen**. Sonst gehen Daten verloren,
  die Sie 10 Jahre aufbewahren müssen.
- **PINs nie raten.** Nach drei falschen Versuchen sperrt sich der Chip.
- **JTL-POS bleibt bis zum Schluss installiert.** Erst wenn der erste Bon mit Any.POS stimmt, wird JTL-POS
  vom Chip abgemeldet. Klappt etwas nicht, kassieren Sie einfach mit JTL-POS weiter.
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
- Seriennummer der TSE und Ablaufdatum des Zertifikats (stehen in JTL-POS in der TSE-Info und auf jedem Bon)
- **Wie ist der Drucker mit der Kasse verbunden?** Kreuzen Sie an – danach richten sich die Punkte 9 und 10:

| ☐ | Variante | Woran Sie es erkennen |
|---|---|---|
| ☐ | **USB** | Ein **USB-Kabel** führt vom Drucker direkt zum Tablet (oder zu einem Adapter am Tablet). |
| ☐ | **Netzwerk** | Der Drucker hängt mit einem **Netzwerkkabel** am Router oder ist im **WLAN**; zum Tablet führt kein Kabel. |

Nur bei **Netzwerk** zusätzlich die **IP-Adresse des Druckers** notieren: Drucker ausschalten, die Taste
**Feed** (Papiervorschub) gedrückt halten und dabei einschalten. Es druckt ein Statuszettel – darauf steht
die „IP Address“, z. B. `192.168.1.50`.

### ☐ 2a. Nur bei USB: Laden des Tablets prüfen

Das Tablet hat meist nur **eine** Buchse. Es muss über dieselbe Verbindung **laden** und **drucken** können.

- **Drucker TM-m30III (oder anderer Drucker mit Ladefunktion):** Ein **USB-C-Kabel** vom Drucker zum Tablet
  genügt – der Drucker lädt das Tablet dabei mit. **Kein weiterer Adapter nötig.** Lassen Sie die Verkabelung
  so, wie sie mit JTL-POS funktioniert hat.
- **Drucker ohne Ladefunktion:** Sie brauchen einen **USB-C-Hub mit Ladeanschluss** (auf der Packung steht
  „PD“ oder „Power Delivery“): Ladekabel des Tablets → Ladeanschluss des Hubs, USB-Kabel des Druckers → eine
  USB-Buchse des Hubs, Hub → Buchse des Tablets.

**Prüfen:** Tablet mit dem Drucker verbinden, Drucker einschalten – oben am Tablet muss das Lade-Symbol erscheinen.

### ☐ 3. Any.POS auf dem Tablet installieren

1. Die Datei `AnyPOS-….apk` auf das Tablet kopieren und antippen. Android fragt, ob Apps aus dieser Quelle
   installiert werden dürfen – mit **Zulassen** bestätigen, dann **Installieren**.
2. Any.POS öffnen, **Admin** antippen, PIN **1234** eingeben, **Anmelden**.
3. Sofort die PIN ändern: **Einstellungen** → ganz unten bei *Benutzer* → **PIN ändern**.

JTL-POS bleibt installiert und wird bis zum Umzugstag ganz normal weiter benutzt.

### ☐ 4. Any.POS vorbereiten (geht ohne Drucker und ohne Chip)

In **Einstellungen**:

- **Unternehmen:** Firmenname, Straße, PLZ, Ort, Steuernummer oder USt-IdNr. eintragen → oben **Speichern**.
  Diese Angaben stehen auf jedem Bon und sind Pflicht.
- **Datensicherung:** **Sicherungsordner wählen** (z. B. USB-Stick oder Cloud) und **Passwort festlegen**.
  Das Passwort gut aufbewahren – ohne Passwort lässt sich eine Sicherung nicht zurückholen.
- **TSE:** vorerst auf **keine TSE** lassen.

### ☐ 5. Artikel in Any.POS anlegen

Artikel aus JTL-POS werden **nicht** übernommen. Legen Sie die Artikel in Any.POS neu an:

1. Links **Artikel** → **Neuer Artikel**.
2. Name, Bruttopreis und **Steuersatz (7 % oder 19 %)** eintragen. Preise und Steuersätze sehen Sie in
   JTL-POS nach, solange es noch installiert ist. Im Zweifel beim Steuersatz den Steuerberater fragen.
3. Speichern.

Legen Sie alle Artikel an, die Sie am ersten Tag verkaufen wollen – am besten schon vor dem Umzugstag.
Für den Test am Umzugstag (Punkt 12) selbst genügt ein einziger Artikel.
Bisherige Verkäufe werden nicht übertragen – die bleiben in den JTL-POS-Sicherungen aus Punkt 7.

---

## Teil B – Am Umzugstag: JTL-POS abschließen (noch nicht abmelden)

JTL-POS bleibt in diesem Teil **installiert und am Chip angemeldet**. Klappt der Umzug nicht, kassieren Sie
einfach mit JTL-POS weiter (Punkt 13, Weg B).

### ☐ 6. Letzter Tag mit JTL-POS

1. Letzten Verkauf in JTL-POS abschließen. **Kein Vorgang darf offen sein** (kein angefangener oder
   geparkter Bon).
2. **Tagesabschluss (Z-Bericht)** in JTL-POS machen.

### ☐ 7. Daten aus JTL-POS sichern (10 Jahre aufbewahren!)

In JTL-POS exportieren:

- **DSFinV-K-Export** für den **gesamten** Zeitraum, in dem Sie JTL-POS genutzt haben
- **TSE-Export (TAR-Datei)** – dabei **„Daten auf der TSE löschen“ NICHT anhaken**
- **Sicherung der JTL-POS-Daten** (Backup-Funktion von JTL-POS)

Alles an **zwei Orten** speichern (z. B. USB-Stick und Cloud) und dem Steuerberater sagen, wo es liegt.

### ☐ 8. JTL-POS schließen

JTL-POS **beenden, nicht deinstallieren**: die Taste für die zuletzt benutzten Apps antippen und JTL-POS
wegwischen. Der Drucker mit dem Chip kann immer nur von **einer** App gleichzeitig benutzt werden.

---

## Teil C – Am Umzugstag: Any.POS mit dem Chip verbinden

Arbeiten Sie bei den Punkten 9 und 10 nur den Teil Ihrer Variante aus Punkt 2 ab (**USB** *oder*
**Netzwerk**). Der Chip bleibt dabei im Drucker.

### ☐ 9. Drucker in Any.POS eintragen

**Variante USB**

1. Drucker einschalten und wie in Punkt 2a mit dem Tablet verbinden.
2. **Einstellungen → Bondrucker & Schublade:** **USB** wählen → oben **Speichern**.
3. Android fragt *„Any.POS Zugriff auf … erlauben?“* → **OK**. Das Häkchen bei *„Immer für dieses Gerät“*
   **noch nicht** setzen – das kommt erst in Punkt 13, wenn feststeht, dass Any.POS bleibt.
   *(Kommt die Frage nicht, Drucker-Kabel einmal ab- und wieder anstecken.)*
4. **Testdruck**. Es muss ein Zettel kommen.

**Variante Netzwerk**

Das Tablet muss im **selben Netzwerk (WLAN)** wie der Drucker sein.

1. **Einstellungen → Bondrucker & Schublade:** **Netzwerk** wählen, bei *Netzwerkdrucker IP* die IP-Adresse
   vom Statuszettel eintragen → oben **Speichern**.
2. **Testdruck**. Es muss ein Zettel kommen.

### ☐ 10. Chip einrichten

**Einstellungen → TSE:**

1. **Epson-TSE** antippen.
2. Bei *Verbindung* dieselbe Variante wie beim Drucker wählen:
    - **Variante USB:** **USB (über den Drucker)** → oben **Speichern**. Mehr ist hier nicht einzustellen –
      kein Host, kein Zertifikat.
    - **Variante Netzwerk:** **Netzwerk** → oben **Speichern**. Das Feld *TSE-Host* leer lassen (dann wird
      die Drucker-IP genommen). Dann **Zertifikat des Druckers bestätigen** → es erscheint ein Fenster mit
      einer langen Zeichenfolge → **Vertrauen**. *(Die Verbindung zum Drucker ist damit verschlüsselt.)*
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
5. **Registrierte Kassen lesen** antippen. In der Liste steht auch die Kasse von JTL-POS (zum Beispiel
   „JTL-POS-1“). Das ist richtig – **nicht entfernen**.
6. Erscheint **„KASSE-01“ registrieren**: antippen und mit **Registrieren** bestätigen.
   Steht dort schon „KASSE-01 ist an der TSE registriert“, ist nichts zu tun.
7. **Bereitschaft prüfen** antippen. Unten muss stehen: **„✓ Bereitschaft: Anmeldung und Zeit in Ordnung“**.
8. **Schließen**.

Meldet die Kasse etwas wie *„PIN falsch“*, *„Shared Secret passt nicht“* oder *„anderer Drucker“*:
**nicht weiter probieren**, sondern Ihren Ansprechpartner anrufen.

### ☐ 12. Inbetriebnahmetest: ein Bon und sein Storno

Mit einem kleinen Testverkauf prüfen Sie, ob Any.POS mit dem Chip richtig unterschreibt. Der Verkauf wird
danach storniert, sodass kein Umsatz bleibt. Beide Bons sind echte, unterschriebene Belege und bleiben
gespeichert. Das ist so gewollt.

1. Einen günstigen Artikel (zum Beispiel um 1 €) in den Warenkorb legen und **bar** bezahlen.
2. Den Bon prüfen. Unten muss stehen:
    - **TSE-Daten** mit Seriennummer, Transaktion, Signaturzähler, Start und Ende, Signatur
    - ein **QR-Code**

   Steht dort **„TSE AUSGEFALLEN“** oder **„MOCK“**: weiter mit Punkt 13, **Weg B**.
3. Links **Belegjournal** öffnen, den Testbon antippen → **Storno** → bestätigen. Es kommt ein Stornobon,
   ebenfalls mit TSE-Daten und QR-Code.
4. Auf einem Zettel notieren: Datum, Uhrzeit, Nummer des Testbons und des Stornobons und den Vermerk
   **„Inbetriebnahmetest“**. Zettel und beide Bons für den Steuerberater aufheben (Punkt 16).

Die rote Leiste oben in der Kasse zeigt den Zustand des Chips. Dort sollte *„initialisiert · Self-Test ok ·
Zeit gesetzt“* stehen. *(Die Leiste bleibt sichtbar, bis die Kasse offiziell abgenommen ist.)*

### ☐ 13. Umziehen oder zurück

**Weg A – alles in Ordnung** (Testbon und Stornobon mit TSE-Daten und QR-Code):

1. Nur bei **USB**: Drucker-Kabel einmal ab- und wieder anstecken. Bei der Android-Frage jetzt das Häkchen
   bei **„Immer für dieses Gerät“** setzen → **OK**.
2. **JTL-POS vom Chip abmelden** (nach der Anleitung von JTL bzw. Ihres Einrichters). Danach JTL-POS
   **nicht mehr zum Kassieren benutzen**.
3. JTL-POS **nicht deinstallieren** – siehe Punkt 17.

**Weg B – etwas stimmt nicht:**

1. **Nicht weiter probieren** und nichts am Chip ändern.
2. Any.POS schließen (aus den zuletzt benutzten Apps wegwischen).
3. JTL-POS öffnen. Fragt Android nach dem USB-Zugriff: **OK**. In JTL-POS die **TSE-Info** prüfen –
   der Chip muss erreichbar sein.
4. Mit JTL-POS ganz normal weiter kassieren und den Ansprechpartner anrufen. Sagen Sie ihm, dass
   Any.POS als „KASSE-01“ am Chip angemeldet ist und welche Bons dabei entstanden sind.

---

## Teil D – Nach dem Umzug

### ☐ 14. Finanzamt informieren (innerhalb von 1 Monat)

Über **Mein ELSTER** → *„Mitteilung über elektronische Aufzeichnungssysteme“*:

- JTL-POS **außer Betrieb** (Datum des letzten Tagesabschlusses)
- Any.POS **in Betrieb** mit derselben TSE (Datum des Umzugs)

Am einfachsten macht das Ihr Steuerberater – geben Sie ihm die Angaben aus Punkt 2.

### ☐ 15. Jeden Tag

- Abends in Any.POS **Abschluss** → **Z-Bon / Tagesabschluss …**.

### ☐ 16. Für den Steuerberater

- **Kassenbuch** → **An Steuerberater senden** (Kassenbuch und DATEV-Datei). Eine E-Mail-Adresse, Berater-
  oder Mandantennummer müssen dafür **nicht** eingetragen sein.
- **Abschluss** → **DSFinV-K-Export …** (das Datenformat, das das Finanzamt bei einer Prüfung verlangt).
- Gelegentlich (z. B. einmal im Jahr, außerhalb der Öffnungszeiten): **Einstellungen → TSE →
  TSE-Daten exportieren …** (braucht die Admin-PIN). Während des Exports kann nicht kassiert werden.
- Einmalig nach dem Umzug: den Zettel und die Bons vom Inbetriebnahmetest (Punkt 12) und den Ablageort der
  JTL-POS-Daten (Punkt 7).

### ☐ 17. JTL-POS deinstallieren – erst mit Freigabe

Erst wenn der Steuerberater die Daten aus Punkt 7 hat, sie öffnen kann und zustimmt, darf JTL-POS vom Tablet.
Bis dahin bleibt die App installiert, wird aber nicht mehr benutzt.

---

## Wenn etwas nicht klappt

| Was Sie sehen | Was Sie tun |
|---|---|
| „TSE nicht erreichbar“ – **USB** | Drucker an? Kabel (und ggf. Hub) fest eingesteckt? Steckt der Chip im Drucker (nicht am Tablet)? Drucker kurz aus- und einschalten; kommt die Android-Frage nach dem USB-Zugriff: **OK** (nach Punkt 13 mit **Immer**). |
| „TSE nicht erreichbar“ – **Netzwerk** | Ist der Drucker an und im WLAN? Tablet im selben WLAN? Drucker kurz aus- und einschalten. |
| Tablet lädt nicht mehr (USB) | Ist der Drucker eingeschaltet? USB-C-Kabel an beiden Enden fest? Bei Hub: Ladekabel muss am **Ladeanschluss des Hubs** stecken (Punkt 2a). |
| Beim Zertifikat-Bestätigen erscheint ein Fehler (nur Netzwerk) | Drucker-IP prüfen (Punkt 2). Hilft das nicht: Ansprechpartner anrufen – **nicht** selbst die Verschlüsselung ausschalten. |
| „Druckerzertifikat hat sich geändert“ (nur Netzwerk) | Nicht einfach neu bestätigen – Ansprechpartner anrufen. |
| „PIN falsch“ / „PIN gesperrt“ | Nicht weiter probieren. Gesperrte PINs lassen sich nur mit dem PUK entsperren. |
| „Kasse nicht registriert“ | Punkt 11 wiederholen. |
| JTL-POS findet nach Weg B den Drucker nicht | Drucker-Kabel ab- und wieder anstecken, bei der Android-Frage JTL-POS erlauben. Hilft das nicht: Android-*Einstellungen → Apps → Any.POS → Standardmäßig öffnen → Standardeinstellungen löschen*, dann erneut anstecken. |
| Bon zeigt „TSE AUSGEFALLEN“ | Weiter kassieren ist erlaubt, der Ausfall ist auf dem Bon vermerkt. Ursache beheben lassen. |

**Ansprechpartner:** ______________________ **Telefon:** ______________________
