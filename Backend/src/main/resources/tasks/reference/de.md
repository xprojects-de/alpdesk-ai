Eine Aufgabe wird in **YAML** geschrieben (JSON geht auch). Sie liest die neuen Zeilen von Dateien auf einer
Dateiablage, lässt sie vom Chat-Modell des Mandanten gegen Prüfpunkte prüfen, prüft die Befunde gegen und meldet sie
per E-Mail - nur wenn etwas nicht stimmt, oder wenn ein Lauf fehlschlug oder unklar blieb.

## Die Aufgabe

| Feld | |
|---|---|
| `version` | immer `1` |
| `name` | der Name der Aufgabe, eindeutig im Mandanten |
| `notify` | E-Mail-Adressen, die von einem fehlgeschlagenen oder unklaren Lauf erfahren (und ein `send_mail` ohne `to` bekommen) |
| `triggers` | wann sie von selbst läuft - weggelassen: nur von Hand, durch die angebundene Software oder MCP |
| `steps` | die Schritte, von oben nach unten |

## Auslöser

`type`: `schedule` mit **entweder** `every` (`15m` bis `7d`, etwa `30m`, `5h`, `1d`) **oder** `cron` (sechs Felder:
Sekunde Minute Stunde Tag Monat Wochentag, etwa `"0 30 6 * * MON-FRI"`). Eine Aufgabe läuft höchstens alle 15 Minuten.

## Felder jedes Schritts

`id` (Kleinbuchstaben, Ziffern, `_`), `type`, `input` (frühere Schritte, die er liest), `if` (übersprungen, wenn
falsch), `next` (wohin es weitergeht: Einträge mit `if`, `to` und - zurück - `maxRounds`).

## Schritttypen

- **`read_files`** - `store` (der Name der Dateiablage), `folder` (unterhalb des Ordners der Ablage), `pattern`
  (ein Dateiname mit `*`, `?` und Datumsangaben wie `{yyyy-MM-dd}`), `days` (das Datum von heute und der Tage davor,
  1-31), `onlyNew` (Standard `true`: nur Neues seit dem letzten erfolgreichen Lauf), `firstRun` (`start`, der
  Standard, oder `end`: Der erste Lauf der Aufgabe liest nichts, es zählt nur, was danach geschrieben wird - für Logs
  mit langer Vorgeschichte; Dateien, die später dazukommen, werden von Anfang an gelesen), `minLines` (optional: eine Datei, die stetig wachsen muss, z. B. das Lebenszeichen
  eines Crons - weniger neue Zeilen lassen den Lauf scheitern, und die notify-Liste bekommt eine Mail mit dem Grund),
  `include` / `exclude`
  (Texte, die eine Zeile enthalten muss oder nicht enthalten darf), `mask` (Werte, die durch `***` ersetzt werden,
  bevor eine Zeile übernommen wird - nichts Maskiertes erreicht das Modell oder die Mail; jeder Eintrag ist
  `after: TEXT` - der Wert nach dem Text (Leerzeichen dazwischen übersprungen) bis zum nächsten `"`, `'`, `&`, `<`,
  `>`, `,`, `;` oder Leerzeichen - oder `regex: AUSDRUCK` - jeder Treffer; z. B. `- after: 'ApiPassword="'`,
  `- after: 'password:'` für `password: s3cret`, in einem HTML-maskierten Log
  `- after: 'ApiPassword=&quot;'`).
- **`read_documents`** - ganze Dokumente (PDF, DOCX, ODT, RTF, TXT, Markdown, HTML, PPTX, XLSX, ODS, CSV; Bilder und
  gescannte Seiten liest das Bildmodell des Mandanten), jedes einmal - erneut nur, wenn es sich geändert hat: `store`,
  `folder`, `pattern` (ein Dateiname mit `*` und `?` oder eine Liste davon, etwa `["*.pdf", "*.jpg"]` - Groß- und
  Kleinschreibung gleich; `"*"` jede Datei), `subfolders` (Standard `false`; `true`: auch die Unterordner, etwa einer
  je Jahr). Höchstens 50 Dokumente pro Lauf, die ältesten zuerst - der Rest kommt mit dem nächsten Lauf. Ein Dokument,
  das sich nicht lesen lässt, wird mit dem Grund festgehalten, der Lauf geht weiter.
