# Kundendisplay

Ein zweites Tablet oder Smartphone zeigt dem Kunden Warenkorb, Summe, Trinkgeldabfrage und Rückgeld.
Es braucht **keine App**: Das Display öffnet eine Webseite, die die Kasse im WLAN bereitstellt.

```
Kassen-Tablet (Any.POS, Port 8090) ──WLAN──► Display-Gerät (Chrome, Safari …)
                                              http://<IP der Kasse>:8090/d/<Schlüssel>
```

## Einrichten

1. Kasse: *Einstellungen → Kundendisplay* → **ein** → oben **Speichern**.
2. Es erscheinen Adresse und QR-Code. Mit dem Display-Gerät den QR-Code scannen (oder **Adresse drucken** und den
   Bon scannen bzw. die Adresse abtippen).
3. Display-Gerät:
   - im **selben WLAN** wie die Kasse,
   - Seite im Browser öffnen → **Vollbild**: oben rechts „⛶ Vollbild“ oder einfach einmal auf die Seite tippen
     (Browser erlauben Vollbild nur nach einer Berührung; nach dem Neuladen der Seite einmal erneut tippen).
     iPhone: Safari kennt kein Vollbild für Webseiten → *Teilen → Zum Home-Bildschirm*, dann von dort öffnen,
   - Hoch- und Querformat werden unterstützt (Schriftgrößen richten sich nach der kürzeren Bildschirmseite;
     geprüft für Handy 360×640 bis Tablet 1280×800, jeweils hoch und quer),
   - Bildschirm-Zeitlimit auf das Maximum bzw. „beim Laden aktiv lassen“ (die Seite kann den Bildschirm nicht selbst
     wach halten – das erlauben Browser nur über HTTPS),
   - optional *Bildschirm anheften* (Android) bzw. *Geführter Zugriff* (iOS), damit der Kunde die Seite nicht verlässt.
4. Bricht das WLAN kurz ab, zeigt das Display „Verbindung unterbrochen“ und verbindet sich selbst wieder.

**Adresse für Displays:** Leer = IP-Adresse der Kasse im WLAN. Ändert der Router die IP der Kasse, ändert sich die
Adresse – im Router der Kasse eine feste IP zuweisen. Das Feld ist auch für Sonderfälle da (z. B. Weiterleitung).

**Neuer Schlüssel:** sperrt alle bisher gekoppelten Displays aus (z. B. wenn ein Gerät abhandenkommt).

## Was angezeigt wird

| Zustand | Anzeige |
|---|---|
| kein Vorgang | Firmenname, „Willkommen“ |
| Warenkorb | Positionen (Menge, Preis, Rabatt), zuletzt gewählte hervorgehoben, Summe |
| Trinkgeldabfrage | Tasten „Kein Trinkgeld“, Prozentstufen, „Aufrunden auf …“ mit Betrag und neuer Summe |
| bezahlt | Summe, darin Trinkgeld, gegeben, **Rückgeld**; nach 15 s zurück zu „Willkommen“ |

## Sicherheit

- Der Webserver läuft nur, wenn das Kundendisplay eingeschaltet ist. Er beantwortet nur Adressen mit dem richtigen
  Schlüssel (128 Bit, zufällig); alles andere: „nicht gefunden“.
- Das Display zeigt nur, was der Kunde ohnehin sieht. **Einzige Eingabe** ist die Trinkgeldauswahl – sie gilt nur für
  die gerade gestellte Frage und wird erst wirksam, wenn der Kassierer sie übernimmt.
- Keine Verbindung zur TSE, keine Daten werden gespeichert.
- Verbindung unverschlüsselt (HTTP im eigenen WLAN). Für das Kunden-WLAN eines Ladens ein getrenntes Gastnetz nutzen.

## Rechtliches

- Ein Kundendisplay ist in Deutschland nicht vorgeschrieben.
- Es **ersetzt nicht die Belegausgabe** (bloßes Anzeigen ist kein Beleg). Der Bon wird weiter gedruckt.

## Vorbereitung eBon (digitaler Beleg ab 2028)

Stand 22.10.2026: Das Bundeskabinett hat am 23.09.2026 einen Gesetzentwurf beschlossen. Danach soll die Pflicht zum
Papierbon ab **1.1.2028** entfallen; stattdessen ein **digitaler Beleg**, z. B. als **QR-Code auf dem Kassendisplay**.
Der Kunde behält den Anspruch auf einen Papierbon. Bundestag und Bundesrat stehen aus; technische Vorgaben (Format,
Schnittstellen, Datenschutz) fehlen noch.

Vorbereitet ist: QR-Erzeugung in der App (ZXing), Platz im Zustand „bezahlt“. Offen bis zur endgültigen Regelung:

1. **Beleg im QR-Code** (komprimiert, Anzeige über eine feste Webseite; keine Daten auf einem Server) – Grenze: Größe.
2. **Beleg-Ablage im Internet** mit Download-Link – braucht Hosting, Aufbewahrung, Datenschutz (Auftragsverarbeitung).

Das Handy des Kunden ist meist nicht im WLAN des Ladens; ein Link auf das Kassen-Tablet reicht deshalb nicht.

## Technik

- `domain/CustomerDisplay.kt`: Zustand (`DisplayState`), `CustomerDisplayHub` (Kasse veröffentlicht, Displays lesen).
- `display/DisplayServer.kt`: HTTP ohne Bibliothek; `GET /d/<schlüssel>` (Seite), `GET …/events` (Server-Sent Events,
  Lebenszeichen alle 15 s), `POST …/tip` (Trinkgeldauswahl).
- `assets/customer_display.html`: Anzeigeseite (ohne externe Dateien).
- Einstellungen: `CUSTOMER_DISPLAY`, `CUSTOMER_DISPLAY_PORT` (8090), `CUSTOMER_DISPLAY_TOKEN`, `CUSTOMER_DISPLAY_HOST`.

### Test mit dem Emulator

Der Emulator ist vom WLAN aus nicht direkt erreichbar. Am Entwicklungs-PC:

    adb forward tcp:18090 tcp:8090
    python relay.py 8090 18090        (leitet 0.0.0.0:8090 → 127.0.0.1:18090 weiter)

In der App *Adresse für Displays* = IP des PCs; dann öffnet das Smartphone `http://<PC-IP>:8090/d/<Schlüssel>`.
