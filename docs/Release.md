# Release erstellen (Test-APK zum Weitergeben)

## Signaturschlüssel – unbedingt sichern

Weitergegebene APKs werden mit einem eigenen Schlüssel signiert. Nur APKs mit **demselben** Schlüssel
lassen sich als Update über eine installierte Version spielen (sonst müssen Tester deinstallieren und
verlieren ihre Daten).

Der Schlüssel liegt **nicht** im Git-Repository:

| Datei | Inhalt |
|---|---|
| `keystore/anypos-release.jks` | Signaturschlüssel |
| `keystore.properties` | Pfad und Passwörter |

```properties
storeFile=keystore/anypos-release.jks
storePassword=…
keyAlias=anypos
keyPassword=…
```

**Beide Dateien an einem sicheren Ort aufbewahren** (z. B. Passwortmanager + verschlüsselte Ablage).
Geht der Schlüssel verloren, können bestehende Installationen nicht mehr aktualisiert werden.
Für eine spätere Veröffentlichung im Play Store wird ein eigener Upload-Schlüssel empfohlen.

## Bauen

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

Ergebnis: `app\build\outputs\apk\release\app-release.apk` (≈ 3 MB, optimiert mit R8).
Fehlt `keystore.properties`, entsteht ein unsigniertes APK, das sich nicht installieren lässt.

Vor jeder neuen Testversion `versionCode` (+1) und `versionName` in `app/build.gradle.kts` erhöhen –
Android installiert ein Update nur bei höherem `versionCode`.

## Weitergeben

- APK umbenennen in `AnyPOS-<versionName>-test.apk`
- Zusammen mit `docs/Testanleitung.md` und `docs/samples/jtl-items-v1-beispiel.csv` verschicken, **oder**
- auf GitHub: *Releases → Draft a new release* → Tag `v<versionName>` → APK als Datei anhängen →
  „Set as a pre-release“ aktivieren → *Publish*. Tester laden die APK dann direkt dort herunter.

Zur Prüfung der Signatur:
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs AnyPOS-0.1.0-test.apk
```
SHA-256 des aktuellen Schlüssels: `3ed5648ead58e874f4a713f3bea127c7e6f4c5881b8bb3573e17c606ce0a2401`