- **`extract`** - eine Zeile je Dokument eines `read_documents`-Schritts (`input`): `model`, `instructions` (optional -
  etwa die Kontierungsregeln der Firma oder wer der Partner ist), `knowledge` (Standard `false`; `true`: das Modell darf
  im Firmenwissen suchen), `fields` - die Spalten der Tabelle, nicht der Aufbau des Dokuments: Das Modell findet jeden
  Wert, wo immer er im Dokument steht. Jedes Feld: `name` (Kleinbuchstaben, Ziffern, `_`), `type` (`text`, `date`,
  `amount`, `number`, `percent`, `choice` mit `values: [wort, ...]`), `required` (Standard `false`), `description` (was
  es ist). Der Server prüft jeden Wert; eine Zeile, der ein Pflichtfeld fehlt, mit einem Wert falschen Typs oder aus
  einem Dokument, das sich nicht lesen ließ, ist `unclear` mit dem Grund. Ein Feld wie `extra` (`text`) nimmt auf, was in
  keine Spalte passt.
- **`write_file`** - die Zeilen eines `extract`-Schritts (`input`) als CSV in einer Ablage, deren Schalter Aufgaben das
  Schreiben erlaubt: `store`, `file` (unterhalb des Ordners der Ablage, endet auf `.csv`; Platzhalter `{yyyy}`, `{MM}`,
  `{dd}` - das Datum des Laufs, `{date:yyyy}` - das Jahr eines Datumsfelds der Zeile, `{kind}` - das Wort eines
  choice-Felds), `mode` (`append`, der Standard: eine Zeile, deren Dokument schon in der Datei steht, wird übersprungen;
  `replace`: nur die Zeilen dieses Laufs), `separator` (`;` Standard oder `,`), `decimal` (`,` Standard oder `.`),
  `dateFormat` (Standard `yyyy-MM-dd`, etwa `dd.MM.yyyy`). Die Spalten: die Felder, dann `unclear` und `source`; Felder,
  die am Ende der Liste dazukommen, werden übernommen, jede andere Änderung braucht eine neue Datei. Zwei Aufgaben
  schreiben nie dieselbe Datei. Der Ordner muss existieren.
- **`call_tool`** - die Zeilen eines `extract`-Schritts (`input`) an ein Werkzeug der angebundenen Software: `connection`
  (ihr Name auf der Seite „Software“), `tool` (eines, das eingeschaltet ist), `each` (`row`, der Standard: ein Aufruf je
  Zeile; `all`: ein Aufruf mit allen Zeilen als Liste `rows`), `with` (feste Argumente, etwa `journal: received`). Die
  Felder einer Zeile gehen mit ihren Namen mit, dazu `source` (Pfad und Prüfsumme des Dokuments - daran erkennt die
  Software eine erneut gesendete Zeile). Unklare Zeilen werden nie gesendet. Ein abgelehnter Aufruf markiert seine
  Zeile; der Lauf endet unklar, und die notify-Liste erfährt es. **Ohne `input` ist er eine Quelle** - der erste Schritt,
  er ruft ein lesendes Werkzeug: Mit `fields` (typisiert wie bei `extract`) und `key` (das Feld, das eine Zeile eindeutig
  macht) wird seine Antwort, ein JSON-Array von Objekten, zu Zeilen (höchstens 200 je Antwort); mit `key` und
  `file: {tool: <ein Werkzeug, das eine Datei liefert>, with: {...}}` wird die Datei jedes Objekts über seinen Schlüssel
  geholt und wie ein Dokument gelesen (für einen `extract`-Schritt). `{lastRun:yyyy-MM-dd}` in `with` ist der Start des
  letzten erfolgreichen Laufs der Aufgabe (beim ersten Mal leer) - etwa `since: '{lastRun:yyyy-MM-dd}'`.
