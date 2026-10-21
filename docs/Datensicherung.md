# Datensicherung

*Einstellungen → Datensicherung* (nur Administratoren)

## Einrichten (einmalig, empfohlen)

1. **Sicherungsordner wählen** – z. B. ein USB-Stick (über USB-Hub), ein Netzlaufwerk oder ein
   Cloud-Ordner (Google Drive, Nextcloud …), der in der Android-Dateiauswahl erscheint.
2. **Passwort festlegen** (mind. 8 Zeichen). Das Passwort **außerhalb der Kasse aufbewahren** – ohne
   Passwort ist keine Wiederherstellung möglich, auch nicht durch den Hersteller.

Danach wird **nach jedem Tagesabschluss automatisch** eine Sicherung in den Ordner geschrieben.
Schlägt sie fehl (Ordner nicht erreichbar), erscheint ein Hinweis beim Abschluss. Die Einstellungsseite
zeigt den Zeitpunkt der letzten Sicherung rot an, wenn sie älter als 7 Tage ist.

**Automatischer Monatsexport:** Beim ersten Tagesabschluss eines neuen Monats legt die Kasse für jeden
vollständig vergangenen, noch nicht exportierten Monat zusätzlich diese Dateien im selben Ordner ab:

| Datei | Inhalt |
|---|---|
| `Kassenbuch_<Kasse>_<JJJJ-MM>.csv` | Kassenbuch des Monats (Excel-lesbar) |
| `EXTF_Buchungsstapel_<Kasse>_<JJJJ-MM>.csv` | DATEV-Buchungsstapel (nur wenn DATEV-Einstellungen vollständig) |
| `DSFinV-K_<Kasse>_<JJJJ-MM>.zip` | DSFinV-K-Export aller Z-Bons des Monats |

Wird der Ordner z. B. mit einem Cloud-Ordner des Steuerberaters synchronisiert, liegen die Monatsdaten
dort ohne weiteres Zutun. Fehlt etwas (DATEV-Einstellungen, Firmendaten), zeigt der Tagesabschluss einen
Hinweis; Kassenbuch und DSFinV-K werden trotzdem abgelegt.

Ohne eingerichteten Ordner erstellt **Jetzt sichern** eine einzelne Sicherungsdatei an einem frei
gewählten Ort (Passwort wird dann abgefragt).

## Was wird gesichert?

Die vollständige Kassendatenbank: Belege, Abschlüsse, Kassenbuch, Gutscheine, Artikel, Benutzer,
Einstellungen und Protokoll. Dateiname: `AnyPOS_<Kasse>_<Datum>_<Uhrzeit>.anyposbak`.

Verschlüsselung: AES-256-GCM, Schlüssel aus dem Passwort (PBKDF2, 210.000 Iterationen). Manipulierte
oder unvollständige Dateien werden beim Wiederherstellen erkannt und abgelehnt.

> Die Sicherung ersetzt **nicht** die Aufbewahrung der DSFinV-K- und TSE-Exporte. Diese regelmäßig
> (z. B. monatlich) exportieren und 10 Jahre aufbewahren.

## Wiederherstellen (z. B. neues Tablet nach Defekt)

1. Any.POS auf dem neuen Tablet installieren und als Admin anmelden (PIN 1234 bei Neuinstallation).
2. *Einstellungen → Datensicherung → Wiederherstellen …* → Sicherungsdatei wählen → Passwort.
3. Bestätigen. Die App startet neu und arbeitet mit dem gesicherten Datenstand weiter.

Die bisherige Datenbank des Geräts wird **nicht gelöscht**, sondern als
`anypos.db.vor-wiederherstellung-<Zeitstempel>` aufbewahrt. Die Wiederherstellung wird im Protokoll
vermerkt.

**Achtung:** Belege, die nach dem Zeitpunkt der Sicherung entstanden sind, fehlen im wiederhergestellten
Stand. Deshalb immer die **neueste** Sicherung verwenden. Eine Sicherung aus einer neueren App-Version
wird abgelehnt – zuerst die App aktualisieren.

Nach einem Gerätewechsel muss die automatische Sicherung neu eingerichtet werden (Ordner und Passwort),
weil das Passwort gerätegebunden verschlüsselt gespeichert wird.
