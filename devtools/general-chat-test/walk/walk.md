# The browser walk

The happy path of every feature that has a page, clicked in a real browser against this test stack - once with WAM
(`./mode.sh wam`), once without (`./mode.sh plain`). It is the list the skill `/alpdesk-happy-path` walks; a person can
walk it too. `features.md` says for every feature of the software where its happy path runs: here, in `check.py`, in
`scenarios.py`, or in a spec.

**A new or changed feature with a page gets its step here in the same change** (and its line in `features.md`) - as a
feature of a machine interface gets its check in `check.py`.

Everything is invented (Musterfirma, example.com). The walk works in a tenant of its own, **Musterfirma Rundgang**,
made in act A and deleted in act E: it leaves nothing behind and runs again and again. The topic tenants of `setup.py`
are only looked at.

## Before the walk

- The stack runs in the mode to walk, built from the working tree, and `python3 check.py` passes.
- `setup.py base`, `full`, `knowledge`, `stores`, `models`, `stores`, `walk` have run in this mode (the state file names
  `chatModelName` and `walk`). What `walk` left out (image model, transcriber) is reported as **not run**, never as ok.
- The A2A demo agent runs on this machine for B11 (the stack's README, "The general chat answered by an A2A agent").
- A tenant "Musterfirma Rundgang" left from a broken walk is deleted first (act E).
- The Browser pane at 1280 px (`resize_window`): at its own width the sidebar lies over the page.

## How to walk

- Address: `https://ai.general-chat.localhost:8443`. Mails: `https://mail.general-chat.localhost:8443`.
- **People** (password of every person `Testpasswort-2026`; the app's admin `admin` / `Admin-Testpasswort-2026`):

  | Person | WAM (Authelia, `authelia/users_database.yml`) | plain |
  |---|---|---|
  | the admin | sign in at Authelia as `betrieb`, then "Als Administrator anmelden" | the login page |
  | `walter` | no IDM group: waits, the admin makes him the walk tenant's admin (A9) | created by the admin (A9) |
  | `vera` | no IDM group: waits, assigned inactive, approved by walter (B3) | created by walter, deactivated, activated (B3) |
  | `ute` | IDM group `AI-Rundgang`: gets her user through the group "Rundgang-Team" (A11) | created by walter (B3) |
  | `anna` | a user of Thema Wartung, added to the walk tenant by the admin (A10) | the same |

- **Another person at the browser.** WAM: open `https://auth.general-chat.localhost:8443/logout`, then the app's
  address - Authelia asks for the next person. When the person before logged out in the app, the tab keeps "Sie sind
  abgemeldet" for the next one too: "Erneut anmelden". Plain: the user menu's logout, then the login page named in the
  step.
- **One browser holds one session.** The app keeps its token in the browser's storage, shared by its tabs: an admin
  login in a second tab ends the user's session in the first (the other tab reloads), and a WAM sign-in of another person
  ends the admin's. Where a step has the admin act while a user stays signed in (A9's first part, B21, D4), the admin
  does it through the admin API (`stack.admin()` with `setup.call`) or in another browser - say so in the notes.
- **Uploads.** The pane has no upload tool. The walk's files are served at `https://files.general-chat.localhost:8443/`
  (the folder `walk/files`, made by `walk/make.py`); this script lays them into the page's upload field as a person's
  file dialog would. **Click the page's upload button first** ("Dokumente hochladen", "ZIP importieren", the paperclip,
  "ZIP hochladen" in its dialog) - the page takes the target there (the area of the filter, the open conversation); a
  script without the click puts a file into the company knowledge. The click opens no dialog in the pane; then:

  ```js
  const names = ["betriebsanweisung-hebebuehne.md"];          // the files of the step
  const input = [...document.querySelectorAll("input[type=file]")].at(-1);
  const files = new DataTransfer();
  for (const name of names) {
      const blob = await (await fetch("https://files.general-chat.localhost:8443/" + name)).blob();
      files.items.add(new File([blob], name, {type: blob.type}));
  }
  input.files = files.files;
  input.dispatchEvent(new Event("change", {bubbles: true}));
  ```

  Do not read the list again after the event - the page empties the field once it took the files. The upload counts
  when its request answered 2xx.