- **`call_agent`** - die Zeilen eines `extract`-Schritts (`input`) an einen A2A-Agenten der angebundenen Software
  (der Administrator erlaubt A2A für den Mandanten): `agent` (der Name seiner Verbindung), `skill` (optional, eine
  Fähigkeit seiner Karte), `each` (`row`, Standard: eine Nachricht je Zeile; `all`: eine Nachricht mit allen Zeilen als
  Liste `rows`), `prompt` (was der Agent mit ihnen tun soll, bis 2.000 Zeichen - als Text vor den Daten), `with` (feste
  Werte). Die Felder einer Zeile gehen mit ihren Namen als Daten, dazu `source`. Unklare Zeilen werden nie gesendet.
  Eine abgelehnte Nachricht, eine Rückfrage des Agenten (eine Aufgabe hat niemanden, der antwortet) oder kein Ende
  innerhalb der Agenten-Zeit markiert die Zeile; der Lauf endet unklar.
- **`agent`** - `model` (ein Chat-Modell des Mandanten), `input` (read_files-Schritte), `instructions` (optional),
  `checks`: jeder mit `name`, `severity` (`error` oder `warning` - ein Problem, das die Zeilen zeigen; oder `report` -
  etwas zum Weitergeben, z. B. „liste alle übrigen Fehler“: seine Notiz kommt in die Mail, sobald es etwas gibt,
  `verify` lässt ihn, wie er ist) und `instruction`.
- **`verify`** - `of` (der Agent-Schritt, dessen Befunde er gegenprüft), optional `model` und `instructions`. Sein
  `model` darf ein günstigeres als das des Agent-Schritts sein (weggelassen: das des Agent-Schritts).
- **`prompt`** - `model` (ein Chat-Modell des Mandanten), `prompt` (was das Modell tun soll), `input` (beliebige
  frühere Schritte: gelesene Dateien, Antworten früherer Prompts, Befunde), optional `choices` (2-10 Wörter, etwa
  `[approve, rework]`). Er antwortet in freiem Text - der nächste `prompt` nimmt die Antwort als Eingabe, so entsteht
  eine Kette. Mit `choices` wählt er zusätzlich ein Wort, das eine Bedingung liest: `review.choice == rework`. Durch
  eine Kante zurückgeschickt, sieht er die Antwort, die ihn zurückgeschickt hat.
  **`assistant: true`** beantwortet ihn als Assistent des Unternehmens: er durchsucht das Firmenwissen und die in
  `areas` genannten Wissensbereiche (Namen; keiner: nur das Firmenwissen), nutzt die lesenden Werkzeuge der in
  `software` genannten angebundenen Software (Namen - auch A2A-Agenten, mit dem A2A-Schalter des Administrators), sucht
  mit `web: true` im Web und folgt den Anweisungen der in `template` genannten Chat-Vorlage. Bei einem Mandanten, dessen
  Chat über seinen A2A-Agenten antwortet, beantwortet der Agent den Schritt; seine Rückfrage lässt den Schritt scheitern.
  Dort kann `model` entfallen - der Agent bekommt den `prompt` und die Eingaben, kein Modell wird gefragt.
- **`send_mail`** - `input` (Agent-, Verify-, Prompt-, Extract-, Write-File- oder Call-Tool-Schritte, die Zeilen liefern - die Antwort
  eines Prompts kommt in die Mail; von einem Extract-Schritt oder einer Call-Tool-Quelle die unklaren Zeilen mit Grund und wie
  viele Dokumente und Zeilen der Lauf hatte), `to`
  (weggelassen: `notify`), `subject`.

## Bedingungen

`<Schritt>.<Zähler> <Operator> <Zahl>`, verbunden mit `&&` und `||` - etwa `verify.errors > 0 || verify.warnings > 0`.
Zähler: `files`, `lines` (read_files); `files` (read_documents); `rows`, `unclear` (extract); `rows`, `skipped` (write_file); `sent`, `failed` (call_agent, call_tool, das sendet), `rows`, `unclear` oder `files` (call_tool als Quelle); `errors`, `warnings`, `reports`, `unclear`, `ok` (agent, verify); `chars` (prompt). Ein
Prompt-Schritt mit `choices`: `<Schritt>.choice == <Wort>` oder `!=`. Operatoren:
`> >= < <= == !=`.

