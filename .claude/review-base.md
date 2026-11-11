# Review base - shared by the scoped review skills

The scoped review skills (`alpdesk-security-review`, `alpdesk-knowledge-review`, `alpdesk-chat-review`,
`alpdesk-usage-review`, `alpdesk-tenancy-review`, `alpdesk-task-review`, `alpdesk-mcp-a2a-review`) each define **what** they review: their parts, paths and key
questions. This file defines **how**: the rules, the decision register, what counts as a finding, verification, the
budget and the report. A skill that includes this file follows every rule here unless it says otherwise.

The goal of a scoped review is a **small, reliable set of changes**, not a complete list of everything that could be
better. Fewer findings that all hold beat many findings that need sorting. The full sweep over everything is
`alpdesk-ultra-code-review`.

## 1. Rules

- **Current working tree, never a diff.** Ignore `<PR#>`, branch, `--comment`, `--fix` or `--post` arguments and say
  that the skill always reviews the current working tree (uncommitted changes included).
- **Read-only.** No code changes, no commits, no `./gradlew run`, **never the developer's dev database** (CLAUDE.md
  "The developer's dev database": not `127.0.0.1:15433`, not `Backend/database/`, no `devDb*` task), never a call to
  a real AI provider, model server or a tenant's MCP server. Never read local credential files (ignored by git, e.g. under `.claude/`) or any `.env`.
- **The yardstick** is [CLAUDE.md](../CLAUDE.md) together with the architecture reference
  [docs/architecture/](../docs/architecture/README.md) (tenancy, AI features, branding, MCP, A2A, file stores,
  knowledge sources, tasks, code execution, doc sites, i18n, monitoring - one file per area), plus
  [SECURITY.md](../SECURITY.md) and [docs/privacy.md](../docs/privacy.md) for what the software promises. A deviation
  from them is a finding; a rule the code has outgrown is a suggestion to update the file that states it.
- **Agents** are spawned in a single assistant turn with `subagent_type: general-purpose`,
  `run_in_background: false` and `model: "opus"` (never as a fork - a fork takes the session's model; each skill
  says why). Each gets a self-contained prompt (it has no conversation context) that contains the
  skill's "What this does", its own part with paths and special care, the skill's checklist or key questions, and
  sections 2 to 5 of this file verbatim.
- **Ground truth**: in the same turn as the agents, run in the background only the specs the skill names (JAVA_HOME =
  `$(/usr/libexec/java_home -v 25)`, needs Podman; the specs bring their own Testcontainers database):
  `cd Backend && ./gradlew test --tests '<spec>' --tests '<spec>' ...`, and `cd Frontend && npx ng test --watch=false`
  when the skill names frontend parts. Count tests and failures from `Backend/build/test-results/test/*.xml`. A red
  spec is a finding (Hoch).

## 2. The decision register

`local-data/review/decisions.md` (local, ignored by git - never published) lists what the user has decided to accept
(E-numbers). Every agent reads it before reviewing. The folder also keeps earlier review notes (`local-data/review/*.md`);
the plans with their decision lists are in `local-data/plans/` (also local)
- findings in full that the public roadmap and plans only name as a limit (e.g. point 66, media hardening); an agent
whose part they concern reads them, and what they record as deliberately left counts like a register entry.
**If the file is missing** (a fresh clone, another machine, a git worktree - `local-data/` is not part of the
repository): stop before spawning agents and tell the user; without the register every accepted point would be reported
again. Agents run in the main checkout, never in an isolated worktree, so they see it. A new entry is written into
that file; the user keeps their own backup of `local-data/review/`.

- A finding that an entry covers is **not reported**. The agent counts it under "vom Register abgedeckt: E01".
- Only if the entry's premise no longer holds (the code changed so the reason is gone, or the consequence is worse
  than the entry says) the finding is reported, marked **"Entscheidung prüfen (E01)"**, with what changed.
- Decisions in CLAUDE.md and `docs/architecture/` ("decided", "accepted", "deliberately", "not done on purpose") count
  the same as register entries.

## 3. No hallucination

A review that reports something the code does not do costs the user more than a finding it misses. Therefore:

- **Only what you read.** Every claim about the code rests on lines you read in this review, cited as `path:line`.
  Never a class, method, field, config key, endpoint or spec from memory, from its name or from "how such code usually
  looks". Before citing, quote the lines to yourself; a `path:line` that does not show what you claim is a wrong
  finding.
- **Follow the call, don't guess it.** A finding that depends on a caller, a guard, a transaction or a later step
  names where you checked it (`grep` for the callers, read them). "Probably not checked elsewhere" is not a finding.