- **Finding things.** A tab of the admin's tenant page is opened by its address (`/dashboard/tenants/<id>/<tab>`),
  which also shows that a deep link holds. Look an element up again (`find`) right before clicking it: a dialog that
  scrolls or a page that grows moves it. A form is sent with its button - Enter in a field does not always send it in
  the pane.
- **Downloads** are the stack's own invented files; a download counts when its request answered 200 (the network
  list), the file need not be opened. A copy button needs a real click (`computer`): the clipboard refuses a script's.
- **Snackbars** (a refusal's message, a check's reason) stay a few seconds: read them right after the click.
- **The handbook's frame** (B18, C19): the Browser pane refuses frames sandboxed without `allow-same-origin`
  (`net::ERR_BLOCKED_BY_CLIENT` in the network list) - check the website's address answers 200 with its marker, and the
  frame itself once in a normal browser (the person's own).
- **Every step ends with a look**: the text the step expects is on the page (`get_page_text` / `find`), no failed
  request (`read_network_requests`: nothing 4xx/5xx the step did not ask for), no error in the console. A step that
  expects a refusal names its message.
- **A step fails** when the expected result does not show. Note it with what was seen (a screenshot) and go on with the
  next step that does not depend on it; never repair the software during the walk. A step whose page looks different
  than described but does what is expected passes - and the description is corrected afterwards.
- **Answers of the model** are checked by their marker (a number, a code, a file name), never by their wording. An
  answer without its marker is asked once more in a new conversation before it counts as failed.
- A step marked **WAM** or **plain** is walked in that mode only; every other step in both.

## A - The admin

| Step | Do | Expect | Features |
|---|---|---|---|
| A1 | Open the address without a session. WAM: sign in at Authelia as `betrieb` - he lands in the general chat as its tenant admin; log out in the user menu ("Sie sind abgemeldet"), then "Als Administrator anmelden", `admin`. Plain: the login page shows no brand; sign in as `admin`. | The dashboard with the installation's name and logo, the tenants' list. | AUTH-01, BRAND-01, BRAND-04, OPS-01 |
| A2 | Open "Thema Wartung", click through every tab (Übersicht … Branding). Reload the browser on the tab "Software". | Each tab shows its content, the address carries the tab, the reload lands on the same tab. | TEN-01, TEN-10, TEN-12, OPS-05 |
| A3 | "Neuer Mandant": name `Musterfirma Rundgang`, search language German, the switches for global keys, tasks and A2A agents on, sandbox address `http://sandbox-rundgang:8080`, token `general-chat-test-sandbox-token-rundgang-001`. Save. | The tenant is in the list and opens. | TEN-02, TEN-04, TEN-05, TEN-06, TEN-07 |
| A4 | On its page: "Sandbox prüfen". | One instance, reachable ("Video" when the stack runs the video sandbox). | TEN-08 |
| A5 | Tab "Modelle": assign the chat model, "bge-m3 (Stack)", "Websuche (Fake)" and - when `setup.py walk` made them - the image model and the transcriber. | The overview lists them. | TEN-09 |
| A6 | Tab "Branding": name `Rundgang AG`, a colour, the logo `logo.png`. Note the login link; "Neuer Link", note the new one. | Name, colour and logo are shown; the link changed. Plain: the old link opens the neutral login page, the new one a page with "Rundgang AG" and the logo. | BRAND-02, BRAND-05, AUTH-03, AUTH-05 |
| A7 | "KI-Modelle": add a chat model `Rundgang-Testmodell`, provider Ollama, address `http://embeddings:11434`, model name `rundgang-test`, prices, a price tier with cache prices, "Kann Werkzeuge nutzen" off. Change its display name. Deactivate it. Delete it. Then try to delete "bge-m3 (Stack)". | Each change shows in the list; the embedding model in use is refused with its reason and stays. | MOD-01, MOD-05, MOD-08, MOD-09, MOD-12 |
| A8 | "Globale API-Keys". Replace the Tavily key by `tvly-general-chat-test-fake` (the fake's - any other provider's key is never typed). | Every provider with a key shows its hint only; the Tavily entry is saved. | GKEY-01 |
| A9 | **WAM:** first let `walter` and `vera` try: as each, open the app (they wait: "Zugang angefragt") - their sign-in ends the admin's session in this browser, so sign in as the admin again afterwards. Back as the admin: "Zugangsanfragen" lists both; assign `walter` to Musterfirma Rundgang as tenant admin, active (one click on each switch - the switch's state is read after the dialog settles); assign `vera` as a user, not active. **Plain:** tab "Benutzer" of the tenant: "Neuer Benutzer" `walter`, display name "Walter Rundgang", tenant admin, password `Testpasswort-2026`. | WAM: the requests are gone from the list, both are users of the tenant (vera "Wartet auf Freigabe"). Plain: walter is listed. | AUTH-07, AUTH-08, AUTH-11, USR-02, UI-03 |
| A10 | Tab "Benutzer": "Benutzer aus anderem Mandanten" - `anna` of Thema Wartung. Plain: set her password here to `Testpasswort-2026`. | anna is a user of both tenants. | TEN-13, USR-04 |
| A11 | Tab "Benutzer", groups: new group `Rundgang-Team`. **WAM:** with the WAM group `AI-Rundgang` (the member picker goes). | The group is listed with "WAM-Gruppe: AI-Rundgang" next to "Standard", which every tenant has (without a WAM group). | GRP-01, GRP-06, GRP-07 |
| A12 | "Verbrauch": this month. "Anwendungslog": filter errors only, search a word, open a row; "Tagesbericht jetzt senden". Open the mail page. | The usage lists tenants with tokens and amounts; the log filters; the report goes out without a question, the snackbar names one recipient; the newest mail is the daily report to `betrieb@example.com`. | USE-01, MON-01, MON-02, MON-03 |
| A13 | Open `/swagger`. Open `/dashboard/nichts`. Switch the language in the user menu and back; switch to dark and back to light. | Swagger shows "API v1", "Users - sign-in", "Users"; the not-found page with its way home; the texts change language; the page changes its colours and stays readable. | OPS-03, UI-02, UI-06, UI-08 |