## Schleifen

Eine Kante zurück (`to` ein früherer Schritt) braucht `maxRounds` (1-5): wie oft sie zurückgeht. Ein Befund, der an
der Grenze noch unklar ist, beendet den Lauf als unklar und meldet ihn an `notify` - ebenso ein Prüfpunkt, den der
letzte agent- oder verify-Schritt unklar ließ, mit oder ohne Kante zurück.

## Kommentare und lange Texte

`#` beginnt einen Kommentar; `|` beginnt einen Text über mehrere Zeilen. Anker und Aliase (`&`, `*`) werden nicht
verwendet.

## Beispiel

```yaml
version: 1
name: Log check interfaces
notify: [it@example.com]
triggers:
  - type: schedule
    every: 5h
steps:
  - id: logs
    type: read_files
    store: Logdateien
    pattern: "{yyyy-MM-dd}_*.log"
    days: 2
    include: [ERROR, WARN]
  - id: check
    type: agent
    model: Claude Sonnet
    input: [logs]
    checks:
      - name: Interface
        severity: error
        instruction: Were there connection losses or timeouts to interface X?
  - id: verify
    type: verify
    of: check
    next:
      - if: verify.unclear > 0
        to: check
        maxRounds: 2
  - id: mail
    type: send_mail
    if: verify.errors > 0 || verify.warnings > 0
    input: [verify]
    subject: Findings in the log files
```

## Eine Kette von Prompts

```yaml
  - id: summary
    type: prompt
    model: Claude Sonnet
    input: [logs]
    prompt: Summarise the errors of the night for the IT team, grouped by interface.
  - id: review
    type: prompt
    model: Claude Sonnet
    input: [summary]
    prompt: Is this summary clear enough to act on? If not, say what is missing.
    choices: [approve, rework]
    next:
      - if: review.choice == rework
        to: summary
        maxRounds: 2
  - id: mail
    type: send_mail
    if: review.choice == approve
    input: [summary]
```

## Dokumente als Zeilen

Rechnungen in einem Ordner (darunter einer je Jahr), jede einmal gelesen, eine Zeile mit Feldern je Dokument, eine Mail,
wenn eine angesehen werden muss:

```yaml
version: 1
name: Eingangsrechnungen
notify: [buchhaltung@example.com]
triggers:
  - type: schedule
    cron: "0 0 22 * * *"
steps:
  - id: documents
    type: read_documents
    store: Buchhaltung
    folder: Eingang
    subfolders: true
    pattern: ["*.pdf", "*.jpg", "*.png"]
  - id: rows
    type: extract
    model: Claude Sonnet
    input: documents
    instructions: |
      Der Partner ist immer die andere Seite - nie die Musterfirma, deren Briefkopf auf ihren eigenen Rechnungen steht.
      Ein Dokument mit mehreren Steuersätzen: unclear setzen.
    fields:
      - {name: kind, type: choice, values: [invoice, credit_note, receipt, other], required: true}
      - {name: date, type: date, required: true}
      - {name: number, type: text}
      - {name: partner, type: text, required: true}
      - {name: net, type: amount}
      - {name: vat_rate, type: percent}
      - {name: gross, type: amount, required: true}
      - {name: extra, type: text, description: "Was in kein anderes Feld passt - ein Skonto, eine Bestellnummer"}
  - id: csv
    type: write_file
    input: rows
    store: Buchhaltung
    file: "Export/{date:yyyy}/eingangsrechnungen.csv"
    dateFormat: dd.MM.yyyy
  - id: mail
    type: send_mail
    if: rows.unclear > 0
    input: [rows, csv]
    subject: Belege prüfen
```

Für einen Lieferschein, einen Prüfbericht oder ein Formular sind es dieselben Felder - nur ihre Namen ändern sich.
