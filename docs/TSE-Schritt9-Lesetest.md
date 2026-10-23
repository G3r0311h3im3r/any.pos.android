# Schritt 9 – Lesetest an der echten TSE (USB)

Diese Anleitung beschreibt den **ersten Kontakt von Any.POS mit der echten Epson-TSE**. Die TSE wird dabei
**nur gelesen**. Es wird nichts geschrieben, es werden keine PINs verwendet und es wird nicht verkauft.

**Ziel:** Nachweis, dass Any.POS die TSE im Drucker **TM-m30III über das USB-Kabel** erreicht, und
Dokumentation des Ist-Zustands der TSE (Seriennummer, Zertifikat, Zähler, offene Vorgänge).

> **Achtung – Produktiv-TSE.** Im Drucker steckt die TSE, mit der JTL-POS täglich kassiert. Deshalb gilt:
> schriftliche Freigabe des Betreibers vorher, nur außerhalb der Öffnungszeiten, JTL-POS ist während des
> Tests nicht in Betrieb, Schreibschutz bleibt eingeschaltet. Bei jeder Unklarheit: **abbrechen**, nicht probieren.

**Dauer:** etwa 30–45 Minuten, davon 10 Minuten eigentlicher Test.

---

## Was passiert – und was nicht

| Passiert | Passiert **nicht** |
|---|---|
| Any.POS liest zwei Auskünfte: TSE-Angaben (`GetStorageInfo`) und die offenen Transaktionen **dieser Kasse** (`GetStartedTransactionList`); die Leiste oben wiederholt die erste Abfrage jede Minute | keine Anmeldung an der TSE, keine PIN, kein Shared Secret |
| ein Testdruck auf dem Bondrucker | keine Transaktion, kein Verkauf, kein Bon mit TSE-Daten |
| die TSE wird für wenige Sekunden „geöffnet“ und sofort wieder freigegeben | keine Registrierung, kein Self-Test, keine Zeiteinstellung, kein Export, kein Löschen |

Ein versehentlich ausgelöster schreibender Befehl wird vom **Schreibschutz** in Any.POS abgefangen, **bevor**
er zum Drucker geht, und im Protokoll vermerkt.

---

## Teil A – Vorher

### ☐ 1. Schriftliche Freigabe des Betreibers

Das Formular auf der letzten Seite ausfüllen und unterschreiben lassen. Ohne Unterschrift kein Test.

### ☐ 2. Zeitpunkt festlegen

- **Außerhalb der Öffnungszeiten**, am besten nach dem Tagesabschluss (Z-Bericht) in JTL-POS.
- In JTL-POS darf **kein Vorgang offen** sein (kein angefangener Bon, kein geparkter Beleg).

### ☐ 3. Material

| Was | Bemerkung |
|---|---|
| **Test-Tablet** (Android 13–15) mit WLAN | am besten **nicht** das JTL-Tablet (siehe Punkt 6) |
| USB-C-Kabel Drucker ↔ Tablet | das Kabel, über das das JTL-Tablet heute lädt und druckt |
| PC mit Projekt Any.POS und `adb` | im selben WLAN wie das Tablet |
| Diese Anleitung, Stift | Ergebnisse in Teil E eintragen |
| Seriennummer der TSE laut JTL-POS | vom JTL-Bon abgelesen (noch zu bestätigen): `1Vo1kLVg5n2Kbj473mBn6PulvdxCXVsgasrLkYOlCE4=`, in Any.POS als Hex `D55A3590…183A5084E` |

### ☐ 4. Tablet mit dem PC verbinden (WLAN-Debugging)

Die USB-Buchse des Tablets ist später vom Drucker belegt, deshalb läuft die Verbindung zum PC über WLAN.

1. Tablet: *Einstellungen → Telefoninfo* → 7× auf **Build-Nummer** tippen (Entwickleroptionen frei).
2. *Entwickleroptionen* → **Debugging über WLAN** einschalten → **Gerät über Kopplungscode koppeln**.
3. Am PC:

        adb pair <IP>:<Kopplungsport>      (6-stelligen Code vom Tablet eingeben)
        adb connect <IP>:<Port>
        adb devices                        (Tablet muss erscheinen)

### ☐ 5. Any.POS installieren und vorbereiten (noch **ohne** Drucker)

