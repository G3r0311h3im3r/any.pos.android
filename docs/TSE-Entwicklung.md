# Epson-TSE-Anbindung – Entwicklungskonzept und Arbeitsauftrag

Dieses Dokument ist die Arbeitsgrundlage für die Umsetzung von `de.anypos.tse.EpsonTse`.
Es ergänzt `TSE-Plan.md` (Phasen und Sicherheitsregeln) um die technische Architektur und die
Schritte, in denen gearbeitet wird. **Jeder Schritt wird einzeln vorgeschlagen, abgenommen und erst
dann umgesetzt.**

## Ausgangslage

- Hardware: Epson TM-m30III (LAN, WLAN, Bluetooth, USB-C) mit Epson **USB-TSE am Drucker**.
  TSE-Host ist der Drucker. Die TSE wurde von **JTL-POS initialisiert** und läuft dort produktiv.
- Software: `Tse`-Interface, `SimulatedTse`, `NoTse` vorhanden; `PosService` kennt nur das Interface.
  `EpsonTse` ist Platzhalter. `ProcessData` (Kassenbeleg-V1) und QR-Code auf dem Beleg sind fertig.
- Ziel: Any.POS übernimmt die bestehende TSE vom Drucker. **Kein `SetUp`**, die TSE ist eingerichtet.

## Protokoll (Epson ePOS Device XML, Quelle: Epson TSE Developer's Guide Rev. 1.0.1)

- TCP-Socket zum Drucker, Port **8009** (unverschlüsselt) oder 8143 (TLS).
- Jede Nachricht endet mit einem Null-Byte (`\0`).
- Nach Connect schickt der Host: `<connect><data><client_id>sock…</client_id><protocol_version>2</protocol_version></data></connect>\0`
- Öffnen: `<open_device><device_id>local_TSE</device_id><data><type>type_storage</type></data></open_device>\0`
  → Antwort `<open_device><device_id>local_TSE</device_id><code>OK</code><data_id>1</data_id></open_device>\0`
- Kommando: `<device_data><device_id>local_TSE</device_id><data><type>operate</type><timeout>10000</timeout><requestdata>JSON</requestdata></data></device_data>\0`
  → Antwort `<data><type>onoperateresult</type><resultdata>JSON</resultdata></data>\0`
  Ohne `<sequence>` (wie in der praxiserprobten fiskaltrust-Anbindung); JSON XML-maskiert inkl. `"` → `&quot;`.
  Zuordnung der Antwort über `function`; nach `<reconnect>` werden nachgelieferte Antworten verworfen.
- Schließen: `<close_device><device_id>local_TSE</device_id></close_device>\0`
- Reconnect nach Socket-Abbruch: TSE ist 1 Minute für andere gesperrt; der alte Client darf sofort
  per `<reconnect>` mit `old_client_id`, `new_client_id`, `received_id` (letzte `data_id`) wieder rein.
- JSON-Request:
  ```json
  {"storage":{"type":"TSE","vendor":"TSE1"},"function":"…","input":{…},"compress":{"required":false,"type":""}}
  ```
  Für `GetStorageInfo`: `"storage":{"type":"COMMON","vendor":""}`. `vendor` für alle übrigen Aufrufe =
  `tseInformation.vendorType` aus `GetStorageInfo`.
- JSON-Response: `{"result":"EXECUTION_OK","function":"…","output":{…}}` oder
  `{"result":"<ErrorCode>","function":"…","error":{"fact":"…","errorinfo":"…"}}`
- Login (Challenge-Response): `GetChallenge{userId}` → `hash = base64(sha256(challenge + sharedSecret))`,
  Shared Secret ab Werk `EPSONKEY`, kann per `RegisterSecretKey` je **Host** (Drucker) geändert sein.
  Dann `AuthenticateUserForTimeAdmin{clientId,pin,hash}` bzw. `AuthenticateUserForAdmin{userId:"Administrator",pin,hash}`.