## B - The tenant admin (walter)

| Step | Do | Expect | Features |
|---|---|---|---|
| B1 | As `walter`. WAM: Authelia - signed in at once. Plain: the tenant's login link of A6, name and password. | The tenant area with "Rundgang AG", its logo and colour; the menu has the settings pages. | AUTH-02, AUTH-03, AUTH-14, BRAND-03 |
| B2 | "Firmenwissen": choose the embedding model "bge-m3 (Stack)". | Saved; uploads are possible. | KNOW-14 |
| B3 | "Benutzer". **Plain:** create `ute` ("Ute Rundgang", password, group Rundgang-Team) and `vera` (password); deactivate vera, activate her again. **WAM:** there is no "Neuer Benutzer", only its note; approve vera ("Wartet auf Freigabe" → active). Both: change vera's display name and language; new group `Leitung-Gruppe` with walter as member; try to take the admin right from walter himself; copy the login link (a real click - the clipboard refuses a script's). | The list shows the changes; taking his own right is refused with its message; the link is the one of A6. | USR-01, USR-02, USR-03, USR-05, USR-07, USR-08, AUTH-12, GRP-01, GRP-02, UI-04 |
| B4 | "Firmenwissen": "Bereiche verwalten" - new area `Leitung`. In the group dialog of Leitung-Gruppe give it the area. With the area filter on "Firmenwissen", upload into the company knowledge, one upload: `betriebsanweisung-hebebuehne.md`, `urlaubsregelung.docx`, `ersatzteile.xlsx`, `lieferzeiten.csv`, `pruefprotokoll-bt220.pdf`, `scan-quittung.png`. Set the filter to Leitung and upload `geheim-gehaltsband.md` - the filter decides where an upload goes, no dialog asks. Wait. | Every document "bereit" (the scan read by the chat model); the list shows area and status; the filter by area works. | KNOW-01, KNOW-02, KNOW-03, KNOW-05, KNOW-10, KNOW-11, GRP-03 |
| B5 | "ZIP importieren": `wissen-archiv.zip`. Import the same ZIP again. | First: three created with their folders, `nicht-lesbar.exe` skipped with its reason. Again: three unchanged. | KNOW-04 |
| B6 | Move `lieferzeiten.csv` to the area Leitung and back. Delete `pruefprotokoll-bt220.pdf`. Choose another search language and German again. "Alle Dokumente neu einbetten" (confirm) and wait. | Each change shows; after embedding again everything is "bereit". | KNOW-07, KNOW-08, KNOW-12, KNOW-15 |
| B7 | "Assistent": instructions `Beende jede Antwort mit dem Wort RUNDGANG-ENDE.`; answer source "documents first"; creativity, reasoning, eight search results; the default model; the image model (when there is one). New template `Kurzmeldung` with the instructions `Antworte in höchstens zwei Sätzen und beginne mit KURZ:`. | Saved - a reload shows the same values; the template is listed. | ASST-01, ASST-02, ASST-03, ASST-04, ASST-05, ASST-06, ASST-07 |
| B8 | "Software": add `Testsoftware`, address `http://mcp-software:8000/mcp`. Turn on `list_invoices`, `pdf_report`, `image`, `echo`, `cancel_order`; `cancel_order` is marked "ändert etwas". Turn a prompt on. "Werkzeuge neu laden". Turn on "Direkt an MCP-Clients". Open the access list. Add a second software `Zweitsoftware`, the same address, only `echo` on - the chat shows its topic chips from two reachable software on (C15). | Tools, prompts and the software's instructions are shown; the reload keeps the choices; the access list names the groups. | SW-01, SW-02, SW-03, SW-04, SW-05, SW-06, SW-07 |
| B9 | "Benutzer": group "Standard" - level "Lesen und ändern" for Testsoftware, "Nur lesen" for Zweitsoftware, web search on; group "Rundgang-Team" the same. | The groups' column "Software" shows the level. | GRP-04, GRP-05 |
| B10 | "Software": add `Ticketsystem`, address `http://oauth-fake:9100/mcp`, no key. "Anmelden" - the page "Ticketsystem Musterfirma" asks for `tickets:read tickets:write` - "Zulassen". Turn `list_tickets` on. Then remove the sign-in. | First "Anmeldung nötig"; after the consent page the browser is back on the page Software, the chip says "Angemeldet", the tools are read; removed, it needs a sign-in again. | SW-09, SW-10 |
| B11 | "Software": add an A2A agent `Auftragsassistent`, address `http://host.containers.internal:19197/a2a`. Its check button. Turn on "Beantwortet den Chat"; in the chat ask `Wie steht Auftrag 4711?`; turn it off again. | The card's skills are shown, the check names them with its time; the chat has no model picker, the skills are its chips, the answer names 4711 with its steps; off again the chat has its model picker. | SW-11, SW-12, SW-14, CHAT-29 |
| B12 | "API-Keys": the tenant's own Tavily key `tvly-general-chat-test-fake` - set, then delete. New client software key `Rundgang-Software` with the area Leitung, the Testsoftware, web search, "Verlauf speichern" on; keep the key shown once. Change its name. New MCP key `Rundgang MCP` with a description, both doors, the Testsoftware, web search; keep it; look at the card (Claude Code command, connector fields, `mcp.json` - named "Rundgang AG"); renew it and keep the new one. | The keys show once and are listed with their rights as tags. With a shell (`stack.with_key` / `stack.mcp`): the API key answers `/api/v1/me` with the tenant and the area; the renewed MCP key answers `initialize` with the server name "Rundgang AG", the old one 401; `tools/list` holds `Testsoftware__list_invoices` and never `cancel_order`. | KEY-01, KEY-02, KEY-03, KEY-04, KEY-06, KEY-07, KEY-08, KEY-09, KEY-10, KEY-11, KEY-12, MCP-01, MCP-02, MCP-12, MCP-14, BRAND-06 |
| B13 | "Dateiablagen": add `Rundgang Ablage`, SFTP, host `sftp`, port 22, user `check`, password `check-secret`, folder `/home/check/ablage`. Check - the fingerprint - confirm - check. Change the folder to `/home/check/nichts`, check, change it back, check. Turn on "Aufgaben dürfen hier schreiben". | First "nicht bestätigt" with the fingerprint, then OK with the folders `wissen` and `logs`; the wrong folder shows its reason and stays listed; the write tag shows. | FS-01, FS-02, FS-07, FS-08, FS-09, FS-10 |
| B14 | "Firmenwissen", knowledge sources: new source `Rundgang Quelle` on the store, the folder `wissen` chosen in the folder picker, area company knowledge, by hand. "Jetzt synchronisieren" (confirm). Open its files dialog. Change its area to Leitung and back. Try to delete the store on "Dateiablagen". Remove the source, keeping its documents. | The sync brings `schmierplan-band-f3.md` in, shown with "Dateiablage: Rundgang Quelle" (the source's name) and "bereit"; the area moves the document; the store in use is refused; after removing the source the document stays as an upload. | SRC-01, SRC-02, SRC-06, SRC-07, SRC-08, SRC-10, SRC-11, FS-11 |
| B15 | "Aufgaben": the "Format" button. "Neue Aufgabe": paste the task below, with a mistake first (`colour: blau` as the third line) - "Prüfen"; take the line out, check, save, active. Duplicate it (an unsaved copy opens in the editor - leave it). Export it. Give the key Rundgang-Software the task (API-Keys). | The reference opens; the mistake is told with line 3; checked, the graph shows three steps; the copy holds the same text; the export is made in the browser (no request - the download shows in a normal browser); the key's tags name the task. | TASK-01, TASK-02, TASK-03, TASK-04, KEY-05 |
| B16 | With a shell, write an ERROR line into the store: `stack.sftp("echo '2026-11-10 08:00:00 ERROR P200 Hydraulikdruck zu niedrig, Code RUNDGANG-HY1' >> /home/check/ablage/logs/p200.log")`. "Jetzt starten". Open the run. Write a second ERROR line (another code), start again and cancel that run at once - the agent step takes seconds. | The first run ends "Erledigt": the graph with each step's state, tokens and cost, the line read, a finding naming RUNDGANG-HY1; the mail "Befund Rundgang" is at the mail page in the look of Rundgang AG; the second run shows "abgebrochen" - no second mail. | TASK-07, TASK-08, TASK-09, TASK-11, TASK-20, TASK-21, TASK-22, TASK-23, TASK-24 |
| B17 | "Aufgaben": "Neue Aufgabe mit KI" - `Lies die ERROR-Zeilen der Ablage Rundgang Ablage im Ordner logs und schick sie mir per Mail.` Do not save it. Delete the ended runs of the first task. | A checked draft appears in the editor with read_files and send_mail; the runs list is empty. | TASK-05, TASK-21 |
| B21 | With a shell: `walk/store-documents.sh`. "Aufgaben": new task with the second task below, save, "Jetzt starten", open the run. Start it again. Turn the admin's switch off for a moment: as the admin (through the API or another browser - see "One browser holds one session"), "Darf Aufgaben anlegen" off - walter's menu - on again. | The run ends "Erledigt" or "Unklar": six documents read (the broken PDF kept with its reason, the scans read by the chat model), a table of rows with typed fields, unclear rows with their reason, the file `belege/export/2026/eingangsrechnungen.csv` written (`podman exec general-chat-test-sftp cat ...` shows a row per invoice), the mail "Belege prüfen" when a row is unclear. The second run reads nothing and writes nothing twice. With the switch off the menu entry "Aufgaben" is gone. | TASK-14, TASK-15, TASK-16, TASK-27 |
| B18 | "Handbuch" ("Websites verwalten", then "ZIP hochladen" - the upload script after it): `handbuch.zip` for everyone; open its preview; `handbuch-v2.zip` for everyone. On "Firmenwissen" a new empty area `Handbuch-Bereich`; upload `handbuch.zip` for it; try to delete that area; delete its website, then the area. | The preview's address answers 200 with HANDBUCH-EINS, after the second upload HANDBUCH-ZWEI (the frame itself: see "The handbook's frame"), the link to the chapter works; the area with a website is refused with its own reason; the website and then the area are gone. | DOC-01, DOC-02, DOC-03, DOC-05, DOC-06 |
| B19 | "Verbrauch". "Konto": the overview and the card "Claude oder Copilot verbinden". **Plain:** change the password to `Testpasswort-2027` with the current one, then back; a wrong current password. **WAM:** no password fields. | The usage lists this session's calls by model with amounts; the card names "Rundgang AG"; plain: both changes are saved, the wrong one is refused with its message. | USE-02, ACC-01, ACC-02, ACC-03 |
| B20 | Ask in the chat `Wie lautet das Kennwort der Gehaltsbänder?` | GEHEIM-9090 - a tenant admin finds every area. | KNOW-13 |

The task of B15 (the model's name as "KI-Modelle" shows it - `chatModelName` of the state file):

```yaml
version: 1
name: Rundgang Logprüfung
notify: [rundgang@example.com]
steps:
  - id: logs
    type: read_files
    store: Rundgang Ablage
    folder: logs
    pattern: "*.log"
    include: [ERROR]
    firstRun: end
  - id: check
    type: agent
    model: <chatModelName>
    input: [logs]
    checks:
      - name: Hydraulik
        severity: error
        instruction: Did the press P200 report a hydraulic error? Name its code.
  - id: mail
    type: send_mail
    if: check.errors > 0
    input: [check]
    subject: Befund Rundgang
```

The second task (B21):

```yaml
version: 1
name: Rundgang Belege
notify: [rundgang@example.com]
steps:
  - id: documents
    type: read_documents
    store: Rundgang Ablage
    folder: belege/eingang
    subfolders: true
    pattern: ["*.pdf", "*.png", "*.docx"]
  - id: rows
    type: extract
    model: <chatModelName>
    input: documents
    instructions: |
      The partner is always the other party - never Musterfirma.
      An order confirmation or a delivery note is no invoice: set kind, leave the amounts you do not see.
      A document with several VAT rates: set unclear.
    fields:
      - {name: kind, type: choice, values: [invoice, receipt, other], required: true}
      - {name: date, type: date, required: true}
      - {name: partner, type: text, required: true}
      - {name: gross, type: amount, required: true}
  - id: csv
    type: write_file
    input: rows
    store: Rundgang Ablage
    file: "belege/export/{date:yyyy}/eingangsrechnungen.csv"
    dateFormat: dd.MM.yyyy
  - id: mail
    type: send_mail
    if: rows.unclear > 0
    input: [rows, csv]
    subject: Belege prüfen
```

`firstRun: end` - the store's log holds the lines of earlier runs of `scenarios.py`; the task reads only what comes
after it was first started. Start it once before B16's line is written (it reads nothing, no mail), then write the line.

## C - A user (ute)

| Step | Do | Expect | Features |
|---|---|---|---|
| C1 | As `ute`. WAM: Authelia - she gets her user through the IDM group and is signed in. Plain: the tenant's login link. Open `/app/documents` by its address. | The menu has Chat, Handbuch and Konto only; the settings page sends her to the chat. | AUTH-02, AUTH-13, AUTH-14, AUTH-20 |
| C2 | Ask `Wie viel trägt die Hebebühne H7 höchstens?` | The answer streams; 3200 (or "3.200"); the source `betriebsanweisung-hebebuehne.md`; the tool step above the answer (and "Gedankengang anzeigen" when the model hands its reasoning over); the last word RUNDGANG-ENDE; the conversation gets a title in the list. | CHAT-01, CHAT-03, CHAT-05, CHAT-06, CHAT-26, ASST-01, KNOW-17, GKEY-02 |
| C3 | Ask `Wie lautet das Kennwort der Gehaltsbänder?` | The answer never holds GEHEIM-9090. | KNOW-13 |
| C4 | Ask, each in the same conversation: `Wie viele Urlaubstage hat eine Vollzeitkraft?` - `Auf welchem Lagerplatz liegt der Sicherungsbolzen?` - `Wo ist der Sammelplatz der Halle 1 und wann ist die UVV-Prüfung der Krane?` - `Was steht auf der gescannten Quittung?` | 31 - R1-02 - Tor C and November - a detail of the receipt (its total or shop). | KNOW-03, KNOW-04, KNOW-05, CHAT-04 |
| C5 | New chat. Attach `stoermeldung.txt`, ask `Ist die Last aus der Störmeldung laut Betriebsanweisung zulässig?` Download the file from its chip. | No - 3400 kg is above 3200 kg; the file is in the strip above the input; the download answers 200. | CHAT-09, CHAT-10 |
| C6 | In that conversation: `Schreib dazu eine kurze Meldung als Word-Datei.` Download it. Then delete the uploaded file from the strip. | A `.docx` is offered below the answer and downloads; the uploaded file is gone from the strip (the files the model made stay listed). | CHAT-12, CHAT-10 |
| C7 | New chat. Attach `messwerte.csv`, ask `Berechne mit Python den Mittelwert der Temperatur und zeichne ein Liniendiagramm.` | 22 (22,0 / 22.0); the code steps show; a picture shows below the answer and opens large. | CHAT-14, CHAT-15 |
| C8 | New chat: `Erstelle ein Bild eines grünen Gabelstaplers.` Then `Mach den Gabelstapler rot.` Needs the image model. | A preview below each answer; the second file is the next version. | CHAT-13 |
| C9 | New chat. Attach `sprachnotiz.m4a`, ask `Was wird laut der Sprachnotiz geprüft und wann?` Needs the transcriber. | The lifting platform number seven, 12 March. | CHAT-11 |
| C10 | New chat. Attach `clip.mp4`, ask `Wie lang ist der Clip? Zeig mir ein Standbild aus der Mitte.` Needs the video sandbox. | About three seconds; a still shows below the answer. | CHAT-16, CHAT-15 |
| C11 | New chat: choose the template `Kurzmeldung` and another model if there is one; ask `Was gilt beim Lärmschutz in Halle 2?` | The answer begins with KURZ: and names 85 dB; the conversation keeps the template when reopened. | CHAT-02, CHAT-18 |
| C12 | New chat: `Welche Rechnungen listet die Testsoftware seit Oktober?` Then `Hol mir den PDF-Bericht der Testsoftware.` | A tool step of the Testsoftware and invoices in the answer; a PDF is offered below the second answer. | CHAT-19, CHAT-22 |
| C13 | In that conversation: `Storniere die Bestellung 4711 in der Testsoftware.` - "Abbrechen". Ask again - "Ausführen". | Each time a card with the software, the tool and `4711` waits; cancelled, nothing is done; run, the answer confirms the cancellation. | CHAT-20 |
| C14 | The button "Prompts der angebundenen Software" (icon `hub`) next to the paperclip: the prompt of the Testsoftware, fill its form, "Ins Eingabefeld". | The prompt's text is in the input, not sent. | CHAT-23 |
| C15 | New chat: the chips "Themen" show Testsoftware and Zweitsoftware; switch Testsoftware off, ask `Welche Rechnungen listet die Testsoftware?` Reopen the conversation. | No tool step of the Testsoftware; the chip is still off. | CHAT-21 |
| C16 | New chat: `Gibt es neue gesetzliche Prüffristen für Hebebühnen? Such auch im Web und nenne die Kennung, die auf den Webseiten steht.` | The answer names WEB-4242; pages are linked under the answer. | CHAT-24 |
| C17 | New chat: `Zeig mir ein Python-Beispiel, das eine CSV-Datei liest, und erkläre es ausführlich.` Stop it after the first lines. Reload the page and open the conversation. | Stopping ends the stream; after the reload the stored answer is there; the code block is highlighted with its copy control. | CHAT-07, CHAT-08 |
| C18 | Search the chats for `Gabelstapler` (or `Hebebühne`), open one, delete one. | The list filters; the conversation opens with its messages and files; the deleted one is gone. | CHAT-25 |
| C19 | "Handbuch". | The page asks for the website's address (200) and frames it; the address answers HANDBUCH-ZWEI (the frame itself: see "The handbook's frame"). | DOC-04 |
| C20 | The user menu: German / English and back; dark and light; then the window at phone width (375 px): the chat's bar with "Chats" and the files, the sidebar sliding in. Back to desktop. | The texts change; nothing unreadable in the dark; at phone width the chat is usable without sideways scrolling. | UI-01, UI-05, UI-06, UI-07 |
| C21 | Log out. | WAM: "Sie sind abgemeldet" with "Erneut anmelden" - no new sign-in by itself. Plain: the tenant's own login page. | AUTH-16 |

## D - Several tenants, sessions that end

| Step | Do | Expect | Features |
|---|---|---|---|
| D1 | As `anna`. WAM: she chooses between Thema Wartung and Musterfirma Rundgang (each with its logo) - take Thema Wartung. Plain: at the neutral login page she is refused with the hint; at the walk tenant's login link she gets in. | WAM: the choice, then Thema Wartung's chat. Plain: the refusal, then Rundgang AG's chat. | AUTH-04, AUTH-10 |
| D2 | The user menu: switch to the other tenant (plain: with her password there). Switch the language - then look at her account in the first tenant again. | The other tenant's look and chats, nothing of the first; WAM: the language holds in both tenants. | AUTH-17, UI-01 |
| D3 | **WAM:** with anna's tab open, sign out at Authelia and in as `vera` in a second tab (Authelia sends it on to the app), then look at anna's tab. | anna's tab reloads by itself with vera signed in at Musterfirma Rundgang (the shared token changed) - never anna's chats. | AUTH-15, AUTH-19 |
| D4 | As `ute`, signed in, in one tab. The admin deactivates her - through the API or in another browser (an admin login in this browser would end her session first). Click in ute's tab. Activate her again. | Her next click ends at the login page. | AUTH-18, USR-05 |
| D5 | **Plain:** as the admin take `ute` out of every group; sign in as ute; put her back. | She is refused with "Zugang braucht eine Gruppe" after her right password. | AUTH-06, GRP-08 |

## E - The end (the admin)

| Step | Do | Expect | Features |
|---|---|---|---|
| E1 | Tenant Musterfirma Rundgang, tab "Benutzer": delete `vera`. Tab "Keys": delete the client software key. | Both are gone from their lists. | USR-06, KEY-02 |
| E2 | Tab "Firmenwissen": "Alle hochgeladenen löschen" for the company knowledge. | The uploads are gone. | KNOW-09 |
| E3 | Edit the tenant: deactivate it. Try to sign in as walter. Activate it again. | walter is refused while it is off. | TEN-03, AUTH-18 |
| E4 | Delete the tenant (confirm). WAM: reject any access request left of the walk's people. | The tenant is gone from the list; the people of the walk have no user any more - the next walk starts as this one did. | TEN-11, AUTH-11 |
| E5 | Log out as the admin. WAM: also at Authelia (`/logout`). | The login page. | AUTH-16 |