1. Am PC: `.\gradlew.bat :app:installRelease`
2. Any.POS öffnen, **Admin** / PIN `1234` → **Anmelden**, sofort **PIN ändern** (*Einstellungen → Benutzer*).
3. *Einstellungen → TSE:* **keine TSE** lassen → **Speichern**.
4. **Keinen Verkauf** in Any.POS machen – weder jetzt noch während des Tests.

---

## Teil B – Aufbau am Drucker

### ☐ 6. JTL-POS vom Drucker trennen

1. In JTL-POS prüfen: kein Vorgang offen (Punkt 2).
2. JTL-POS **beenden** (App schließen).
3. **1 Minute warten**, damit der Drucker die TSE sicher freigegeben hat.
4. Das USB-C-Kabel **vom JTL-Tablet abziehen** und am **Test-Tablet** anstecken. Die TSE bleibt im Drucker.
5. Am Test-Tablet erscheint das Lade-Symbol.

> Muss der Test doch auf dem JTL-Tablet laufen: JTL-POS vorher vollständig beenden (*Einstellungen → Apps →
> JTL-POS → Beenden erzwingen*). Fragt Android beim Anstecken, welche App den Drucker nutzen soll, **Any.POS**
> wählen, aber **nicht** „Immer“ – sonst findet JTL-POS den Drucker danach nicht mehr.

### ☐ 7. Drucker in Any.POS – Testdruck

1. *Einstellungen → Bondrucker & Schublade:* **USB** → oben **Speichern**.
2. **Testdruck** antippen. Android fragt *„Any.POS Zugriff auf … erlauben?“* → **OK**.
3. Es muss ein Zettel „Any.POS Testdruck“ kommen. **Kommt keiner: abbrechen** (Teil D), Ursache klären.

---

## Teil C – Der Lesetest

### ☐ 8. TSE einstellen

*Einstellungen → TSE:*

1. **Epson-TSE** wählen, bei *Verbindung* **USB (über den Drucker)** → oben **Speichern**.
2. Prüfen, dass unten **„Schreibschutz aktiv: nur lesende TSE-Aufrufe“** steht. **Den Schalter nicht anfassen.**
3. **Keine** TimeAdmin-PIN und **kein** Shared Secret eingeben.

Nicht antippen: *Anmeldung testen*, *TSE übernehmen …*, *TSE-Daten exportieren …*

### ☐ 9. Mitschnitt starten (am PC)

    adb logcat -c
    adb logcat -s EscPosTse:* AndroidRuntime:E > schritt9-log.txt

Das Protokoll enthält nur Funktionsnamen, Dauer und Ergebnis – keine PINs, keine Daten der TSE.

### ☐ 10. Verbindung prüfen

1. **Verbindung prüfen (nur lesend)** antippen und bis zu einer Minute warten.
2. **Erfolg:** Es erscheint eine Tabelle (Seriennummer, Zertifikat, Signaturen, …).
   Auch die Leiste oben zeigt jetzt den Zustand der TSE.
3. **Bildschirmfoto** machen (am PC: `adb exec-out screencap -p > schritt9.png`).
4. Werte in **Teil E** übertragen.

Die **Seriennummer** muss mit der aus JTL-POS übereinstimmen. Tut sie das nicht: Ergebnis notieren, nichts
weiter tun.

### Mögliche Meldungen

| Meldung | Bedeutung | Was tun |
|---|---|---|
| Tabelle erscheint | **Test bestanden** | Teil E ausfüllen, dann Teil D |
| „Kein USB-Drucker gefunden“ | Kabel/Strom/Drucker aus | Kabel prüfen, Drucker an? Einmal wiederholen |
| „USB-Berechtigung … nicht erteilt“ | Android-Frage abgelehnt | Testdruck (Punkt 7) wiederholen und **OK** wählen |
| „Drucker antwortet nicht auf die TSE-Freischaltung“ | Drucker kennt den ESC/POS-Weg nicht oder TSE nicht erkannt | **Abbrechen**, Log sichern – Ausweichweg prüfen (siehe unten) |
| „TSE konnte nicht freigeschaltet werden (belegt)“ | TSE noch von JTL-POS gehalten | 2 Minuten warten, **einmal** wiederholen, sonst abbrechen |
| „Keine Antwort … Ausführung unbekannt“ | Antwort fehlt | **Abbrechen**, Log sichern. Bei einem Lesebefehl unkritisch |
| „Schreibender TSE-Aufruf gesperrt: …“ | Schreibschutz hat einen Befehl abgefangen | Notieren. **Schreibschutz nicht ausschalten** |
| irgendetwas mit „PIN“ oder „Secret“ | darf beim Lesen nicht vorkommen | **Abbrechen**, Log sichern |

