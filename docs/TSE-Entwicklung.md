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
- Kommando: `<device_data><device_id>local_TSE</device_id><data><type>operate</type><timeout>60000</timeout><requestdata>JSON</requestdata></data></device_data>\0`
  → Antwort `<device_data>…<data><type>operateresult</type><success>true</success><code>SUCCESS</code><resultdata>JSON</resultdata></data><data_id>n</data_id></device_data>\0`
  (Handbuch: `operateresult`, Developer's Guide: `onoperateresult` – beides wird akzeptiert).
  Ohne `<sequence>` (Epson-Demo; in der Antwort ist sie fest „0“). JSON XML-maskiert inkl. `"` → `&quot;`.
  `timeout` 100–120000 ms (Standard 60 s; Self-Test/SetUp 120 s, Spez. S. 10–12), `requestdata` max. 64 KB.
  Zuordnung der Antwort über `function`.
- Fehler zu `device_data` kommen als eigene Nachricht `<error>…<code>…</code>…</error>` (Handbuch S. 82):
  `DEVICE_NOT_OPEN`, `DEVICE_NOT_FOUND`, `PARAM_ERROR`, `COMMAND_ILLEGAL`, `FUNCTION_NOT_FOUND` = nicht ausgeführt;
  `SEND_ERROR`, `DRIVER_ERROR`, `SYSTEM_ERROR` = unbekannt. Im Ergebnis selbst: `ERROR_PARMETER`, `ERROR_DEVICE_BUSY`
  = nicht ausgeführt, sonst unbekannt.
- Schließen: `<close_device><device_id>local_TSE</device_id></close_device>\0` – das Gerät ist exklusiv; die App
  gibt es **nach jeder Aktion frei** (Guide S. 6), die Anmeldung an der TSE bleibt dabei bestehen.
- Beenden: `<disconnect><data><client_id>…</client_id></data></disconnect>\0` (Handbuch S. 36, 68). Die
  `client_id` wird dauerhaft gespeichert; nach Neustart/Absturz beendet die App damit die alte Sitzung.
- Abbruch: TSE ist 1 Minute für andere gesperrt. Bricht die Verbindung **nach dem Senden** ab, steigt die App sofort
  per `<reconnect>` (`old_client_id`, `new_client_id`, `received_id` = letzte `data_id`) wieder ein; der Drucker liefert
  die zurückgehaltene Antwort nach (Handbuch S. 36). Kommt keine, ist das Ergebnis unbekannt, und die Sitzung wird per
  `<disconnect>` verworfen (eine späte Antwort darf keinem anderen Kommando zugeordnet werden). Bei Zeitüberschreitung
  kein `<reconnect>`, sondern `<disconnect>`. `open_device` mit `DEVICE_IN_USE` wird bis zu 25 × 1 s wiederholt.
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
  Die TSE prüft bei `AuthenticateUserForTimeAdmin` **nicht**, ob der Client registriert ist (Guide S. 30) – das
  zeigt sich erst bei `StartTransaction`. Bei falscher PIN kommt `remainingRetries` in `output`.
- Zeit: TSE arbeitet in UTC. Nach Power-Cycle und nach **jedem** Self-Test (setzt die Uhr zurück, Guide S. 21/29)
  muss die Zeit gesetzt werden: `UpdateTime{userId,newDateTime,useTimeSync:false}`. (`UpdateTimeForFirst` empfiehlt
  der Guide nur bei mehreren Kassen an einer TSE; nach dem Umzug nutzt nur Any.POS die TSE.)
- „Kasse über Nacht an“ (Guide S. 25): Anmeldung abgelaufen → Neuanmeldung meldet „Self-Test nötig“ →
  `RunTSESelfTest` → anmelden → Zeit setzen → Befehl einmal wiederholen.
- Transaktion: `StartTransaction{clientId,processData(b64),processType,additionalData}` →
  `{logTime,transactionNumber,serialNumber,signatureCounter,signature}`;
  `FinishTransaction{clientId,transactionNumber,processData,processType}` → `{logTime,signatureCounter,signature}`.
  `logTime` kommt als ISO-Zeit in UTC (`2026-10-22T10:00:00Z`) und wird für DSFinV-K in Unixzeit umgerechnet;
  `serialNumber` kommt Base64-codiert und wird als Hex ausgegeben.
  Max. 512 offene Transaktionen; `GetStartedTransactionList` liefert die offenen.
- Timeouts (Default): Admin 15 min, TimeAdmin 8 h Inaktivität, Export 100 s. Self-Test-Status verfällt nach 25 h.
- Export: **Rolle Admin** und vorher gesetzte Zeit (Spez. S. 45; sonst `OTHER_ERROR_NO_TIME_SET_BEFORE_EXPORT`).
  `ArchiveExport{}` → `GetExportData{}` (bis 128 KB je Aufruf, `exportStatus` `EXPORT_INCOMPLETE_SEARCHING` mit leeren
  Daten / `EXPORT_INCOMPLETE` / `EXPORT_COMPLETE`, Daten base64) → `FinalizeExport{deleteData:false}`; Abbruch mit
  `CancelExport{}`. **Während des Exports nimmt die TSE nichts anderes an** – auch nicht von JTL-POS;
  `GetStorageInfo` liefert dann `OTHER_ERROR_CURRENTLY_EXPORTING` samt Daten.
- `GetRegisteredClientList` und `GetTimeOutInterval`: ebenfalls **Rolle Admin** (Spez. S. 18, 22).
- Zertifikat: `GetLogMessageCertificate{}` → `logMessageCertificate` (Base64 einer PEM-Datei mit Zertifikatskette).
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
   `GetStorageInfo`, `GetChallenge`, `GetStartedTransactionList`, `GetLastTransactionResponse` und
   `GetLogMessageCertificate` passieren (korrigiert 22.10.2026 nach JSON-Spezifikation: Client-Liste, Timeout-Abfrage
   und alle Export-Funktionen verlangen eine Admin-Anmeldung; `ArchiveExport` sperrt die TSE). Jede andere Funktion
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
| `TSE_HOST` | `""` | IP/Hostname des Druckers (TSE-Host); leer = `PRINTER_HOST` |
| `TSE_PORT` | `""` | ePOS-Device-XML-Port; leer = 8143 mit TLS, 8009 ohne |
| `TSE_TLS` | `true` | TLS zum Drucker; aus nur für Mock/Development-TSE (Warndialog, protokolliert) |
| `TSE_TLS_FINGERPRINT` | `""` | vom Admin bestätigter SHA-256-Fingerabdruck des Druckerzertifikats; leer = keine Verbindung |
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
| 4 | Test-Grundlage `MockTseFixture` + Robustheit des Lesepfads (Abbruch + Reconnect, Gerätesperre, Fehlercodes, keine Antwort, `vendor`, Szenarien). Szenariotests wandern in den Schritt, der den Code liefert (Entscheidung 22.10.2026) | `app/src/test/…/MockTseFixture.kt`, `EpsonTseRobustnessTest.kt` | – |
| 5 | Login-/Zeit-Flow mit automatischem Re-Login nach Fehlercode; **mit Tests:** TimeAdmin-Timeout, Self-Test, Client nicht registriert, Secret falsch | `tse/EpsonTse.kt` | TimeAdmin-Login (hinter Guard) |
| 6 | `startTransaction`/`finishTransaction`, Hintergrund-Coroutine, Wiederanlauf über `GetStartedTransactionList`; **mit Tests:** Normalablauf, 512 offene Tx, Abbruch vor/nach Ausführung | `tse/EpsonTse.kt`, ggf. `domain/PosService.kt` | ja |
| 7 | Admin-Dialog „TSE übernehmen“: Admin-Login, `GetRegisteredClientList` (nur mit Admin), optional `RegisterSecretKey`, `RegisterClient(REGISTER_ID)`, `LogOutForAdmin`; PIN nur im Dialog | `ui/SettingsScreen.kt`, `tse/EpsonTse.kt` | ja |
| 8 | TAR-Export (Admin-Login → `UpdateTime` → `ArchiveExport` → `GetExportData` → `FinalizeExport{deleteData:false}`) in den DSFinV-K-Export. **Sperrt die TSE während des Exports** – nur außerhalb der Öffnungszeiten und nie, solange JTL-POS produktiv an derselben TSE arbeitet | `tse/EpsonTse.kt`, `export/ExportService.kt` | ja (Admin, Exportmodus) |
| 9 | **Phase 2a an der echten Hardware:** nur Schritt 3 mit `readOnly`; Ergebnis von `GetStorageInfo` (und `GetStartedTransactionList`) → in `TSE-Plan.md` dokumentieren. Das Gerät wird nach jedem Aufruf freigegeben, damit JTL-POS nicht blockiert wird. Registrierte Clients und Secret-Prüfung sind **keine** reinen Lesezugriffe (Admin-Login bzw. Anmeldeversuch) → erst nach Freigabe des Betreibers | – | nein |

Schritte 1–4 brauchen keine Hardware. Schritt 9 ist der erste Kontakt mit der echten TSE und rein lesend.

## Stand der Schritte

| # | Stand |
|---|---|
| 1 | erledigt 22.10.2026: `TseTransport`, `EposXmlSocketTransport`, 10 Protokolltests gegen Fake-Drucker. Abbruch/Timeout nach dem Senden → `TseResultUnknownException`, nie erneutes Senden; Wiedereinstieg per `<reconnect>` innerhalb 60 s. Korrektur nach Recherche (22.10.2026): kein `<sequence>` mehr, `"` als `&quot;`, Zuordnung über `function`, nach `<reconnect>` nachgelieferte Antworten verwerfen (11 Protokolltests). Noch an echter Hardware zu prüfen (Schritt 9): genaue Form der `<reconnect>`-Antwort. |
| 2 | erledigt 22.10.2026: Modul `tse-mock` (`MockTse` = JSON-Logik, `MockTseServer` = ePOS-Device-XML über TCP, `Main` = Konsole). Merkmale statt linearer Zustandskette (initialisiert, Clients, Admin/TimeAdmin-Anmeldung mit Challenge-Hash, Self-Test 25 h, Zeit, offene Tx, Export in Blöcken bis `EXPORT_COMPLETE`); Szenarien `FRESH`, `JTL_TAKEOVER`, `OPERATIONAL`; Fehlercodes nach Epson-Fehlerliste; Fehlerinjektion per Code (`inject`) und auf Verbindungsebene (`Fault`: Abbruch vor/nach Ausführung, keine Antwort); TSE-Sperre 60 s nach Abbruch, `<reconnect>`; `<sequence>` optional. Kennzeichnung: Seriennummer beginnt mit Bytes „MOCK“ (hex `4D4F434B`), `tseDescription` „MOCK TSE – KEIN GUELTIGER BELEG“, Zertifikat „MOCK TSE“. `writeCalls` protokolliert alles außerhalb der Leseliste (für die Schreibschutz-Tests in Schritt 3/4). 12 Mock-Tests + 4 Rauchtests der App (`TransportAgainstMockTest`). Start: `./gradlew :tse-mock:run --args="--scenario JTL_TAKEOVER"`; PINs/Secret des Mocks sind reine Testwerte. |
| 3 | erledigt 22.10.2026: `ReadOnlyGuardTransport` (Leseliste inkl. `GetLogMessageCertificate`; `FinalizeExport` nur mit echtem Boolean `deleteData:false`; unlesbares JSON gesperrt; Sperre → `AuditLog` `TSE_SCHREIBSPERRE`), `EpsonJson` (`EpsonException` = Fehlercode, sicher nicht ausgeführt), `EpsonTse` lesend (`status()` über `GetStorageInfo`, `registeredClients()`, Seriennummer Base64→Hex, Mock-Erkennung; Transaktionen werfen bis Schritt 6 → TSE-Ausfall), Einstellungen `TSE_HOST` (leer = Drucker-IP), `TSE_PORT`, `TSE_WRITE_ENABLED` (Schalter nur mit Warndialog, protokolliert), „Verbindung prüfen (nur lesend)“, Bon-Hinweis „MOCK – KEIN GÜLTIGER BELEG“. TimeAdmin-PIN/Secret kommen erst in Schritt 5. 9 Tests; im Emulator gegen den Mock geprüft (Banner, Rohwerte, Warndialog; beim Mock kamen nur lesende Aufrufe an). |
| 4 | erledigt 22.10.2026: `MockTseFixture` (Mock, Server, Transport, Schreibschutz, `EpsonTse`, gemeinsame steuerbare Uhr); bestehende Mock-Tests darauf umgestellt; `EpsonTseRobustnessTest` (7 Tests). Mock prüft `vendor` jetzt streng. `EpsonTse` hat `commandTimeoutMs`. Erkenntnis: Die TSE gehört genau einer Verbindung – die App muss eine `EpsonTse`-Instanz teilen und beim Wechsel die alte erst schließen (vor Schritt 5 in `AppContainer.tse()` absichern). |
| 5 | erledigt 22.10.2026: `EpsonTse.ensureReady()` (Self-Test → TimeAdmin-Anmeldung → `UpdateTime`, nur wenn nötig), `withRecovery` (einmalige Wiederherstellung nur nach Fehlercode: Anmeldung abgelaufen, Self-Test, keine Zeit; nie nach „unbekannt“), `syncTime()`. Falsche PIN/Secret: genau ein Versuch, danach keine weiteren bis zur Änderung (PIN wird nicht gesperrt). `CLIENT_NOT_REGISTERED` → Hinweis auf Admin-Ablauf, kein `SetUpForPrinter`. PIN/Secret verschlüsselt (Keystore), nie im Protokoll. TLS mit Fingerabdruck (`TlsPinning`), Mock mit `--tls`. `AppContainer.tse()` schließt die alte Verbindung vor dem Neuaufbau. Oberfläche: TLS-Schalter (Warndialog), Zertifikat bestätigen, PIN/Secret setzen, „Anmeldung testen“. 14 Tests; im Emulator gegen Mock mit TLS geprüft (ohne Bestätigung keine Verbindung, nach Bestätigung Anmeldung erfolgreich, Wiedereinstieg nach Abbruch). |
| K | erledigt 22.10.2026 – **Korrektur nach offiziellen Epson-Unterlagen** (JSON-Spez. Rev. C, Developer's Guide Rev. B, ePOS-Device-XML-Handbuch Rev. AC, EpsonTseDemo 2.4.1; liegen lokal in `downloads/`, nicht im Repository): Leseliste ohne Admin-Funktionen (Client-Liste, Timeout, Export); `<error>`-Nachrichten, beide Ergebnistypen, Ergebniscodes; `<disconnect>` mit dauerhaft gespeicherter `client_id`; sofortiger Wiedereinstieg mit Übernahme der nachgelieferten Antwort, sonst Sitzung verwerfen; `<timeout>` 60 s / 120 s für Self-Test, Grenzen 100–120000 ms und 64 KB; `open_device` mit Wiederholung bei `DEVICE_IN_USE`; Gerät nach jeder Aktion freigeben (JTL wird nicht blockiert, Abbruch zwischen Aktionen wird vor dem Senden erkannt); mehrstufige Wiederherstellung „über Nacht“, Zeit nach jedem Self-Test; `GetStorageInfo` auch bei `CURRENTLY_EXPORTING`; `remainingRetries`, `isTSEUnlocked`, `timeUntilNextSelfTest`. Mock nach Spezifikation (Prüfreihenfolge, Admin-Rolle, Exportmodus, `EXPORT_INCOMPLETE_SEARCHING`, PEM-Kette, `GetLastTransactionResponse` je Client, `<error>`, `<disconnect>`, Nachlieferung, `CLIENT_NOT_FOUND`). 131 Tests. |

## Offene Punkte (vor Schritt 7 klären)

- ~~Vor Schritt 5: TLS?~~ Entschieden 22.10.2026: TLS über 8143 mit bestätigtem Zertifikats-Fingerabdruck;
  8009 ohne TLS nur für Mock/Development-TSE. Am echten Drucker prüfen: Ist TLS (ePOS-Device SSL) aktiviert,
  und lässt sich der Fingerabdruck auf der Statusseite/in EpsonNet Config ablesen?
- **Vor Schritt 6:** Verbindung kann im Hintergrund wegbrechen (im Emulator beobachtet: App eingefroren, Mock
  meldete Abbruch). Durch die Freigabe des Geräts nach jeder Aktion fällt das jetzt beim nächsten `open_device`
  auf – vor dem Senden, also nicht „unbekannt“ (Korrektur K). Offen: TSE-Banner regelmäßig aktualisieren.
- Ungeklärt in den Unterlagen: Rolle von `GetStartedTransactionList`/`GetLastTransactionResponse` (als lesend
  angenommen), TLS auf Port 8143 bei unserem Druckermodell, Feldnamen bei `SetUp` (`adminPin` vs. `adminPIN`),
  `maxSignatures` (Spez. 10 Mio., Guide 20 Mio.) → in Schritt 9 bzw. mit der Development-TSE prüfen.

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
