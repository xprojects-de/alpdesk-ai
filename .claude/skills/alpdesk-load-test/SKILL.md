---
name: alpdesk-load-test
description: "Runs the capacity measurement of Zirmo (Backend/src/test/groovy/de/alpdesk/capacity/CapacitySpec.groovy) on request: many tenants (creating them, the migration on start, connections and threads of their pools, tenants active at the same moment) and one large tenant (write rate, table and index sizes, the chat's search time as it grows, and the search of users who find only part of the knowledge - knowledge areas, pgvector's scan limit). Asks for the size, runs it in the background on the specs' own Testcontainers database, reads build/reports/capacity/capacity.md and explains the numbers. Use only when the user invokes /alpdesk-load-test."
disable-model-invocation: true
---

## What this does

Starts the load test by hand - the only way it runs. `CapacitySpec` carries `@Requires({ env.LOAD_TEST })`: without the
variable it is skipped, in `./gradlew test` (the full suite) as in CI. This skill sets it for one run, waits for the
report and explains it. It changes no code and commits nothing.

## Step A - choose the size

- If the arguments name a size (`schnell`, `standard`, `gross`) or explicit values (`tenants=200 chunks=100000`), take
  them.
- Otherwise **ask** with `AskUserQuestion` - one question, header "Größe", question "Wie groß soll der Lasttest
  laufen?", options:
  - "Schnell" - 50 Mandanten, 20.000 Abschnitte, 100 Suchen; etwa 5 Minuten - prüft, ob alles läuft
  - "Standard (Recommended)" - 500 Mandanten, 200.000 Abschnitte (~6.000 Dokumente), 200 Suchen; eine halbe Stunde und mehr
  - "Groß" - 1.000 Mandanten, 1.000.000 Abschnitte (~30.000 Dokumente), 200 Suchen; mehrere Stunden, ~20 GB Platz in der Podman-Maschine

| Size | LOAD_TENANTS | LOAD_CHUNKS | LOAD_SEARCHES |
|---|---|---|---|
| Schnell | 50 | 20000 | 100 |
| Standard | 500 | 200000 | 200 |
| Groß | 1000 | 1000000 | 200 |

`LOAD_DIMENSION` stays 1024 unless the user names another vector length (at most 2000 - pgvector's HNSW limit).

## Step B - check before starting

1. **Never the developer's dev database** (CLAUDE.md "The developer's dev database"): the spec runs on the specs' own
   Testcontainers PostgreSQL. Do not set `DATASOURCES_DEFAULT_*` - unless the user explicitly names a throwaway
   PostgreSQL for the measurement (e.g. on a server of your own); then also
   `DATASOURCES_DEFAULT_DRIVER_CLASS_NAME=org.postgresql.Driver`, and never `127.0.0.1:15433`.
2. No other Gradle run in this checkout: every session shares `Backend/build`, and the test task empties
   `build/test-storage` and `build/test-results` when it starts - a parallel suite of another session would fail. Look
   for one (`pgrep -fl GradleWorkerMain` - a running test JVM; an idle Gradle daemon does not count - and `ListAgents` for other sessions in this repository); if one
   runs, tell the user and the other session, and wait.
3. Podman runs: `podman machine list` shows the machine running. If not, tell the user - do not start it yourself.
4. JDK 25: `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` in the same command.
5. The test sources compile: `./gradlew compileTestGroovy -q` from `Backend/`. If not, report the errors and stop - the
   load test measures the code as it is, it does not fix it.

## Step C - run

From `Backend/`, with Bash `run_in_background: true` (it outlasts any foreground timeout), e.g. for "Standard":

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25) && LOAD_TEST=1 LOAD_TENANTS=500 LOAD_CHUNKS=200000 LOAD_SEARCHES=200 ./gradlew test --tests "de.alpdesk.capacity.CapacitySpec" --rerun
```

`--rerun`: Gradle would otherwise take the last result from its cache and measure nothing. Tell the user in one line
that it runs and roughly how long; then wait for the notification - do not poll.

## Step D - report

1. If the run failed: show the failing feature and its error (`build/test-results/test/TEST-de.alpdesk.capacity.CapacitySpec.xml`),
   say which sections the report still has, and stop. A spec failure is not a capacity result.
2. Read `build/reports/capacity/capacity.md` and answer in German:
   - **Mandanten**: time per tenant, start migration for all of them (and what that means for a restart at 1.000).
   - **Verbindungen**: at how many tenants used within half a minute the server refused, how many JVM threads the pools
     added, whether they were released after idling; at how many tenants active at the same moment requests failed or
     waited.
   - **Großer Mandant**: write rate (how long a ZIP import of that size takes, without provider time), sizes against
     the server's RAM, the search's median/p95 per step and whether it grows with the chunks, parallel searches per second.
   - **Wissensbereiche** (table "One large tenant: knowledge areas" and the plan "Vector search of a user of the company
     knowledge alone"): the large tenant's documents lie deliberately unfavourably - only every 20th is company
     knowledge, the rest in two areas. Per step and scope (admin = everything, company knowledge alone = 5 %, company
     knowledge plus one area):
     - whether the restricted search is slower than the admin's, and whether the plan still uses the HNSW index
       (`Index Scan using embedding_vector_idx`) or reads every chunk (`Seq Scan` + `Sort`);
     - **"Fewer hits than admin"**: in how many searches a restricted user got fewer chunks than the admin for the same
       query. pgvector's iterative scan stops after `hnsw.max_scan_tuples` chunks (20,000 by default) - from which
       step does it show, for which scope? Say what it means for a user: the vector side misses passages, the
       full-text side still finds exact words.
     - Fake vectors are random-dense, not a real model's: the hit counts show the mechanism and the step where it
       starts, not the share a real tenant would see.
   - **Fazit**: what one installation carries at these numbers, and which limit comes first.
3. Say where the numbers come from: the machine it ran on and PostgreSQL's settings from the report's "Environment" -
   Testcontainers runs with default settings, not tuned like a server.
4. A recommendation (more `max_connections`, another pool size, more indexing threads, PgBouncer, a higher
   `hnsw.max_scan_tuples` for the restricted search) follows CLAUDE.md's bar: only with the measured case it rests on -
   for the scan limit, a step where restricted users got fewer hits, at a size a real tenant reaches. Change nothing -
   the user decides (`docs/architecture/ai-features.md` "Knowledge areas" says the limit is raised only after a
   measurement shows it).
