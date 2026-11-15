---
name: alpdesk-happy-path
description: "Runs the happy path of every feature of Zirmo end to end against the test stack devtools/general-chat-test, built from the working tree - once with WAM sign-in (Authelia in front) and once without: the stack's checks without a model (check.py: sign-in, the machines at /api/v1, /mcp and /a2a, OAuth sign-in, file store and knowledge source, mails), the browser walk through every page as admin, tenant admin and user with a real model (walk/walk.md: tenants, models, users and groups, company knowledge with every file type, assistant, connected software, keys, file stores, tasks, handbook, branding, the chat with files, code, images, audio, video, software, web search), the conversations with a real model (scenarios.py), the file store specs against real servers and the jar's smoke test. Reports per feature of walk/features.md and per mode: ok, failed (with what was seen), not run. Not a review: it changes no code and reads no code, it runs the software. Use only when the user invokes /alpdesk-happy-path."
disable-model-invocation: true
---

## What this does

The reviews read the code; the specs run it against fakes. This skill **runs the software as it is used**: the app
built from the working tree, behind Caddy, with a real model, clicked in a browser and asked by machines - and says for
every feature whether its happy path worked, **with WAM and without**. It finds what the others cannot: a dialog that
does not save, a page that does not show the stream, a real model calling a tool differently than the fake.

It changes no code and commits nothing. A failure is reported, not repaired.

The parts, all in `devtools/general-chat-test/` (its `README.md` is the stack's guide - read it first):

| Part | What | Model |
|---|---|---|
| 1 Build | frontend, backend image, the stack in the mode, its setup | - |
| 2 `check.py` | sign-in, machines, OAuth, file store, mails - one line per check | none |
| 3 Browser walk | `walk/walk.md`, acts A to E, in the Browser pane | real |
| 4 `scenarios.py` | the general chat over the topics, the machines, the scheduled task | real |
| 5 Specs and smoke test | the file stores against real servers, the jar started like the container | none |

`walk/features.md` lists every feature of the software and where its happy path runs. It is the report's backbone.

## Step A - what to run

Unless the arguments say it (`alles`, `wam`, `plain`, `ohne-modell`, a list of acts or areas like `B C` or `tasks`),
**ask** with `AskUserQuestion`, one question, header "Umfang", "Was soll der Happy-Path-Lauf prüfen?":

- "Alles, mit und ohne WAM (Recommended)" - every part in both modes; with a real model, a few euros of tokens at the
  provider and several hours
- "Alles, nur ein Modus" - every part in the mode the stack is in
- "Ohne Modell" - parts 1, 2 and 5 in both modes: no provider cost, about half an hour; the walk and the scenarios are
  reported as not run
- "Nur bestimmte Bereiche" - the user names acts or areas; the others are reported as not run

Say in one line what will run and that the walk's downloads are the stack's own invented files.

## Step B - before starting

1. **Never the developer's dev database** (CLAUDE.md): this stack has its own PostgreSQL, compose project
   `alpdesk-ai-general-chat-test`. Never `./gradlew run`, `devDb*`, `127.0.0.1:15433` or `Backend/database/`. Never stop
   or remove a container outside this compose project.
2. Podman runs (`podman machine list`); if not, tell the user - do not start it.
3. No other session works on the stack or builds in this checkout (`ListAgents`, `pgrep -fl GradleWorkerMain`): the
   stack has one database per mode and one browser.
4. JDK 25: `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` in every Gradle command.
5. The provider keys come from the developer's keys file by themselves: pass `KEYS_FILE=../../local-data/keys` (from
   `devtools/general-chat-test/`; the folder is not committed) to `setup.py models` and `setup.py walk` unless the user
   names another file. The scripts read the lines they need and put them into the stack. **Never open, print, grep or
   type a provider key yourself** - not the file, not in a page, not in a command; whether a key is there shows in the
   script's output ("left out: ..."). Only when the file is missing: ask for its path, or for the keys to be entered
   by hand on "Globale API-Keys" - or run without a model.
6. The walk's files exist (`walk/files/`); after a change of `walk/make.py` run it.

## Step C - build (once, then per mode)

From the repository root, in the background where it takes minutes:

```bash
cd Frontend && npm run deploy && cd ../Backend && ./gradlew copyFrontend
cd ../devtools/general-chat-test && ./mode.sh wam          # or plain - the mode to run
WALK_SANDBOX_IMAGE=$(podman image exists localhost/alpdesk/alpdesk-ai-sandbox:video && echo localhost/alpdesk/alpdesk-ai-sandbox:video || echo localhost/alpdesk/alpdesk-ai-sandbox:general-chat-test) podman compose up -d --build
python3 -c "import stack; stack.wait_until_up()"
```

- The video sandbox is used when its image exists (`./gradlew videoSandboxBuild`, the first build about an hour - never
  start that build yourself); without it the walk's video step is not run.
- **The app does not come up** and its log (`podman logs general-chat-test-app`) names a migration's checksum or
  description: a folded migration of the version being worked on changed, the mode's database is from before. The fix
  deletes this mode's test data (`README.md` "Stop": `podman compose down`, remove the mode's `pgdata` and `files`
  volumes, up, every setup step again). **Ask the user before doing it** - it is their test stack - and say that the
  provider keys come back through `KEYS_FILE` (step B5).