- Zeit: TSE arbeitet in UTC. Nach Power-Cycle und nach Self-Test muss `UpdateTimeForFirst{userId,newDateTime,useTimeSync:"false"}` gesetzt werden.
- Transaktion: `StartTransaction{clientId,processData(b64),processType,additionalData}` →
  `{logTime,transactionNumber,serialNumber,signatureCounter,signature}`;
  `FinishTransaction{clientId,transactionNumber,processData,processType}` → `{logTime,signatureCounter,signature}`.
  `logTime` kommt als ISO-Zeit in UTC (`2026-10-22T10:00:00Z`) und wird für DSFinV-K in Unixzeit umgerechnet;
  `serialNumber` kommt Base64-codiert und wird als Hex ausgegeben.
  Max. 512 offene Transaktionen; `GetStartedTransactionList` liefert die offenen.
- Timeouts (Default): Admin 15 min, TimeAdmin 8 h Inaktivität, Export 100 s. Self-Test-Status verfällt nach 25 h.
- Export: `ArchiveExport{}` → `GetExportData{}` (max. 64 KB je Aufruf, `exportStatus` `EXPORT_INCOMPLETE`/`EXPORT_COMPLETE`, Daten base64) → `FinalizeExport{deleteData:false}`; Abbruch mit `CancelExport{}`.
- Zertifikat: `GetLogMessageCertificate{}` → `logMessageCertificate` (Base64, DER).
- Signaturzeit ~200–500 ms → Transaktionen in Hintergrund-Coroutine, Bondruck erst nach Finish.

Wichtige Fehlercodes und Reaktion (Epson-Best-Practice: nicht vorab pollen, Operation versuchen, auf Code reagieren):

| Code | Reaktion |
|---|---|
| `OTHER_ERROR_UNAUTHENTICATED_TIME_ADMIN_USER` / `TSE1_ERROR_AUTHENTICATION_USER_NOT_LOGGED_IN` | TimeAdmin neu einloggen (Timeout), dann wiederholen – ein zurückgegebener Code heißt: nicht ausgeführt |
| `TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST` / `TSE1_ERROR_WRONG_STATE_NEEDS_SELF_TEST_PASSED` | `RunTSESelfTest`, dann Re-Login, `UpdateTime…`, dann wiederholen |
| `TSE1_ERROR_NO_TIME_SET` | TimeAdmin-Login, `UpdateTime`, dann wiederholen |
| `TSE1_ERROR_CLIENT_NOT_REGISTERED` | Client-ID nicht registriert → Admin-Flow „TSE übernehmen“ nötig |
| `OTHER_ERROR_HOST_AUTHENTICATION_FAILED` | Shared Secret stimmt nicht (JTL hat es ggf. geändert) → Admin-Flow |
| `OTHER_ERROR_CURRENTLY_EXPORTING` | nach Reconnect zuerst `CancelExport` |
| `OTHER_ERROR_UNKNOWN_STORAGE` / `OTHER_ERROR_NO_STORAGE_FOUND` | TSE nicht erkannt → Hardware prüfen, als Ausfall markieren |
| `TSE1_ERROR_CLIENT_NOT_REGISTERED` bei `RunTSESelfTest` | TSE war an anderem Host → Admin + `SetUpForPrinter` (schreibend, nur mit Freigabe) |

Keine Transaktion wird bei unklarem Ergebnis blind wiederholt (Regel 4 aus `TSE-Plan.md`); stattdessen
`GetStartedTransactionList` prüfen.

## Architektur

```
Tse (Interface, vorhanden)
 └── EpsonTse                – JSON-Kommandos, Login/Zeit-Zustandsmaschine, Fehlerbehandlung, readOnly-Guard
      └── TseTransport       – connect() / open() / operate(json, timeout) / close() / disconnect()
           ├── EposXmlSocketTransport   – TCP 8009, reiner Socket-Code, keine SDK-Abhängigkeit   (jetzt)
           └── EposSdkUsbTransport      – Epson ePOS SDK (Epos2GermanyFiscalElement) über USB   (später, nur falls USB zwingend)
```

