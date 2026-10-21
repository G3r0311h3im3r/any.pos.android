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
| 2a | Drucker/TSE identifizieren: Modell, Firmware, TSE-Seriennummer, Zertifikatsablauf, Speicherbelegung, registrierte Clients | **Nein** (nur lesend) |
| 2b | Freigabe durch Betreiber: welche TSE, welche Client-ID, Test oder Produktiv | – |
| 2c | Client-ID `KASSE-01` registrieren, Zeit setzen | **Ja** – nur nach Freigabe |
| 2d | Testtransaktionen | **Ja** – nur auf Test-TSE oder nach ausdrücklicher Freigabe |
| 2e | TAR-Export, Fehlerfälle (Ziehen, Speicher voll, Zeitverlust), Abnahme | Export nur lesend |

Aufwand Software (geschätzt): 2a ca. 0,5 Tag, 2c–2e ca. 2–4 Tage, sobald Drucker, TSE, Epson ePOS SDK und
Dokumentation vorliegen. Der größte Zeitanteil ist der Test an der echten Hardware.

## Regeln

1. Vor jedem schreibenden TSE-Aufruf liegt eine schriftliche Freigabe des Betreibers vor.
2. Keine Testverkäufe auf einer TSE, die gleichzeitig produktiv von JTL-POS genutzt wird, ohne Klärung mit
   Steuerberater (siehe offene Fragen).
3. PINs/PUK der TSE (Admin, TimeAdmin) werden nie im Code oder Repository abgelegt.
4. Bei unklarem TSE-Ergebnis wird keine Transaktion blind wiederholt; der Vorgang wird als Ausfall markiert.