- Setup, each step only when the mode's state file (`state.json` / `state-plain.json`) lacks its part: `base`
  (`topics`), `full` (`full` - and again when a topic there has no `described`: a stack from before templates had
  descriptions, `check.py` expects them), `knowledge` (`machines`), `stores` (`stores`), `models` (`chatModelId` - with `PROVIDER`,
  `CHAT_MODEL` as the user named them, default `PROVIDER=ANTHROPIC CHAT_MODEL=claude-sonnet-5-5`, and the `KEYS_FILE`
  of step B5),
  `stores` again when `stores.taskId` or `stores.taskDescribed` is missing, `walk` (`walk`). `podman restart general-chat-test-authelia` after a
  change of its users file.
- The A2A demo agent for the walk's step B11 and `check.py`'s agent part, in the background:
  `TEST_AGENT_DEMO=1 TEST_AGENT_PORT=19197 TEST_AGENT_ADDRESS=http://host.containers.internal:19197 ../a2a-test-agent/start.sh`
  - when the run ends: `python3 setup.py agent-off` (as long as its connection is there, `check.py` expects the agent
  reached), then stop it.
- A stack whose containers were recreated (`mode.sh`, a rebuild) has lost the OAuth fake's clients and the SFTP
  server's folders: `python3 setup.py stores` and `python3 setup.py oauth` before `check.py` (README "Two modes").

## Step D - run, per mode

In this order; a part that fails does not stop the others unless they build on it.

1. **`python3 check.py`** - must end with `all checks passed (<mode>)`. It stops at its first failure: report that
   check, then decide whether the walk still makes sense (a failed sign-in: no; a failed mail: yes).
2. **The agent's part**: `python3 setup.py a2a`, `python3 check.py` (now the lines of "The general chat answered by the
   A2A agent" run), then `python3 setup.py full` again so the topics are asked by the model (both may run again and
   again).
3. **The browser walk** - `walk/walk.md`, read in full first. Before its first step tell the user in one line that the
   walk of this mode starts now and how to watch: the Browser pane of the app (Cmd+Shift+B or the globe icon), or their
   own browser at `https://ai.general-chat.localhost:8443` as the admin - not as `walter`, `ute` or `vera`, whose
   sessions the walk uses. Do not wait for an answer. Its rules are binding: the people, how another person
   comes to the browser, the upload script, what a failed step is. Work through the acts A to E in the Browser pane
   (`mcp__Claude_Browser__*`; batch what can be foreseen), one line per step in your notes: `B4 ok` or `B4 FAIL - what
   was seen`. Skip a step of the other mode; a step that needs what `setup.py walk` left out is `not run`. Passwords
   typed are only those `walk.md` and the stack's files name, on the stack's hosts (`*.general-chat.localhost`) only.
   Act E always runs, also after failures: the walk's tenant must be gone. The pane at 1280 px; the pane refuses the
   handbook's sandboxed frame - ask the user to look at it once in their own browser, or report it unclear. Where a
   step has the admin act while a person stays signed in, the admin goes through the API (walk.md "One browser holds
   one session") - say so in the notes.
4. **`python3 scenarios.py`** - its report in `local-data/measurements/general-chat/scenarios-<date>-<time>.md` (a repeat with `--only` writes its own `...-only.md`); every step with a check
   that says NO is a failure to look at (its answer is in the report). A model that words a question differently is
   not a fault of the software: ask the scenario once more (`--only <id>`) before reporting it.
5. Once, not per mode - from `Backend/`, in the background:
   `FILE_STORE_TEST=1 ./gradlew test --tests "de.alpdesk.controller.filestore.*"`, and the smoke test as CLAUDE.md
   describes it (an own PostgreSQL container on another port, removed afterwards) when the jar was built - after
   `./gradlew compileJava --rerun-tasks`: a jar from an incremental build may lose `/login` in the API description and
   fail the smoke test.

Then the other mode: `./mode.sh <other>`, build (the images are the same - `podman compose up -d`), `setup.py stores`
and `oauth` (above), setup for that mode, steps 1 to 4 again. A mode whose database does not start (a checksum of the
folded migration) needs the reset of Step C - ask first. At the end leave the stack in the mode it was in when the skill started.

## Step E - report (German)

Write `local-data/measurements/happy-path/<date>-<time>.md` (English; local, ignored by git - a report names keys' hints and the stack's values) and answer the user in German:

1. **Ergebnis in einem Satz**: how many features ran ok in each mode, how many failed, how many did not run.
2. **Fehlgeschlagen** - one entry per failure, the worst first: the feature ids, the mode(s), the step or check, what
   was expected, what was seen (the page's text, the request and its status, the log line of
   `podman logs general-chat-test-app`), a screenshot for a page. Say whether it fails in both modes.
3. **Nicht gelaufen** - what was left out and why (no image model, no video sandbox, part not chosen).
4. **Tabelle** per area of `walk/features.md`: features, ok WAM, ok plain, failed, not run.
5. **Nur durch Specs gedeckt** - the count and the ids from `features.md`: what this run does not prove.
6. **Die Liste pflegen**: steps whose description no longer fits the page (they passed, but read differently), and
   features found on a page that `features.md` does not list.

Rules for the report:
- A feature is **ok** only when its step or check ran in this run and showed what it expects. Never "ok" from an
  earlier run, from the code or from a spec.
- A failure is reported as seen. Do not look for its cause in the code unless the user asks - then say so and stay
  with the lines read.
- What the walk could not judge (a frame's content not readable, a model's answer without its marker twice) is
  "unklar" with what was seen, not ok.
- Nothing is fixed, nothing committed. Offer the fixes as the next step.

## Keeping it complete

The skill is only as good as its lists. When a run shows a feature without a line, or CLAUDE.md / the architecture
reference names one, add it to `walk/features.md` - with its step in `walk/walk.md`, its check in `check.py` or its
scenario in `scenarios.py`, or as "specs only" - and say so in the report.