- Die gesamte Logik liegt in `EpsonTse` und ist transportunabhängig.
- Entwicklung und Test laufen gegen `EposXmlSocketTransport`; der Drucker hängt dafür per LAN im Netz.
- Der Mock-Server (unten) spricht dasselbe Protokoll → **derselbe Code** läuft gegen Mock, Development-TSE und Produktiv-TSE; nur die IP ändert sich.

### Drei Schutzschichten gegen die Produktiv-TSE

1. **`readOnly`-Guard als Hülle um den Transport** (`ReadOnlyGuardTransport`, Schritt 3; Entscheidung vom
   22.10.2026: direkt vor der Leitung, unabhängig von `EpsonTse`; `EposXmlSocketTransport` bleibt reiner Socket-Code).
   Standard ist `TSE_WRITE_ENABLED = false`. Dann dürfen nur
   `GetStorageInfo`, `GetChallenge`, `GetTimeOutInterval`, `GetStartedTransactionList`,
   `GetRegisteredClientList`, `GetLastTransactionResponse`, `GetLogMessageCertificate` und die Export-Lesefunktionen (`ArchiveExport`,
   `GetExportData`, `FinalizeExport` nur mit `deleteData:false`, `CancelExport`) passieren. Jede andere Funktion
   wirft `TseException("Schreibender TSE-Aufruf gesperrt: <function>")`, **bevor** etwas gesendet wird.
   Freischaltung nur durch Admin in den Einstellungen mit Warnhinweis; wird protokolliert (`AuditLog`).
2. **Mock-Server `tse-mock`** für alles, was Logik und Fehlerpfade betrifft.
3. **Development-TSE** von Epson (nicht zertifiziert, `FactoryReset` möglich, über Distributor / fiskal@epson.de)
   für Timing, Firmware-Eigenheiten, Übernahme-Flow. Die Produktiv-TSE wird nur lesend berührt, bis zur Abnahme.

### Mock-Server: Gradle-Modul `tse-mock` (Kotlin/JVM, kein Android)

- `./gradlew :tse-mock:run` → lauscht auf 8009, loggt jedes Kommando. Tablet-App zeigt per `TSE_HOST` auf den Dev-PC.
- `testImplementation(project(":tse-mock"))` → Unit-Tests starten den Mock in-process auf einem freien Port.
- Merkmale statt linearer Zustandskette (Entscheidung 22.10.2026): initialisiert, registrierte Clients,
  Admin-/TimeAdmin-Anmeldung, Self-Test, Zeit, offene Transaktionen, Export, Sperre nach Abbruch – unabhängig
  voneinander, wie die echte TSE sie in `GetStorageInfo` meldet.
- Konfigurierbar: Startzustand (z. B. „von JTL eingerichtet, Secret geändert, Client `JTL-POS-1` registriert“), Shared Secret,
  PINs, Latenz pro Signatur, Fehlerinjektion (nächster Aufruf liefert Code X), Uhr (für 8 h / 25 h Timeouts).
- Signaturen: ECDSA P-256 mit festem Beispielschlüssel, damit `signature`, `publicKey`, `certificate` plausibel sind
  (ausdrücklich keine gültige TSE-Signatur; Seriennummer `MOCK-…`).
- `GetStorageInfo`-Antwort nach Vorlage der echten TSE aus Phase 2a (Felder `vendorType`, `serialNumber`,
  `certificateExpirationDate`, `createdSignatures`, `hasPassedSelfTest`, `tseDescription`, `softwareVersion`, …).

### Einstellungen (neue `Key`-Einträge)

| Key | Default | Zweck |
|---|---|---|
| `TSE_HOST` | `192.168.1.50` | IP/Hostname des Druckers (TSE-Host) |
| `TSE_PORT` | `8009` | ePOS-Device-XML-Port |
| `TSE_WRITE_ENABLED` | `false` | Schreibschutz, siehe oben |
| `TSE_TIMEADMIN_PIN` | `""` | verschlüsselt (Android Keystore, wie `BACKUP_SECRET`) |
| `TSE_SHARED_SECRET` | `""` | verschlüsselt; leer = `EPSONKEY` |

Client-ID der Kasse = `REGISTER_ID` (`KASSE-01`). **Admin-PIN und PUK werden nie gespeichert**, nur im
Dialog „TSE übernehmen“ abgefragt.

