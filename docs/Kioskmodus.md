# Kioskmodus

Der Kioskmodus verhindert, dass Bedienpersonal die Kasse verlässt (Home, Übersicht, Benachrichtigungen,
andere Apps). Ein- und Ausschalten: *Einstellungen → Kioskmodus* (nur Administratoren).

## Stufe 1 – ohne Einrichtung (Bildschirmfixierung)

Funktioniert auf jedem Tablet sofort. Beim Einschalten fragt Android einmal „App anpinnen?“ → **OK**.
System- und Navigationsleiste werden ausgeblendet, Home und App-Wechsel sind gesperrt.

Einschränkung: Wer die Geste kennt (Wischen nach oben und halten), kann die Fixierung lösen.
Für den Alltag in einem kleinen Geschäft genügt das meist.

## Stufe 2 – volle Sperre (Geräteeigentümer)

Die Kasse wird zum Startbildschirm des Tablets, startet nach dem Einschalten automatisch, und die
Fixierung lässt sich nur noch über die Einstellungen der Kasse (Admin-PIN) lösen.

**Voraussetzungen**
- Tablet frisch eingerichtet oder auf Werkseinstellungen zurückgesetzt
- **kein Google-Konto** und kein anderes Konto auf dem Tablet angemeldet (sonst verweigert Android die Einrichtung)
- PC mit Android-Plattform-Tools (`adb`) und USB-Debugging am Tablet

**Einrichtung (einmalig)**
1. Tablet einrichten, Google-Konto-Schritt überspringen.
2. Entwickleroptionen aktivieren (7× auf *Build-Nummer*) → USB-Debugging an.
3. Any.POS installieren, dann am PC:
   ```
   adb shell dpm set-device-owner de.anypos/.kiosk.KioskAdminReceiver
   ```
   Erwartete Antwort: `Success: Device owner set to package de.anypos`
4. In der Kasse *Einstellungen → Kioskmodus* einschalten. Die Seite zeigt nun „Gerät ist für den
   Kioskbetrieb eingerichtet“.
5. USB-Debugging wieder ausschalten.

**Wichtig:** Ein Geräteeigentümer lässt sich in der Regel nur durch Zurücksetzen auf Werkseinstellungen
entfernen. Vorher eine Datensicherung erstellen.

> Hinweis zum Entwicklungsstand: Stufe 1 ist auf dem Testgerät (Emulator, Android 16) geprüft
> (Fixierung aktiv, Home gesperrt, sauberes Lösen). Stufe 2 ist implementiert, aber noch nicht auf
> einem Gerät erprobt – bitte beim ersten Einrichten des echten Tablets gemeinsam testen.
