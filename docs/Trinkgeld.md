# Trinkgeld

*Einstellungen → Trinkgeld*: Abfrage vor jeder Zahlung (Bar, Karte, Aufteilen), Standard **aus**.

## Ablauf

1. Kassierer tippt **Bar**, **Karte** oder **Aufteilen**.
2. Frage „Trinkgeld?“ an der Kasse und – wenn eingeschaltet – am **Kundendisplay**:
   *Kein Trinkgeld · 5 % · 10 % · Aufrunden auf … €* (Stufen einstellbar).
3. Wählt der Kunde am Display, wird die Auswahl an der Kasse hervorgehoben; der Kassierer **übernimmt** sie. Der
   Kassierer kann auch selbst eine Stufe oder „Anderer Betrag …“ wählen.
4. Das Trinkgeld wird eine **eigene Position** im selben Vorgang (eine TSE-Signatur), danach folgt die Zahlung über
   den Gesamtbetrag (Rückgeld bei Bar entsprechend).
5. Ändert sich danach der Warenkorb, wird das Trinkgeld entfernt und beim Bezahlen neu gefragt.

Keine Abfrage bei Rückgaben/negativen Beträgen. Ein Storno nimmt das Trinkgeld mit zurück.

## Steuerliche Behandlung

| | Trinkgeld für **Mitarbeitende** (Standard) | Trinkgeld an **Betrieb/Inhaber** |
|---|---|---|
| DSFinV-K | `TrinkgeldAN`, USt-Schlüssel 5 | `TrinkgeldAG`, USt-Schlüssel einstellbar (Standard 1 = 19 %) |
| Bon | getrennt: „Trinkgeld (Mitarbeiter, ohne USt)“, Steuersatz E 0 % | „Trinkgeld“ mit USt |
| Umsatz | nein (für Mitarbeitende steuerfrei, § 3 Nr. 51 EStG) | ja |
| Z-Bon | eigener Block: erhalten, davon unbar, ausgezahlt | „Trinkgeld Betrieb (im Umsatz)“ |
| DATEV | Konto *Trinkgeld Mitarbeiter* (Verbindlichkeit) | Erlöskonto des Steuersatzes |

Wichtig (Praxishilfe HWK, DSFinV-K): Trinkgeld der Mitarbeitenden muss auf dem Beleg **ohne Umsatzsteuer** getrennt
stehen – sonst schuldet der Betrieb Umsatzsteuer darauf. Unbare Trinkgelder (Karte) und ihre **Auszahlung** sind über
die Kasse zu erfassen und mit der TSE abzusichern.

## Auszahlung an Mitarbeitende

*Kassenbuch → Trinkgeld auszahlen (offen …)*: Betrag (Vorschlag = noch nicht ausgezahlt) und Name eingeben. Es entsteht
ein eigener Vorgang `TrinkgeldAN` (negativ, bar, mit TSE); der Bon wird gedruckt und von der Mitarbeiterin bzw. dem
Mitarbeiter unterschrieben. Im Kassenbuch erscheint die Auszahlung als Bargeldabgang.

## Mit dem Steuerberater abstimmen

- Konto *Trinkgeld Mitarbeiter* (DATEV, wird erst verlangt, wenn Trinkgeld gebucht wurde).
- Wer bekommt das Trinkgeld (Mitarbeitende oder Inhaber), Verteilung, Unterlagen für die Lohnbuchhaltung.
- Steuersatz für Trinkgeld an den Betrieb.

## Technik

`BusinessCase.TIP_EMPLOYEE`/`TIP_OWNER`, `Cart.withTip`/`withoutTip`, `Tips.options`, `TipTotals`,
`PosService.tipPayout`/`tipBalance`, `TipDialog`, Einstellungen `TIP_ENABLED`, `TIP_RECIPIENT`, `TIP_PERCENTS`,
`TIP_ROUND_UP`, `TIP_OWNER_TAX_KEY`, `ACC_TIP`. Tests: `TipsTest`, `CustomerDisplayTest`.