### Erweiterungen am bestehenden Modell

- `TseFinish.publicKey`, `.certificate`: kommen nicht pro Transaktion, sondern aus `GetStorageInfo` /
  `GetLogMessageCertificate`; `EpsonTse` holt sie einmal und cached sie.
- `signatureAlgorithm = "ecdsa-plain-SHA256"`, `timeFormat = "unixTime"` (fix für Epson; `logTime` aus ISO umgerechnet).
- `StartTransaction` liefert ebenfalls `signature` und erhöht den Signaturzähler; `TseStart` kann das
  ignorieren, für DSFinV-K reicht der Finish.

## Arbeitsschritte

| # | Schritt | Dateien | schreibt auf TSE? |
|---|---|---|---|
| 1 | `TseTransport`-Interface + `EposXmlSocketTransport` (Handshake, open/close, operate, Null-Terminator, Reconnect, Timeouts) | `tse/TseTransport.kt`, `tse/EposXmlSocketTransport.kt` | nein |
| 2 | Modul `tse-mock` mit Zustandsmaschine, Standalone-`main`, Fehlerinjektion | `tse-mock/…`, `settings.gradle.kts` | – |
| 3 | `EpsonTse` gegen Transport: `status()` via `GetStorageInfo`, `readOnly`-Guard, neue Settings, Verdrahtung in `AppContainer.tse()` | `tse/EpsonTse.kt`, `domain/Settings.kt`, `AnyPosApp.kt`, `ui/SettingsScreen.kt` | nein |
| 4 | Unit-Tests `EpsonTse` ↔ Mock: Happy Path, TimeAdmin-Timeout, Self-Test, Socket-Abbruch + Reconnect, Client nicht registriert, Secret falsch, 512 offene Tx | `app/src/test/…/EpsonTseTest.kt` | – |
| 5 | Login-/Zeit-Flow mit automatischem Re-Login nach Fehlercode | `tse/EpsonTse.kt` | TimeAdmin-Login (hinter Guard) |
| 6 | `startTransaction`/`finishTransaction`, Hintergrund-Coroutine, Wiederanlauf über `GetStartedTransactionList` | `tse/EpsonTse.kt`, ggf. `domain/PosService.kt` | ja |
| 7 | Admin-Dialog „TSE übernehmen“: Admin-Login, optional `RegisterSecretKey`, `RegisterClient(REGISTER_ID)`, `LogOutForAdmin`; PIN nur im Dialog | `ui/SettingsScreen.kt`, `tse/EpsonTse.kt` | ja |
| 8 | TAR-Export (`ArchiveExport` → `GetExportData` → `FinalizeExport{deleteData:false}`) in den DSFinV-K-Export | `tse/EpsonTse.kt`, `export/ExportService.kt` | nein |
| 9 | **Phase 2a an der echten Hardware:** nur Schritt 3 mit `readOnly`; Ergebnis von `GetStorageInfo`, `GetRegisteredClientList`, Login-Test mit `EPSONKEY` (nur um zu sehen, ob das Secret geändert wurde) → in `TSE-Plan.md` dokumentieren | – | nein |

Schritte 1–4 brauchen keine Hardware. Schritt 9 ist der erste Kontakt mit der echten TSE und rein lesend.

## Stand der Schritte

