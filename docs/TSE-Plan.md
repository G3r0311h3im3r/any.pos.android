# TSE-Anbindung (Phase 2) – Plan und Sicherheitsregeln

## Stand

Die Kasse arbeitet gegen die Schnittstelle `de.anypos.tse.Tse`. Vorhanden sind:

| Implementierung | Zweck | Produktiv zulässig |
|---|---|---|
| `NoTse` | keine TSE; jeder Beleg trägt „TSE ausgefallen“ | nein |
| `SimulatedTse` | Entwicklung/Schulung; Belege mit „SIMULATION – KEIN GÜLTIGER BELEG“ | nein |
| `EpsonTse` | Platzhalter, wird in Phase 2 umgesetzt | erst nach Abnahme |

Bereits fertig und getestet: Transaktionsstart bei Vorgangsbeginn, Abschluss mit `processType = Kassenbeleg-V1`
und `processData` nach DSFinV-K, Abbruch als `AVBelegabbruch`, Wiederanlauf offener Transaktionen nach Absturz,
TSE-Daten und QR-Code (V0) auf dem Beleg, Protokollierung von TSE-Fehlern.

## Ablauf Phase 2

| Schritt | Inhalt | Schreibt auf die TSE? |
|---|---|---|
| 2a | Drucker/TSE identifizieren: Modell, Firmware, TSE-Seriennummer, Zertifikatsablauf, Speicherbelegung (`GetStorageInfo`) | **Nein** (nur lesend) |
| 2b | Freigabe durch Betreiber: welche TSE, welche Client-ID, Test oder Produktiv | – |
| 2c | Registrierte Clients lesen (braucht Admin-Anmeldung), Client-ID `KASSE-01` registrieren, Zeit setzen | **Ja** – nur nach Freigabe |
| 2d | Testtransaktionen | **Ja** – nur auf Test-TSE oder nach ausdrücklicher Freigabe |
| 2e | TAR-Export, Fehlerfälle (Ziehen, Speicher voll, Zeitverlust), Abnahme | Admin-Anmeldung; **sperrt die TSE während des Exports** (Spez. S. 45) – nie parallel zum Produktivbetrieb |

Hinweis (22.10.2026, nach den offiziellen Epson-Unterlagen): Die Liste der registrierten Clients und alle
Export-Funktionen sind keine reinen Lesezugriffe, sondern verlangen eine Admin-Anmeldung. Der erste Kontakt
mit der Produktiv-TSE (2a) beschränkt sich deshalb auf `GetStorageInfo`; das Gerät wird danach sofort wieder
freigegeben, damit JTL-POS nicht blockiert wird.

Aufwand Software (geschätzt): 2a ca. 0,5 Tag, 2c–2e ca. 2–4 Tage, sobald Drucker, TSE, Epson ePOS SDK und
Dokumentation vorliegen. Der größte Zeitanteil ist der Test an der echten Hardware.

## Produktiv-TSE vor Ort (Ist-Zustand)

| Angabe | Wert | Quelle / Stand |
|---|---|---|
| Drucker (TSE-Host) | Epson TM-m30III, per USB-C am Tablet (lädt das Tablet über PD) | Betreiber, 22.10.2026 |
| TSE | USB-TSE, steckt im Drucker | Betreiber, 22.10.2026 |
| Seriennummer (Base64, wie auf JTL-Bons) | `1Vo1kLVg5n2Kbj473mBn6PulvdxCXVsgasrLkYOlCE4=` | **vom Foto eines JTL-Bons abgelesen, noch zu bestätigen** (1/l/I und O/0 auf dem Foto nicht sicher unterscheidbar) |
| Seriennummer (Hex, wie Any.POS sie anzeigt) | `D55A3590B560E67D8A6E3E3BDE6067E8FBA5BDDC425D5B206ACACB9183A5084E` | daraus umgerechnet (32 Bytes), ebenfalls zu bestätigen |
| Zertifikatsablauf, Signaturzähler, Speicher, offene Transaktionen | – | folgt aus dem Lesetest (Schritt 9, `GetStorageInfo`) |

Bestätigung der Seriennummer: in JTL-POS unter *TSE-Info* bzw. beim Lesetest (Anzeige „serialNumber (hex)“).

Hinweis vom selben Bon („TSE Fehlerbehebungsbeleg“): JTL-POS hatte am 22.10.2026 von 11:01 bis 13:29 keine
Verbindung zur TSE (betroffen: Bon R-0086). Der Ausfall gehört zur Dokumentation des Betreibers für JTL-POS.

## Regeln

1. Vor jedem schreibenden TSE-Aufruf liegt eine schriftliche Freigabe des Betreibers vor.
2. Keine Testverkäufe auf einer TSE, die gleichzeitig produktiv von JTL-POS genutzt wird, ohne Klärung mit
   Steuerberater (siehe offene Fragen).
3. PINs/PUK der TSE (Admin, TimeAdmin) werden nie im Code oder Repository abgelegt.
4. Bei unklarem TSE-Ergebnis wird keine Transaktion blind wiederholt; der Vorgang wird als Ausfall markiert.
