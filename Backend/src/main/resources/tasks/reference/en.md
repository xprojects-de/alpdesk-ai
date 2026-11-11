A task is written in **YAML** (JSON works too). It reads the new lines of files on a file store, lets the
tenant's chat model check them against check points, verifies the findings and mails them - only when something is
wrong, or when a run failed or stayed unclear.

## The task

| Field | |
|---|---|
| `version` | always `1` |
| `name` | the task's name, unique in the tenant |
| `notify` | e-mail addresses that hear of a failed or unclear run (and get a `send_mail` without `to`) |
| `triggers` | when it runs by itself - left out: only by hand, the client software or MCP |
| `steps` | the steps, run from top to bottom |

## Triggers

`type`: `schedule` with **either** `every` (`15m` to `7d`, e.g. `30m`, `5h`, `1d`) **or** `cron` (six fields:
second minute hour day month weekday, e.g. `"0 30 6 * * MON-FRI"`). A task runs at most every 15 minutes.

## Fields of every step

`id` (lower case letters, digits, `_`), `type`, `input` (earlier steps it reads), `if` (skipped when false),
`next` (where to go on: entries with `if`, `to` and - going back - `maxRounds`).

## Step types

- **`read_files`** - `store` (the file store's name), `folder` (below the store's folder), `pattern` (a file name
  with `*`, `?` and dates like `{yyyy-MM-dd}`), `days` (the dates of today and the days before, 1-31), `onlyNew`
  (default `true`: only what is new since the last successful run), `firstRun` (`start`, the default, or `end`: the
  task's first run reads nothing and only what is written after it counts - for logs with a long past; files that
  appear later are read from their start), `minLines` (optional: a file that must keep growing, e.g. a cron's
  heartbeat - fewer new lines fail the run, and the notify list is mailed why), `include` / `exclude` (texts a line must or must not contain), `mask`
  (values replaced with `***` before a line is kept - nothing masked reaches the model or the mail; each entry is
  `after: TEXT` - the value after the text (spaces in between skipped) up to the next `"`, `'`, `&`, `<`, `>`, `,`,
  `;` or space - or `regex: EXPRESSION` - every match; e.g. `- after: 'ApiPassword="'`, `- after: 'password:'`
  for `password: s3cret`, in an HTML-escaped log
  `- after: 'ApiPassword=&quot;'`).
