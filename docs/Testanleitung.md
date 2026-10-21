# Any.POS – Anleitung für Testerinnen und Tester

Vielen Dank fürs Testen! Diese Version ist eine **Testversion**. Sie ist **nicht** für den echten
Kassenbetrieb zugelassen (keine zertifizierte TSE angebunden). Bitte keine echten Verkäufe damit erfassen.

## 1. Voraussetzungen

- Android-Tablet (oder Smartphone) mit **Android 13, 14 oder 15**
- Querformat empfohlen; Bondrucker ist **nicht** nötig

## 2. Installation

1. Die Datei `AnyPOS-<Version>-test.apk` auf das Gerät laden (E-Mail, Cloud, USB).
2. Datei antippen. Android fragt, ob die App (z. B. „Dateien“ oder „Chrome“) **unbekannte Apps
   installieren** darf → *Einstellungen* → erlauben → zurück → *Installieren*.
3. Falls Google Play Protect warnt („Unbekannte App“): *Trotzdem installieren*. Die App ist nicht im
   Play Store, daher diese Meldung.

**Updates:** Neue Testversionen einfach darüber installieren – die Testdaten bleiben erhalten.

## 3. Erste Schritte

1. App öffnen → Benutzer **Admin**, PIN **1234** → *Anmelden*.
2. *Einstellungen* → **TSE: „Simulation (nur Test!)“** wählen → oben *Speichern*.
   (Ohne diese Einstellung zeigt jeder Beleg „TSE ausgefallen“ – das ist korrektes Verhalten, aber
   zum Testen ist die Simulation übersichtlicher.)
3. Optional Firmendaten (Name, Straße, PLZ, Ort, Steuernummer) eintragen → *Speichern*.
4. Artikel anlegen: *Artikel → Neuer Artikel* – oder eine Beispieldatei unter *Import* laden
   (`jtl-items-v1-beispiel.csv`, liegt dieser Anleitung bei).

## 4. Was bitte testen?

| Bereich | Probieren Sie … |
|---|---|
| Verkauf | Artikel antippen, Menge +/−, Rabatt, Preis ändern, *Bar* (mit Rückgeld) und *Karte* |
| Splitzahlung | *Aufteilen*: Teil bar, Rest Karte |
| Gutscheine | *Gutschein verkaufen* (z. B. 50 €), dann bei einem neuen Einkauf *Gutschein einlösen* mit der Nummer vom Beleg |
| Belege | *Belege*: Beleg öffnen, *Rückgabe* einzelner Artikel, *Stornieren* |
| Kassenbuch | *Kassenbuch*: Einzahlung/Auszahlung buchen, *Kassenbuch teilen* |
| Tagesabschluss | *Abschluss → Z-Bon*: gezähltes Bargeld eingeben, Z-Bon prüfen |
| Export | *Abschluss → DSFinV-K-Export* (nach einem Z-Bon) |
| Datensicherung | *Einstellungen → Datensicherung → Jetzt sichern* |

Bitte auch „Fehlbedienungen“ ausprobieren (falsche PIN, leerer Warenkorb, Gutschein zweimal einlösen …) –
die Kasse soll dann verständliche Meldungen zeigen und nie abstürzen.

## 5. Fehler melden

Bitte jeweils angeben:
- **Was** haben Sie gemacht (Schritte), **was** ist passiert, **was** hätten Sie erwartet
- **Bildschirmfoto** (Ein/Aus + Leiser gleichzeitig)
- Gerät und Android-Version (*Einstellungen → Über das Tablet*)

Meldung an: GitHub-Issues im Projekt oder direkt an den Ansprechpartner, der Ihnen die App geschickt hat.

## 6. Bekannte Einschränkungen dieser Testversion

- Keine echte TSE (Epson-Anbindung folgt) – Belege sind als „SIMULATION – KEIN GÜLTIGER BELEG“ markiert
- Bondruck/Schublade nur mit Epson-Drucker per USB testbar
- Kartenzahlung ist nur eine Buchung – ein Kartenterminal wird nicht angesteuert