- **Missing tests are searched, not assumed.** "Untested" only after a `grep` over `Backend/src/test/groovy` (and the
  `*.spec.ts` files) for the class, endpoint and case; name what you searched for.
- **Libraries are not guessed either.** Behaviour of Micronaut, langchain4j, Tika, the MCP Java SDK, PostgreSQL or
  Angular that a finding depends on is either visible in the project (code, spec, config) or marked as **Annahme** with
  what would confirm it. An Annahme is at most Mittel until confirmed.
- **Unsure is a valid answer.** What you cannot settle from the code goes into a short list "Offen/unklar" with the
  question and where to look - never into the findings.
- **"Nothing to find" is a valid result** for a part or a key question. Do not produce findings to fill a list. Say
  briefly what is solid, so well-built parts are not touched.

## 4. No over-optimisation

The goal is lean, readable code (CLAUDE.md "Code style"). A review that recommends caches, pools, interfaces,
configuration options or generic frameworks for a load or a second case nobody has makes the code worse.

- **Damage today, not in theory.** A finding needs a concrete scenario with today's code and use (or a use certain to
  come). "Could become slow with millions of tenants" is not a finding; "every chat question reads all documents of
  the tenant into memory" is.
- **This limits what gets recommended, never what gets looked at.** Security, tenant isolation, concurrency,
  transactions, resource and memory leaks (connections, pools, streams, temporary files) and real performance problems
  are examined in full depth.
- **The smallest fix that removes the damage.** Prefer deleting or simplifying code over adding layers; one guard or
  one spec over a new mechanism. No abstraction in advance, no optimisation without a measured or certain load, no
  configuration option nobody asked for.
- **Weigh value against cost.** When a fix costs more complexity than the damage is worth, say so: "Fix lohnt sich
  derzeit nicht" - the finding then goes to "Bewusst nicht empfohlen", not into the main list.
- **Severities are not inflated.** A papercut is Niedrig, even when there are many of them. No pure style findings:
  naming, formatting or "could be more elegant" only count when a rule of CLAUDE.md is broken, and then as Niedrig.

## 5. What counts as a finding

- **Concrete failure scenario**: which input, timing, crash, provider answer, user or admin action leads to which
  wrong behaviour. "Could be a problem" is not a finding.
- **Verified in the code** (section 3), with `path:line`.
- **Outside the skill's scope** only what the side-effect pass below led you to, or what you met while following a
  call: a **Kritisch** one goes into a separate short list, a **Hoch** one into the list "Nachbarbereich" (one line:
  `path:line`, the scenario, the review whose scope it is). Never look for findings out there, and nothing below Hoch
  is reported from there.
- **Severities** (a skill makes them more specific for its scope):
  - **Kritisch**: a tenant's documents, chats, files, keys or usage visible to or changeable by another tenant or
    user; a login or role check that can be bypassed; a leaked secret (admin password, JWT secret, `APP_SECRET_KEY`,
    provider, API, MCP or connection key) or a document's content in the log; a change in a tenant's software run
    without the user's decision; a request to an internal address an attacker can steer (SSRF); writing outside the
    storage folder; data loss.
  - **Hoch**: embedding, search or chat wedges or fails for good or silently skips work; a feature is broken; usage
    lost or billed to the wrong key; a tenant's pool or the server's memory exhausted by normal use; an important
    behaviour untested.
  - **Mittel**: wrong or inconsistent behaviour with limited or self-repairing consequence.
  - **Niedrig**: a papercut.
- For every finding: `path:line`, part, severity, failure scenario (one sentence), suggested fix (one sentence),
  `produktionsrelevant: true/false`, and the size of the fix: **S** (a few lines, one spec), **M** (one class and its
  specs), **L** (several classes or a design change).

### Side effects (every agent, after the questions of its skill)

The skill's key questions and failure cases name what is already known. This pass looks for what they do not name. It
is kept narrow on purpose (two test runs of 10 November 2026 over every writing method found nothing of their own):
only **the methods of your part that take something away or set something going** - that delete, deactivate or
revoke, that change a right or an assignment (a key's areas, software or tasks, a group's members or levels, a
switch of the tenant), or that start work which outlives the request (a job, a run, a queue entry, a stream). Not
plain creates and updates of one row. For each of them, answer from the code:

1. **Who else calls it?** `grep` for every caller, also outside your part (another entry point, a job, a start
   listener, a task step, the MCP or A2A server). Does each caller meet what the method takes for granted - the role
   and tenant checked, the transaction, the lock, the limits?