- **`read_documents`** - whole documents (PDF, DOCX, ODT, RTF, TXT, Markdown, HTML, PPTX, XLSX, ODS, CSV; images and
  scanned pages read by the tenant's image model), each read once - again only when it changed: `store`, `folder`,
  `pattern` (a file name with `*` and `?`, or a list of them, e.g. `["*.pdf", "*.jpg"]` - upper and lower case alike;
  `"*"` every file), `subfolders` (default `false`; `true`: the folder's subfolders too, e.g. one per year). At most
  50 documents a run, the oldest first - the rest come with the next run. A document that cannot be read is kept with
  its reason, the run goes on.
- **`extract`** - one row per document of a `read_documents` step (`input`): `model`, `instructions` (optional - e.g.
  the company's accounting rules, or which party is the partner), `knowledge` (default `false`; `true`: the model may
  search the company knowledge), `fields` - the table's columns, not the document's layout: the model finds each value
  wherever the document has it. Each field: `name` (lower case, digits, `_`), `type` (`text`, `date`, `amount`,
  `number`, `percent`, `choice` with `values: [word, ...]`), `required` (default `false`), `description` (what it is).
  The engine checks every value; a row missing a required field, with a value not of its type, or from a document that
  could not be read is `unclear` with the reason. A field like `extra` (`text`) keeps what fits no other column.
- **`write_file`** - the rows of an `extract` step (`input`) as a CSV in a store whose switch lets tasks write: `store`,
  `file` (below the store's folder, ending in `.csv`; placeholders `{yyyy}`, `{MM}`, `{dd}` - the run's date,
  `{date:yyyy}` - a date field's year of the row, `{kind}` - a choice field's word), `mode` (`append`, the default: a row
  whose document is in the file already is skipped; `replace`: only this run's rows), `separator` (`;` default, or
  `,`), `decimal` (`,` default, or `.`), `dateFormat` (default `yyyy-MM-dd`, e.g. `dd.MM.yyyy`). The columns: the
  fields, then `unclear` and `source`; fields added at the end of the list are taken, another change needs a new file.
  Two tasks never write the same file. The folder must exist.
- **`call_tool`** - the rows of an `extract` step (`input`) sent to a tool of the connected software: `connection`
  (its name on the page "Software"), `tool` (one turned on), `each` (`row`, the default: one call per row; `all`: one
  call with every row as its list `rows`), `with` (fixed arguments, e.g. `journal: received`). A row's fields go by
  their names, plus `source` (the document's path and checksum - the software recognises a row sent again by it).
  Unclear rows are never sent. A refused call marks its row; the run ends unclear and the notify list hears of it.
  **Without `input` it is a source** - the first step, calling a reading tool: with `fields` (typed as `extract`'s) and
  `key` (the field that makes a row unique) its answer, a JSON array of objects, becomes rows (at most 200 an answer);
  with `key` and `file: {tool: <a tool giving one file>, with: {...}}` each listed object's file is fetched by its key
  and read like a document (for an `extract` step). `{lastRun:yyyy-MM-dd}` in `with` is the start of the task's last
  successful run (empty the first time) - e.g. `since: '{lastRun:yyyy-MM-dd}'`.
- **`call_agent`** - the rows of an `extract` step (`input`) sent to an A2A agent of the connected software (the
  admin allows A2A for the tenant): `agent` (its connection's name), `skill` (optional, one of its card's skills),
  `each` (`row`, the default: one message per row; `all`: one message with every row as its list `rows`), `prompt`
  (what the agent is to do with them, up to 2,000 characters - sent as text before the data), `with` (fixed values).
  A row's fields go by their names as data, plus `source`. Unclear rows are never sent. A refused message, the agent
  asking back (a task has no person to answer) or no end within the agent timeout marks the row; the run ends unclear.
- **`agent`** - `model` (a chat model of the tenant), `input` (read_files steps), `instructions` (optional),
  `checks`: each with `name`, `severity` (`error` or `warning` - a problem the lines show; or `report` - something to
  hand on, e.g. "list every other error": its note goes into the mail whenever there is anything, `verify` leaves it
  as it is) and `instruction`.
- **`verify`** - `of` (the agent step whose findings it judges again), optional `model` and `instructions`. Its `model`
  may be a cheaper one than the agent's (left out: the agent's).
- **`prompt`** - `model` (a chat model of the tenant), `prompt` (what the model is to do), `input` (any earlier steps:
  files read, answers of earlier prompts, findings), optional `choices` (2-10 words, e.g. `[approve, rework]`). It
  answers in free text - the next `prompt` takes the answer as its input, so prompts form a chain. With `choices` it
  also picks one word, which a condition reads: `review.choice == rework`. Sent back by an edge, it sees the answer that
  sent it back. **`assistant: true`** answers it as the company's assistant: it searches the company knowledge and the
  knowledge areas named in `areas` (names; none: the company knowledge only), uses the reading tools of the connected
  software named in `software` (names - A2A agents too, with the admin's A2A switch), searches the web with
  `web: true`, and follows the instructions of the chat template named in `template`. A company whose chat answers
  through its A2A agent has the step answered by that agent; its asking back fails the step. There `model` may be left
  out - the agent gets the `prompt` and the inputs, no model is asked.
- **`send_mail`** - `input` (agent, verify, prompt, extract, write_file or call_tool steps giving rows - a prompt's answer goes into
  the mail; an extract step's or a call_tool source's unclear rows with why, and how many documents and rows the run had), `to` (left out:
  `notify`), `subject`.

## Conditions

`<step>.<count> <op> <number>`, joined with `&&` and `||` - e.g. `verify.errors > 0 || verify.warnings > 0`.
Counts: `files`, `lines` (read_files); `files` (read_documents); `rows`, `unclear` (extract); `rows`, `skipped` (write_file); `sent`, `failed` (call_tool sending, call_agent), `rows`, `unclear` or `files` (call_tool as a source); `errors`, `warnings`, `reports`, `unclear`, `ok` (agent, verify); `chars` (prompt). A
prompt step with `choices`: `<step>.choice == <word>` or `!=`. Operators:
`> >= < <= == !=`.

## Loops

An edge back (`to` an earlier step) needs `maxRounds` (1-5): how often it goes back. A finding still unclear at the
limit ends the run as unclear and mails `notify` - so does a check point the last agent or verify step left unclear,
edge back or not.

## Comments and long texts

`#` starts a comment; `|` starts a text over several lines. Anchors and aliases (`&`, `*`) are not used.

## Example

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

## A chain of prompts

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

## Documents into rows

Invoices dropped into a folder (one per year below it), each read once, a row of fields per document, a mail when one
needs a look:

```yaml
version: 1
name: Invoices received
notify: [accounting@example.com]
triggers:
  - type: schedule
    cron: "0 0 22 * * *"
steps:
  - id: documents
    type: read_documents
    store: Accounting
    folder: Inbox
    subfolders: true
    pattern: ["*.pdf", "*.jpg", "*.png"]
  - id: rows
    type: extract
    model: Claude Sonnet
    input: documents
    instructions: |
      The partner is always the other party - never Musterfirma, whose letterhead is on its own invoices.
      A document with several VAT rates: set unclear.
    fields:
      - {name: kind, type: choice, values: [invoice, credit_note, receipt, other], required: true}
      - {name: date, type: date, required: true}
      - {name: number, type: text}
      - {name: partner, type: text, required: true}
      - {name: net, type: amount}
      - {name: vat_rate, type: percent}
      - {name: gross, type: amount, required: true}
      - {name: extra, type: text, description: "What fits no other field - a discount, an order number"}
  - id: csv
    type: write_file
    input: rows
    store: Accounting
    file: "Export/{date:yyyy}/invoices-received.csv"
    dateFormat: dd.MM.yyyy
  - id: mail
    type: send_mail
    if: rows.unclear > 0
    input: [rows, csv]
    subject: Documents to look at
```

The fields are the same for a delivery note, a test report or a form - only their names change.