**Höchstens zwei Versuche.** Mehr bringt nichts und verlängert nur die Zeit, in der JTL-POS nicht kassieren kann.

---

## Teil D – Rückbau (immer, auch nach Abbruch)

### ☐ 11. Any.POS von der TSE lösen

1. *Einstellungen → TSE:* **keine TSE** → **Speichern**.
2. Any.POS schließen. Am PC Mitschnitt mit `Strg+C` beenden.
3. **1 Minute warten.**

### ☐ 12. JTL-POS wieder anschließen

1. USB-C-Kabel vom Test-Tablet abziehen, wieder am **JTL-Tablet** anstecken.
2. JTL-POS starten und in der **TSE-Info** prüfen: TSE erreichbar, keine Fehlermeldung.
3. Meldet JTL-POS einen TSE-Fehler: Drucker **aus- und wieder einschalten**, 1 Minute warten, erneut prüfen.
   Hilft das nicht: Ansprechpartner anrufen, **bevor** am nächsten Tag kassiert wird.

> Einen Testverkauf in JTL-POS nur machen, wenn das mit dem Steuerberater so abgesprochen ist –
> es ist ein echter, signierter Beleg.

---

## Teil E – Ergebnis

**Datum / Uhrzeit:** ______________________ **Durchgeführt von:** ______________________

| Wert (aus der Tabelle in Any.POS) | Ergebnis |
|---|---|
| Verbindung über USB erfolgreich? | ☐ ja ☐ nein |
| serialNumber (hex) | |
| stimmt mit JTL-POS überein? | ☐ ja ☐ nein |
| tseInitializationState | |
| certificateExpirationDate | |
| createdSignatures / remainingSignatures | |
| startedTransactions (offen / max., alle Kassen inkl. JTL-POS) | |
| registeredClients (Anzahl / max.) | |
| hasPassedSelfTest / hasValidTime | |
| softwareVersion | |
| offene Transaktionen (KASSE-01) – erwartet „keine“ oder „nicht lesbar“, weil Any.POS noch nicht registriert ist | |
| Meldungen / Auffälligkeiten | |
| JTL-POS danach in Ordnung? | ☐ ja ☐ nein |

Bildschirmfoto `schritt9.png` und Mitschnitt `schritt9-log.txt` an den Entwickler; die Werte kommen in
`TSE-Plan.md` (Ist-Zustand der Produktiv-TSE).

**Wenn der USB-Weg nicht antwortet:** Ausweichweg ist der Netzwerkzugang des Druckers (ePOS Device XML mit
Zertifikat) bzw. das Epson ePOS SDK. Beides wird erst nach Rücksprache und mit neuer Freigabe getestet.

---

## Freigabe des Betreibers (Schritt 9 – nur lesender Zugriff)

Ich erlaube einen **rein lesenden** Zugriff der Kassensoftware Any.POS auf die TSE in meinem Bondrucker.

- Drucker: Epson TM-m30III
- Seriennummer der TSE (lt. JTL-Bon): 1Vo1kLVg5n2Kbj473mBn6PulvdxCXVsgasrLkYOlCE4=  ☐ geprüft
- Zeitraum: am ______________ von ________ bis ________ Uhr (außerhalb der Öffnungszeiten)
- Es werden nur die TSE-Angaben und die Liste offener Transaktionen gelesen. Es werden **keine** Daten
  geschrieben, **keine** PINs verwendet, **keine** Belege erzeugt und **nichts** gelöscht.
- JTL-POS wird während des Tests nicht zum Kassieren benutzt und danach wieder angeschlossen und geprüft.

Betrieb: ______________________________________________

Name Betreiber: ______________________________  Ort, Datum: ______________________________

Unterschrift: ______________________________

Durchführung durch: ______________________________  Unterschrift: ______________________________