2. **Who else reads what it writes?** `grep` for the table, column, field or file. Does every reader cope with the new
   state - a search, a list, a count, a stored choice, the usage, the daily report, another feature's delete?
3. **What goes on after it returned?** Work after the commit, on an executor or by a job: does it still find what it
   needs when the row, the user, the key or the tenant is gone or changed meanwhile, and whose token, key and scope
   does it carry?
4. **What if it runs twice at once, or against its opposite?** The same call from two tabs or two keys; the call
   against a delete, a deactivation, a cleanup job, a restart of the same thing.

Deliver one row per method where an answer is not "fine": `Class.method` (path:line) · the question (1-4) · where you
looked (path:line of the caller, reader or job) · what happens · verdict ⚠️ / ❗. Say which methods you went through
and how many were fine. A ⚠️/❗ row is a finding and must hold like any other (a concrete scenario, sections 3 and 4);
when the place it hurts lies outside your part, it follows the rule for "outside the skill's scope" above.

### Not described

List in one line each what the code of your part does that neither the skill, CLAUDE.md nor `docs/architecture/`
mentions: an entry point, a caller, a job, a setting, a stored field, a branch for a special case. It is no finding by
itself - it shows the user where the skill or the documentation has fallen behind the code.

## 6. Verification and budget (synthesis)

1. **Pool and dedupe** the agents' findings - the rows of their side-effect pass and their "Nachbarbereich" lines
   included - (keep the more precise `path:line`), drop what the register covers.
2. **Refute before reporting.** Spawn one more agent, the *verifier*, with every Kritisch, Hoch and Mittel candidate
   (path:line, scenario, claimed severity) and sections 3 and 4 of this file. Its only task is to **try to refute**
   each one by reading the code: the cited lines do not say that; a guard, role check, transaction, lock, retry or
   later step prevents the scenario; a premise does not hold; the fix is out of proportion. It answers per finding
   `bestätigt` / `widerlegt` (with the preventing line) / `überzogen` (suggested severity) / `unverhältnismäßig`
   (to "Bewusst nicht empfohlen"). Drop the refuted, adjust the rest. **Read every remaining Kritisch and Hoch
   yourself** at its cited lines before it goes into the report.
3. **Budget**:
   - **Main list**: at most **10** findings, only Kritisch and Hoch, most severe first.
   - **Anhang**: Mittel and Niedrig as one-liners, at most 10 each. More are cut; say how many.
   - **Nachbarbereich**: the verified Hoch findings outside the skill's scope, at most 5, outside the main list's
     budget and the fix packages - they name the review to run next.
4. **Fix packages**: group the main list (and Mittel findings that belong to the same code) into **at most three
   packages**. Each package is self-contained (one change): title, findings it fixes, files, the specs it needs (named,
   with the case they cover - a bug fix starts with a spec that fails without it), size S/M/L. Order by value: what
   prevents the worst damage per effort first.

## 7. Report (German)

In this order, headings in German, identifiers as they are, clickable `path:line` relative to the repository root:

1. **Überblick**: parts reviewed (and what the user chose), test run (specs, tests, failures), findings per severity
   before and after verification (how many refuted, downgraded, moved to "Bewusst nicht empfohlen"), how many the
   register covered (`E01 ×2`), two or three sentences on the state including what is solid.
2. The skill's own sections (answers to its key questions, tables, walkthroughs), then **Nebenwirkungen**: which
   methods the agents went through, and the ⚠️/❗ rows of their side-effect pass that held.
3. **Befunde** (main list, max. 10).
4. **Fix-Pakete** (max. 3).
5. **Anhang**: Mittel, Niedrig, "Entscheidung prüfen" entries, out-of-scope Kritisch findings, "Nachbarbereich"
   (out-of-scope Hoch, with the review to run), "Nicht beschrieben" (what the code does that skill and documentation do
   not mention - to bring the skill or the file up to date), "Offen/unklar".
6. **Bewusst nicht empfohlen**: one line each with the reason, so the user sees what was weighed and why it stays.
7. **Kandidaten fürs Register**: findings the user will likely accept as intended (a deliberate trade-off, a documented
   limit), each as a ready-to-paste entry in the format of `local-data/review/decisions.md` (English). What shows one tenant's data to another, leaks a
   secret or runs a change without asking is never a candidate - it is fixed.

Do not fix anything unasked. End by asking which fix package to work on. When the user answers that a finding is
intended, offer to add it to the register.