| # | Stand |
|---|---|
| 1 | erledigt 22.10.2026: `TseTransport`, `EposXmlSocketTransport`, 10 Protokolltests gegen Fake-Drucker. Abbruch/Timeout nach dem Senden → `TseResultUnknownException`, nie erneutes Senden; Wiedereinstieg per `<reconnect>` innerhalb 60 s. Korrektur nach Recherche (22.10.2026): kein `<sequence>` mehr, `"` als `&quot;`, Zuordnung über `function`, nach `<reconnect>` nachgelieferte Antworten verwerfen (11 Protokolltests). Noch an echter Hardware zu prüfen (Schritt 9): genaue Form der `<reconnect>`-Antwort. |
| 2 | erledigt 22.10.2026: Modul `tse-mock` (`MockTse` = JSON-Logik, `MockTseServer` = ePOS-Device-XML über TCP, `Main` = Konsole). Merkmale statt linearer Zustandskette (initialisiert, Clients, Admin/TimeAdmin-Anmeldung mit Challenge-Hash, Self-Test 25 h, Zeit, offene Tx, Export in Blöcken bis `EXPORT_COMPLETE`); Szenarien `FRESH`, `JTL_TAKEOVER`, `OPERATIONAL`; Fehlercodes nach Epson-Fehlerliste; Fehlerinjektion per Code (`inject`) und auf Verbindungsebene (`Fault`: Abbruch vor/nach Ausführung, keine Antwort); TSE-Sperre 60 s nach Abbruch, `<reconnect>`; `<sequence>` optional. Kennzeichnung: Seriennummer beginnt mit Bytes „MOCK“ (hex `4D4F434B`), `tseDescription` „MOCK TSE – KEIN GUELTIGER BELEG“, Zertifikat „MOCK TSE“. `writeCalls` protokolliert alles außerhalb der Leseliste (für die Schreibschutz-Tests in Schritt 3/4). 12 Mock-Tests + 4 Rauchtests der App (`TransportAgainstMockTest`). Start: `./gradlew :tse-mock:run --args="--scenario JTL_TAKEOVER"`; PINs/Secret des Mocks sind reine Testwerte. |
| 3 | erledigt 22.10.2026: `ReadOnlyGuardTransport` (Leseliste inkl. `GetLogMessageCertificate`; `FinalizeExport` nur mit echtem Boolean `deleteData:false`; unlesbares JSON gesperrt; Sperre → `AuditLog` `TSE_SCHREIBSPERRE`), `EpsonJson` (`EpsonException` = Fehlercode, sicher nicht ausgeführt), `EpsonTse` lesend (`status()` über `GetStorageInfo`, `registeredClients()`, Seriennummer Base64→Hex, Mock-Erkennung; Transaktionen werfen bis Schritt 6 → TSE-Ausfall), Einstellungen `TSE_HOST` (leer = Drucker-IP), `TSE_PORT`, `TSE_WRITE_ENABLED` (Schalter nur mit Warndialog, protokolliert), „Verbindung prüfen (nur lesend)“, Bon-Hinweis „MOCK – KEIN GÜLTIGER BELEG“. TimeAdmin-PIN/Secret kommen erst in Schritt 5. 9 Tests; im Emulator gegen den Mock geprüft (Banner, Rohwerte, Warndialog; beim Mock kamen nur lesende Aufrufe an). |

## Offene Punkte (vor Schritt 7 klären)

- **Vor Schritt 5:** TLS (Port 8143) für den Produktivbetrieb verpflichtend? Ab Schritt 5 wird die TimeAdmin-PIN
  übertragen; über 8009 unverschlüsselt im LAN mitlesbar. Der Transport ist dafür vorbereitet (`socketFactory`).

- Hat JTL-POS das Shared Secret des Druckers geändert? (Ergebnis aus Schritt 9.)
- Admin-PIN / PUK der TSE: liegen beim Betreiber? Siehe `TSE-Umzug-Betreiber.md`.
- Bleibt der Drucker produktiv im LAN (empfohlen: ein Transport für Druck und TSE, Tablet lädt per USB-C PD),
  oder muss USB bleiben (dann Schritt „EposSdkUsbTransport“ + ePOS SDK einbinden)?
- Development-TSE bestellt? Lieferzeit?

## Quellen

- Epson TSE Developer's Guide Rev. 1.0.1: https://download4.epson.biz/sec_pubs/bs/pdf/GermanFiscal_TSE_Developers_Guide_en_RevB.pdf
- Epson TSE Documents and Software (Treiber, JSON-Spezifikation, EpsonTSEDemo mit C++-Quellcode):
  https://www.epson.eu/fr_EU/support/sc/fiscal-tse-for-germany/s/s3557
- Epson ePOS SDK for Android: https://download.epson-biz.com/modules/pos/
- BSI TR-03153 / TR-03151, DSFinV-K 2.x (BZSt)
