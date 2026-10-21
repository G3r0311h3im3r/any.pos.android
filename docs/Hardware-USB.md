# Hardware: Tablet, Bondrucker, Schublade, TSE

## Zielaufbau

```text
Netzteil ──► Epson-Bondrucker ──(Kick-Kabel RJ12)──► Kassenschublade
                    │
                    │ USB (Daten; je nach Modell auch Laden des Tablets)
                    ▼
             Android-Tablet (13–15)
                    │
             TSE: im/am Epson-Drucker, Zugriff über den Drucker (Phase 2)
```

- **Schublade:** wird ausschließlich über den Drucker geöffnet (ESC/POS `ESC p`, Pin 2 Standard, Pin 5 wählbar).
  Die App sendet den Impuls mit dem Beleg bei Barzahlung, bei Ein-/Auszahlungen und über „Lade öffnen“
  (wird protokolliert).
- **Kundendisplay:** nicht vorgesehen.
- **Barcodescanner:** USB-HID/Bluetooth-Scanner im Tastaturmodus mit Enter-Suffix funktionieren ohne Einrichtung.

## Kritischer Punkt: Laden und USB-Daten über **einen** Anschluss

Das Tablet hat in der Regel nur einen USB-C-Anschluss. Damit es gleichzeitig **lädt** und mit dem Drucker
**kommuniziert**, gibt es zwei mögliche Konstellationen – welche vorliegt, hängt vom konkreten Druckermodell ab:

| Variante | Wer ist USB-Host? | Software-Zugriff | Voraussetzung |
|---|---|---|---|
| A: Drucker lädt das Tablet (z. B. Epson TM-m30-Serie mit Ladefunktion) | je nach Modell Drucker *oder* Tablet | Android USB-Host **oder** Android Open Accessory | Epson-Angabe zum Modell |
| B: USB-C-Hub mit Power-Delivery-Durchleitung | Tablet | Android USB-Host (in der App implementiert) | Tablet muss „Laden im Host-Modus“ unterstützen |

**Aktueller Stand der App:** direkter Zugriff über die Android-USB-Host-API (Variante B bzw. A mit Tablet als Host).
Arbeitet der Drucker als Host (Accessory-Modus), wird für Druck **und** TSE das Epson ePOS SDK verwendet –
das ist ohnehin für die TSE geplant. Die Druckerschnittstelle ist dafür austauschbar (`PrinterTransport`).

**Benötigt zur Klärung:** exakte Modellbezeichnung des Druckers (Typenschild, z. B. „TM-m30III-H“ oder
„TM-m30II-F“) und des Tablets.

## USB-Berechtigung

Beim ersten Anstecken fragt Android, ob Any.POS auf den Drucker zugreifen darf. **„Immer für dieses Gerät“**
wählen, dann ist die Berechtigung dauerhaft (Epson-Vendor-ID 0x04B8 ist in der App hinterlegt).

## Einstellungen in der App

*Einstellungen → Bondrucker & Schublade*: Modus **USB**, 48 Zeichen/Zeile (80 mm), Kick-Pin 2.
„Testdruck“ prüft Umlaute/€-Zeichen (Zeichensatz PC858) und QR-Code, „Schublade testen“ den Kick.

## Bekannte Fehlerbilder

| Symptom | Ursache / Abhilfe |
|---|---|
| „Kein USB-Drucker gefunden“ | Kabel/Hub prüfen; Drucker eingeschaltet? Bei Variante A ggf. Accessory-Modus → ePOS SDK nötig |
| „USB-Schnittstelle belegt“ | Andere App (z. B. Epson-Utility) greift zu → schließen |
| Umlaute falsch | Drucker-Codepage-Einstellung prüfen; App nutzt Tabelle 19 (PC858) |
| Schublade öffnet nicht | Kick-Pin 5 probieren; Schubladenkabel (24 V Epson-Standard) prüfen |
