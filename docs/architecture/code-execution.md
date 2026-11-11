# Code execution: Python and shell in the chat

Decided 10 October 2026, after the measurements of roadmap point 14 (docs/roadmap.md): the chat's model may run
Python and shell commands on the question's files - to count, group, calculate and compare, above all in large files,
instead of reading them through search and line ranges. It is generic: the model decides what to compute; nothing in
the app assumes a kind of file.

## The parts

```
 app (Java)                                   the tenant's sandbox containers (Backend/podman/sandbox/, Python)
 ───────────                                  ────────────────────────────────────
 RunPythonTool ┐                              app.py       HTTP (Starlette, uvicorn) + the tenant's token
 RunShellTool  ┴─ CodeSessions ─ Sandboxes ──► sessions.py  one session at a time, its processes and files
                   (one session per answer,      │  confine.py  rlimits + seccomp, then exec
                    on the asker's tenant's)     ├─ kernel.py   the session's Python (a notebook)
                                                 └─ bash -c     one shell command
```

- **The sandbox** (`Backend/podman/sandbox/`) is a container image of its own: Ubuntu 26.04 with Python 3.14 and the libraries of
  `Backend/podman/sandbox/requirements.txt` (pandas, numpy, openpyxl, matplotlib), the usual shell tools (bash, grep, gawk, sed, sort,
  jq, sqlite3, find, file, compressors), ffmpeg with ffprobe (Ubuntu's package - for videos, see "Videos" below) and
  `python3-seccomp`. `app.py` answers HTTP (port 8080) on Starlette and
  uvicorn, from a venv of its own (`requirements-server.txt`, apart from the code's): `GET /health`, `POST /sessions`
  (the files, base64 - the app opens it empty), `PUT /sessions/<id>/files?name=` (one file into `data/`, streamed to
  disk - a video does not fit a JSON body; at most `SANDBOX_FILE_MB`), `GET /sessions/<id>/files?path=` (a file the
  code wrote, streamed - 400 for anything not a regular file below the session's directory, checked on the opened
  descriptor so code swapping a file for a link reaches nothing outside, not even the server's own
  `/proc/self/environ`; opened without waiting, so a named pipe the code made holds no thread of the server), `POST /sessions/<id>/python` and `.../shell`, `DELETE /sessions/<id>`, every error as
  `{"error"}`; a body larger than its limit or without a length (chunked) is refused before it is read.
  `sessions.py` holds everything else - the session, its processes, the cleanup - and knows nothing of HTTP. The server
  was `http.server` until 11 October 2026 (not meant for production, Python's documentation says); Starlette, not
  FastAPI, for the memory: measured idle, `http.server` 18.5 MB, Starlette 23 MB, FastAPI 45 MB per instance, which
  counts once per tenant. uvicorn wakes ten times a second - about 0.35 % of a core per idle instance against 0.05 %. `Backend/podman/sandbox/selftest.py` checks a running instance against everything below; the pull requests and
  the release run it.
- **The app** reaches the instances of the asking tenant - the addresses and token the admin entered at the tenant
  (below). `services.sandbox.Sandboxes` asks their health (trusted for 10 s) and leases a free one per answer: while it
  waits for one, it asks `/health` (which says `busy`) and opens the session only on an instance that is free, then
  streams the files into it one by one - the conversation's from the storage, never whole in the app's memory -; a
  refused token (401) ends the wait at once. An instance whose `/health` says `unusable` (why: it may not send
  signals, a session's processes could not be killed, a cleanup left files behind - it takes no session until it is
  started again) is no code execution: the tools are not offered on it, the admin's check shows "needs a restart"
  (`Status.UNUSABLE`), the app's log says why once (decided 23 October 2026: before, such an instance looked busy
  for ever - the model was told "busy", the admin saw "belegt").
  `SandboxSession` is that answer's session and holds the tenant's token. The lease lives in memory, by address - the
  app runs as one instance. An instance that ended the session itself (idle or lifetime, below) answers 404: the
  session is gone, the model is told, its lease ends, and the answer's next call opens a new session with the files.
- **The tools** `run_python` and `run_shell` (`services.tools`) are offered when the tenant has a sandbox of its own and
  one of its instances answers; the system prompt then says when to use them (`ChatService.CODE_RULES`). The first call of an answer opens its session with the
  question's files in `data/`: every file sent with it (`/api/v1/chat`, the MCP server's `ask_assistant`) - small or
  large - and the conversation's files; a name keeps its folders (`src/a/Util.java` lands in `data/src/a/`), a name
  given twice gets a number (`export (2).csv`) instead of overwriting the first, one leaving `data/` is refused. A file
  of the conversation without text (an archive a connected software returned) is opened here: `read_file` sends the
  model to `run_shell` / `run_python` for it when the tenant's sandbox answers (docs/architecture/mcp.md "Files in
  tool results").
  A file an instance refuses (413 too large, 400 a name it cannot write) or the storage cannot read ends the wait at
  once and the session opened for it: the model is told, no other instance is tried, the instance is not taken for
  unreachable. The session ends with the answer, however it ends (`ToolContext.closeSandbox`
  in every path of `ChatService`).

- **Images and files out of the session** (17 October 2026, `docs/roadmap.md` point 35): what the code prints is
  text, so two more tools come with the code tools. `view_images` shows the model up to 12 images its code wrote
  (PNG, JPEG, GIF, BMP, 20 MB each - frames cut out of a video, a still it drew on) at a time and 60 in one answer
  (every later round sends them all again - a provider refuses a request with too many) after the tool results, each
  named with its size in pixels - the coordinates to draw at, also when a large one is shown smaller (2048 pixels). And
  `hand_out_file` stores files of the session as files of the answer, like `create_file` (50 MB each, the type from
  the extension - an image is shown below the answer, a clip is `video/mp4`; not offered to the client software
  without history, whose files go nowhere). Several in one call (`paths`, in their order - one call each would use up
  the rounds), up to 30 images per answer (`MAX_IMAGES_PER_ANSWER`, the stills of a movement) beside the 5 other files
  `create_file` and the connected software share (decided 18 October 2026). **Two or more images of one answer are
  shown as a sequence** (`chat-image-sequence`): one image at a time, a slider, step back and forth (also with the
  arrow keys), play on a loop at 1, 2, 4 or 8 images a second, large in the dialog, the current one downloaded - all
  images loaded at once through HttpClient like a single preview, released with the conversation. Chosen over a clip
  of the stills: a video's timeline does not land on a given image, and the view is made for stepping through a
  movement image by image. The prompt asks for single images, never one sheet or grid (a model handed out the contact
  sheets it had made for its own overview). A clip handed out
  (`video/mp4`) is played below its answer with the browser's own controls (`chat-video-preview`, loaded the same
  way); an uploaded video stays a chip. A clip handed out lies in the next answer's `data/`; `read_file` sends the model to
  ffmpeg for it.

## One sandbox per tenant

Decided 11 October 2026: **no instance is shared by two tenants.** The first build had one pool for every tenant, kept
apart by running one session at a time and deleting everything of it after. On a running instance a session then left
three things for the next - another tenant's - session: a file next to its directory in `/work`, a file in
`/dev/shm`, and a System V shared-memory segment. Each was closed (below), but a cleanup is a deny list - every
writable place and kernel object must be known. Now the container keeps tenants apart: its own file systems, its own
IPC namespace, its own processes - what the platform gives a container without any privilege. The cleanup still keeps
the users of one tenant apart.

- **Data**: the tenant's addresses (`tenant.sandbox_urls`, comma separated) and token (`tenant.sandbox_token`,
  encrypted by `SecretCipher` like the provider keys, `sandbox_token_hint` for the frontend) in the management
  database. Code execution is on while both are set - there is no switch of its own and no global sandbox setting.
- **Entered by the admin** in the tenant's dialog (page "Mandanten"), validated by `TenantService`: http or https, a
  host, no credentials, query or fragment, de-duplicated - plain HTTP on purpose, the instances live in the
  installation's internal network and the operator enters them. An address another tenant already has is refused (409,
  compared without case): the container is what keeps tenants apart, so no instance may serve two of them. The token
  32 to 200 characters, no spaces, never sent out again. Addresses and token come together; emptied addresses remove
  the token as well.
- **Checked by the admin**: `GET /admin/tenants/{id}/sandbox` ("Sandbox prüfen" on the tenant's page) asks every
  instance with the tenant's token - reachable (a session opened without files and ended at once), busy, token
  refused or unreachable. A typo in an address or token would otherwise only show as tools missing in the chat.
- **Started by the operator**, one compose project per instance from `Backend/podman/public/docker-compose.tenant-sandbox.yml`
  with a file of its own in `sandboxes/` (name and token), joining the internal network of `docker-compose.prod.yml`;
  on Kubernetes a StatefulSet per tenant - `Backend/podman/public/README.md` "Code execution".
- **Cost** (measured 11 October 2026, Podman): the image 442 MB (869 MB with ffmpeg since 17 October 2026, "Videos"
  below), shared by every instance; an idle instance 19-21 MB
  and about 1 % CPU; a session with pandas, numpy and matplotlib imported about 96 MB, back to 21 MB when it ends. 100
  idle tenants are about 2 GB - what sizes the host is the number of answers running code at the same time.
- **Not built on purpose**: a script starting and updating every tenant's sandbox at once (until the number of tenants
  asks for it the compose file and a loop in the README do); a queue when a tenant's instances are busy (the tenant
  gets another instance); a shared pool whose container restarts after every session (Kubernetes keeps `emptyDir` and
  the pod's IPC namespace across container restarts).

## Videos

Decided 17 October 2026 (`docs/roadmap.md` point 35): a video is examined with code, not read by a model - the model
cuts the frames it needs with ffmpeg and looks at them (`view_images`), and may hand out a still or a clip
(`hand_out_file`). No model gets a video as it is any more: the catalog's "reads video" and the copy for Gemini (640
pixels, one frame per second) are gone.

- **Two images** (decided 18 October 2026): the sandbox stays what it was - no ffmpeg, no pose estimation, about 420
  MB - and the **video sandbox** (`alpdesk-ai-sandbox-video`, the Dockerfile's target `video`) is the same with
  ffmpeg, DejaVu and rtmlib with its RTMPose models. One Dockerfile, so the two cannot drift apart; the sandbox is its
  default target, so a build without `--target` stays the small one. The operator gives a tenant the one it needs
  (`SANDBOX_IMAGE` in the tenant's file for `docker-compose.tenant-sandbox.yml`) - every instance of a tenant the
  same. Fewer programs reading untrusted formats (ffmpeg, ONNX Runtime) where nobody needs them.
- **The sandbox says which it is**: `/health` answers `"video": true` when ffmpeg and the models are there
  (`sessions.VIDEO`, read from the image, not from a setting that could disagree with it). `Sandboxes.video` keeps it
  with the health (10 s): yes when every answering instance of the tenant says so (a session lands on any free one),
  no when one answering does not, unknown when none answers. It decides the upload (no: 409
  `chatFile.videoNeedsVideoSandbox`; unknown: taken, as with a sandbox entered), the video rules and rounds in the
  prompt, `read_file`'s hint ("no video tools - an admin can give it one"), and the admin's "Sandbox prüfen" shows
  "Video" per instance.
- **Pose estimation** (measured 18 October 2026, `docs/roadmap.md` point 35): rtmlib's RTMPose "balanced" - a person
  detector (YOLOX-m) and 26 points with heels and toes (Halpe-26) on ONNX Runtime, about a second a frame on 2 CPUs,
  447 MB at its peak, so it runs under `SANDBOX_MEMORY_MB` (the "performance" size did not: ONNX Runtime's
  `bad_alloc` above 1 GB address space). Its models are fetched when the image is built into `/opt/models/rtmlib` and
  found through `TORCH_HOME` (in every program's environment) - a session has no network. The video libraries go into
  `/opt/video-libs`, added to the code's venv by a `.pth` file instead of a second copy of the venv. OpenCV comes
  headless (`--no-deps`: rtmlib asks for the GUI build).
- **The posture's measures** (`Backend/podman/sandbox/posture.py`, in the video image's `/opt/video-libs`; decided 18
  October 2026): RTMPose gives points, not angles - the model would write the arithmetic anew in every answer, and two
  analyses of the same person would not compare (left and right mixed up, the image's y downwards, 180 or 0 for
  "straight"). `posture.person` takes the largest person's sure points (score 0.3), `posture.measure` gives the knee,
  hip and elbow angles left and right (180 = straight), the trunk's and the whole body's lean from the vertical with
  its side (the body's is largest in the middle of a turn and passes 0 when the body crosses over), the hips over the
  feet in leg lengths, the tilt of shoulders and hips; `posture.draw` draws skeleton and angles in the frame's pixels.
  Industry-neutral - the same for any movement - a lift at the workplace, a physiotherapy exercise, a sport. The model computes
  other measures itself where a question needs them. The selftest checks it on a made-up pose with known angles.

- **Upload** (`ChatFileService`): a video only for a tenant with a sandbox entered (else 409
  `chatFile.videoNeedsSandbox` - entered is enough, it need not answer at that moment), stored as it is, no copy for a
  model. Up to `app.chat.video-max-size` (500 MB, the installation's; raise the server's request limits, `/work` and
  `SANDBOX_FILE_MB` with it); every other file stays at 50 MB. A part above 50 MB must be a video: recognised by its
  first 64 KB (Tika sees an MP4's or WebM's type there) and streamed to the storage, never whole in memory. The files of
  one upload up to 50 MB count together against the 50 MB, a larger video on its own. The videos of one conversation
  (not expired) keep to `app.chat.video-max-size` together (else 409 `chatFile.videosTooLarge`): every one goes into the
  session, and `/work` holds about two of the largest - see below.
- **Retention** (decided 17 October 2026, for every chat file - asked for together with the videos'):
  `services.chat.ChatFileCleanupJob` (`app.chat.cleanup-cron`, every hour) deletes from the storage an uploaded video
  `app.chat.video-retention-hours` (8) after its upload and every other chat file - uploaded, created, handed out,
  returned by a connected software - `app.chat.file-retention-days` (14) after it was stored. Left empty, a retention is
  off; anything but a positive number stops the start. The row stays with `expired_at` (V3 of the tenant schema) and
  without its text (extracted on upload, a transcript): the conversation shows the file as expired (no download - 404
  `chatFile.expired`, no image preview), the model's file list and `read_file` say "expired" (an upload: "ask the user
  to upload it again"), `create_image` refuses it as a source and the sandbox gets it no longer. A still or clip handed
  out is a file of its own: it expires after the days, not with its video. Deleting the conversation or the file
  removes it at once, as before. A file whose content cannot be deleted is logged and tried at the next run; it holds
  up none after it. The conversation's messages stay - only files expire. Not built on purpose: a sweep
  of storage folders without a row (a delete that failed) - the job works from the rows; an index for its query (a
  scan of one tenant's files an hour).
- **Rounds**: an answer about a video may take `app.chat.video-rounds` (16) rounds instead of 6 - probing, an
  overview, frames, looking, searching, drawing, checking and handing out took 15 tool calls in a test;
  with 6 the model stopped before it had looked at the frames. Only with a video that has not expired, the code
  tools and the video sandbox (every round sends the frames looked at again).
- **The prompt** (`ChatService.VIDEO_RULES`, only with a video of the conversation that has not expired, the code
  tools offered and the video sandbox), in two parts (split 18 October 2026): for every task on a video - do what the
  user asks (cut, join, slow down, mark, label, make a clip or stills, examine), check what was made before handing it
  out, a call's 300 s on two CPUs, ffmpeg as `timeout 60 ffmpeg -nostdin` (in a test one ffmpeg call hung
  until the sandbox stopped it after 300 s; the same command took 2 s in every try since - the timeout caps the loss)
  - and, only when a person's movement is to be examined, the rest below (a user asking
  for a labelled clip must not get joints measured). ffprobe first; a time the user names is where to start, otherwise the model finds
  the passages itself - with a person, a scan with rtmlib at about a frame a second for where the person is large,
  visible and moving (numbers only, about a second a frame), and it says which passage it chose so the user can correct
  it (decided 18 October 2026) -, without one an overview (one image of 16 to 25
  small frames from key frames - 2.7 s for 4 minutes of full HD against 26 s decoding every frame); then dense frames
  where it matters, cropped, at most 1280 pixels wide, a few at a time; say what could not be seen; the observation is
  the model's own, the advice comes from the company's documents with their names (also with "documents only"); to
  mark something, draw with Pillow in the frame's own pixels, a labelled grid first when positions are needed, text in
  DejaVu (Pillow's default font has no umlauts), check
  with `view_images`, hand out with `hand_out_file`. Where a person moves: measure the posture with rtmlib (the
  call, the order of the 26 points, a score of 0.3 to trust a point), compute the angles that matter, draw the skeleton
  exactly; angles hold for a movement across the picture. A movement in its phases (start, middle, end), found from the
  measured values, shown as one image of 5 to 10 stills side by side with skeleton and angles (sequence images on
  one background were tried and dropped). Trade-specific wording (a technique, a
  sport, what a phase is called) belongs in the tenant's chat template, not here.
- **Not built on purpose**: the video's length and resolution in the file list (the model asks ffprobe - one call);
  a helper script finding the phases - only if the model does not find them reliably with the pose data.
- **ffmpeg**, measured below (the video image only).

- **Ubuntu's `ffmpeg` package** (8.0 in 26.04), updated with the image like every package. It adds about 450 MB
  (420 → 869 MB, measured on amd64), most of it graphics libraries ffmpeg's filters link against and the sandbox never
  uses (LLVM 135 MB, Mesa 45 MB) - apt cannot leave them out. A static build of a third party would be smaller but needs
  pinning and updating by hand.
- **`MALLOC_ARENA_MAX=2`** in every program's environment (`sessions.py`): glibc reserves an arena of up to 64 MB
  address space per thread, and the address space is what `SANDBOX_MEMORY_MB` limits. ffmpeg 8 runs many threads -
  encoding a full HD video with sound failed with "Cannot allocate memory" at 158 MB really used. The selftest checks
  it ("ffmpeg cuts frames out of a full HD video with sound").
- **Measured** (Podman, 2 CPUs, the compose file's limits) on a 4-minute full HD test video (30 fps, 16 Mbit/s, 484
  MB), in `/work`:

  | What | Time | Peak memory |
  |---|---|---|
  | 30 frames of 3 seconds (10 per second, 1280 px, JPEG) from second 20 | 1.1 s | 116 MB |
  | contact sheet, 25 frames over the whole video, every frame decoded | 26 s | 106 MB |
  | the same from key frames only (`-skip_frame nokey`) | 2.7 s | 80 MB |
  | a clip of 10 seconds cut without encoding (`-c copy`) | 0.2 s | 60 MB |
  | a clip of 10 seconds encoded with text drawn on it | 6 s | 226 MB |
  | HEVC: 20 frames of 2 seconds | 1.6 s | 120 MB |

  So with the default video size of 500 MB, **`/work` stays a tmpfs of 1 GB**: the video takes half of it, frames and
  clips the rest, and the container's 2 GB hold that and ffmpeg. A larger video setting needs a larger `/work` (and
  memory) for the sandbox.

## Your own image

Decided 5 November 2026, cut down to this on 8 November 2026 (roadmap point 59): a tenant may need its own programs in
the sandbox - its test framework, a simulator, a vendor's CLI. The core carries no trade's programs ("Industry-neutral"),
so the operator builds an image on ours (`Backend/podman/public/sandbox-example/`, `Backend/podman/public/README.md`
"Your own sandbox image") and writes **one file into it, `/opt/extras/README.md`** - the content comes from the image,
never from the core.

- **The sandbox** reads the file once at its start (`sessions.read_extras`, `app.py`'s `main`) and returns its text in
  `/health` as `"extras"` - `null` without one, an empty one or one it cannot read (never an error: the instance runs as
  ours). **At most 16 KB** (`EXTRAS_LIMIT`; about 4,000 tokens in every code question of the tenant): a longer one is cut
  there with a line saying so, and the start's log says it. Checked once by hand against a test image with a README of
  22 KB (8 November 2026): cut at 16 KB with the line, logged.
- **The app** keeps the text with the instance's health (`Sandboxes.Health.extras`, the same 10 s). The **agreement
  rule**, like the video flag's: a session lands on any free instance of the tenant, so `Sandboxes.extras(tenant)`
  gives the text only when every instance answering reports the same one - one without it, or another text, gives
  none. `ChatService` appends it to the code tools' rules when run_python is offered: the fixed heading "Programs
  installed in this sandbox (its README):" and the text as it is - no sentence of ours, no extra round.
- **The admin's check** (`SandboxStatusResponse`: `extras` per instance, `extrasAgree`) shows "Eigene Programme" next to
  "Video" and warns when the instances differ. Nothing for the tenant admin - the sandbox is the admin's.
- **Not built on purpose**: a description format (capabilities, a summary) - the README is the description; rules of
  the core per program - a capability that needs one is a release of ours (as `video` is); more rounds or a longer
  command time per image - a program that needs more is wrapped by the operator into one command; a tag of its own -
  the operator tags with our version and rebuilds per release; the text in `/api/v1`, the MCP server or the A2A card.
  The video image stays described by the core (its rules are the chat's).

## The base image

Decided 11 October 2026, after every candidate was checked: **plain `ubuntu:26.04` stays** - flexible (every Ubuntu
package at hand for a tool the code needs later), the same base as the app's image, and Python and `python3-seccomp`
from one hand. **The check is to be repeated** (below) - the candidates change with every release of theirs.

### What was checked (11 October 2026)

Each candidate built with the same packages, the same server (`app.py`) and run through the same `selftest.py`
(Podman, arm64):

| Base | Image | Server RSS idle | Selftest | Python | seccomp for `confine.py` |
|---|---|---|---|---|---|
| `ubuntu:26.04` (kept) | 447 MB | 41 MB | passed | 3.14.4 (Ubuntu) | `python3-seccomp` (Ubuntu) |
| `debian:trixie-slim` | 406 MB | 32 MB | passed | 3.13.5 (Debian) - a version back | `python3-seccomp` (Debian) |
| `python:3.14-slim` | 419 MB | 39 MB | passed | 3.14.8, built from source into `/usr/local` | `pyseccomp` 0.1.2 (PyPI) instead |

- **The slim bases save 30-40 MB** of 447 MB and change nothing that grows with the tenants: the image is shared, an
  instance loads only what it uses. They still carry apt, dpkg and perl.
- **`python:3.14-slim`** is a Docker Official Image (`docker-library/python`, maintained by the Docker community, not
  by the Python Software Foundation) on `debian:trixie-slim`; its tag moves with every rebuild (a digest pins it). It
  has bash, the GNU coreutils, grep, sed, find, diff, gzip and tar; it lacks gawk (its awk is mawk, which differs in
  details), jq, sqlite3, file, bzip2, xz and unzip - installed with apt like on Ubuntu, the shell then has everything.
  The catch: Debian's Python is not installed, so Debian's `python3-seccomp` has no Python to belong to - `confine.py`
  would need `pyseccomp`, a small third-party binding, instead of libseccomp's own, or a second Python in the image.
- **A chiselled Ubuntu** (Canonical's `chisel`: only the files needed): estimated 90-100 MB instead of 193 MB of
  system, without apt, dpkg, perl, passwd or pebble; slices for 26.04 exist for every package but `python3-seccomp`
  (to be copied from an Ubuntu stage). Not built - no case for it today; unchecked: `/bin/sh` and the user without
  `useradd`.
- **Alpine**: left out - its busybox tools behave unlike the GNU ones models expect.
- **Where the size goes** (Ubuntu): the Python libraries 248 MB (their test suites 61 MB of it, `pandas/tests` alone
  37 MB), Ubuntu 132 MB, the added packages 61 MB. Removing the test suites would save 61 MB at a small risk
  (`pandas.test()` gone); the compiled `__pycache__` must stay - the root is read-only, every session would compile
  pandas again.

### Checking again

**When**: at every new Ubuntu LTS and every new Python minor version the sandbox moves to, when the image's download
size or the tools inside it start to matter, or when `python3-seccomp` or a slice for it appears where it was missing.

**How**: build each candidate with the same packages and `app.py`, run `selftest.py` against it, measure the image size
and the server's RSS idle (`/proc/1/status`) next to the current one, check where the seccomp binding comes from - then
update the table above with the date.

## What the model gets

- **Python like a notebook**: one namespace per answer, variables and imports kept from call to call, the value of a
  last expression shown. Measured: models expect that - with a fresh process per call they loaded everything again and
  gave up. A call stopped after 300 s (a pass over a video takes minutes) takes the session's Python with it (the model is told its variables are gone).
  The code runs in a module of its own that is `__main__`, and `multiprocessing` forks: a `Pool` takes the functions
  the code defined (Python 3.14's default, forkserver, ran the kernel again in its worker and hung until the timeout).
  The kernel answers the server over a descriptor of its own; fd 1 and 2 of the code are a file every program it
  starts inherits - so what a subprocess, a forked worker or a C library prints is output of the call, and never
  mistaken for the kernel's answer.
- **The shell**: `bash -c` in the same directory, stdout and stderr together, with the exit code.
- **At most 20,000 characters of output** per call reach the model (`CodeSessions.MAX_OUTPUT`), with a note to print
  less; the sandbox cuts at 100,000 bytes already (`SANDBOX_OUTPUT_CHARS`) - the kernel its own, the server a shell
  command's, which it stops there: a program printing a whole file never fills the server's memory.
- **Every instance busy** (`app.sandbox.wait`, 15 s): the tool answers "busy - go on without it", the answer goes on;
  a refused token says so instead ("an admin checks the sandbox at the tenant"). A sandbox that goes down during the
  answer: the first call fails as a text; once no instance of the tenant answers, every further one is answered at
  once by the `Toolbox` ("the tool run_python is not available right now - go on without it"). With several instances
  the answer's session stays on the one that went down and its calls fail as texts (not built on purpose: the damage
  is one answer without code). The same text answers a call of a built-in tool
  not offered for the question at all.
  An instance failing mid-session is a text for the model, never a failed answer.
- **The container may not send signals** (seen 23 October 2026 on Ubuntu 24.04 with Podman 4.9 and crun: the host's
  AppArmor profile `/etc/apparmor.d/crun` stacks its label under Podman's `containers-default`, whose rules allow
  signals and `ptrace read` to its own label only - every `kill` in the container, even to itself, fails with
  `EACCES`): the sandbox cannot end a session's processes, so after the first answer it took no session any more and
  looked busy for ever. Now `signals_allowed()` tries a harmless signal on the server itself at the start and
  `refuse_sessions` says why in the log and in `/health` (`unusable`); `kill_group` logs a refused kill instead of
  raising; `selftest.py` checks `unusable` at its start and at its end. The compose file and the README's `podman run`
  set `apparmor=unconfined` for the sandbox container (decided 23 October 2026 - an own profile with the stacked
  label allowed would need a file installed by hand on every host, and what `containers-default` forbids is
  impossible without root and capabilities anyway; a host without AppArmor ignores the option).

## Isolation without privileges

Required (decided 10 October 2026): it runs on Kubernetes with the "restricted" pod security - no root, no
capabilities, no cgroups of its own - on kernels from 4.x on, and on Podman and Docker alike. So no namespaces, no
chroot, no Landlock (kernel 5.13). The layers:

1. **One tenant per instance, one session per instance.** The container keeps tenants apart (above). Within it,
   without privileges a process cannot be kept out of another's files when both run as the same user - so there never
   is another: an instance runs one answer's code at a time, which keeps the tenant's users apart. More answers of a
   tenant at once need more instances of that tenant.
2. **The container holds nothing worth taking**: no secret but its token, no database, no files of the app; not root
   (uid 10001), the root file system read-only, only `/work` and `/tmp` writable (tmpfs), CPU and memory limited by
   the platform.
3. **No way out of the network**: the sandbox reaches nothing outside (Compose: the `sandbox` network, `internal:
   true`; Kubernetes: a NetworkPolicy without egress) - and every program of a session gets no network socket anyway
   (below). On Compose that network is shared: the app's unproxied port and every tenant's instances are in it, so
   there the seccomp rule keeps a session's code off the app and the other tenants' instances. On Kubernetes
   the NetworkPolicy holds without it. A network per tenant on Compose is not built - no way past the `socket` rule
   is known, and it would be a network to create per tenant (SECURITY.md "Code execution").
4. **Every program of a session is confined** (`confine.py`, then `exec`): resource limits (address space
   `SANDBOX_MEMORY_MB`, 1 GB; processes; file size; open files) and a seccomp filter with `no_new_privs` - no sockets
   but local ones, no `ptrace` or `process_vm_*`, no `setsid`/`setpgid` (so nothing leaves the session's process group),
   no signal to the server or to every process by any of the calls that send one (`kill`, `rt_sigqueueinfo`, `tgkill`,
   `rt_tgsigqueueinfo` to its pid; `tkill` and `pidfd_send_signal` not at all - the kernel drops only the signals PID 1
   has no handler for, and the server handles SIGTERM), no `unshare`, `mount`, keyrings, BPF, no io_uring (it opens
   sockets past the filter), no System V IPC and POSIX message queues (their objects outlive the process). seccomp
   needs no privilege and exists since kernel 3.5.
5. **The server cannot be read**: it is PID 1 of its container and marks itself not dumpable, so a program of the
   same user reads neither its memory nor its environment (the token) through `/proc`. An init like tini would hold
   the token in a readable environment - the server reaps orphaned processes itself instead.
6. **A session leaves nothing**: at its end - the app's `DELETE`, 300 s idle or 1800 s in all - its process group is
   killed (background processes included: they cannot leave it; a program started while the session ends is killed
   too), every place a session can write to is emptied (`sessions.py` `CLEANED`: all of `/work`, `/tmp`, `/dev/shm`,
   `/var/tmp`, `/dev/mqueue` - a plain `open()` creates a message queue there -, and `/run/lock`, which Podman's tmpfs
   on `/run` copies from the image with everyone's write right), orphans collected. Which places are writable is the
   runtime's choice, not the image's: `selftest.py` looks for every writable directory and fails on one not in the
   list - run it under the runtime of the installation (CI runs Docker, production Podman). The instance stays
   busy until that is done - the next session, another user's of the tenant, starts after it and finds nothing;
   `selftest.py` writes into every one of them and checks. The rights a session took off a directory - also off a
   mount's root, which the session's user owns with Docker's `uid=` - are given back first; every place is emptied
   even when another fails, and a cleanup that leaves anything behind makes the instance refuse every session (busy)
   until it is restarted, which empties everything again. The reaper that ends abandoned sessions logs an error and
   goes on.

Considered and left (10 October 2026): **nsjail** (namespaces per call - needs `SYS_ADMIN` or an unconfined seccomp
profile plus `label=disable` in a container, and no Ubuntu or Debian release packages it); **chroot** (needs
`CAP_SYS_CHROOT`, separates files only); **Landlock** (kernel 5.13 and newer only); **Python as WebAssembly** in the
JVM (no numpy, pandas or shell, slow); **a container per call** (needs the Podman or Docker socket - root on the host -
and seconds per start).

## Settings

| Per tenant (the admin, page "Mandanten") | Default | |
|---|---|---|
| Sandbox-Adressen (`tenant.sandbox_urls`) | none: no code execution | the tenant's instances, comma separated |
| Sandbox-Token (`tenant.sandbox_token`, encrypted) | - | the token its instances were started with, 32+ characters |

| App | Default | |
|---|---|---|
| `app.sandbox.timeout` | 330s | one HTTP call to an instance - longer than a call may run |
| `app.sandbox.wait` | 15s | how long an answer waits for a free instance of its tenant |

| Sandbox (environment) | Default | |
|---|---|---|
| `SANDBOX_TOKEN` | required, 32+ characters | |
| `SANDBOX_TIMEOUT_SECONDS` | 300 | one Python call or shell command (CI's selftest: 30) |
| `SANDBOX_IDLE_SECONDS`, `SANDBOX_MAX_SECONDS` | 300, 1800 | a session ends by itself |
| `SANDBOX_MEMORY_MB`, `SANDBOX_PROCESSES`, `SANDBOX_FILE_MB` | 1024, 256, 512 | limits per program; `SANDBOX_FILE_MB` also for a file streamed in |
| `SANDBOX_OUTPUT_CHARS`, `SANDBOX_REQUEST_MB` | 100000, 256 | |

Running it - Compose, `podman run`, Kubernetes: `Backend/podman/public/README.md` "Code execution". Locally `./gradlew
sandboxStart` builds and starts a sandbox on `http://localhost:19180` (`Backend/podman/docker-compose.sandbox.yml`, a
published local token), `sandboxStop` removes it; enter its address and token at a test tenant. `./gradlew
videoSandboxStart` / `videoSandboxStop` do the same with the video image (tag `:video`) in the same container and port,
so one of the two runs at a time. The first build fetches the pose models from a server delivering some 30 KB/s -
about an hour (measured 19 October 2026); later builds take them from podman's layer cache. That cache is kept on
purpose: the stage that fetches them (`video-libraries`) is only copied from, so its image has no tag and no child,
and the prune after every sandbox start (`sandboxPruneImages`) removed it as dangling - the next build fetched the
models again. `videoSandboxLibraries` builds the stage under a tag of its own (`:video-libraries`) first, which no
prune removes. The models are fetched again only when the stage changes: `requirements.txt` or
`requirements-video.txt`, a newly pulled base image, or a prune of tagged images (`podman system prune -a`). The backend never runs
code itself, also not in development - without a sandbox the tools are not offered.

## Tests

- `services.sandbox.FakeSandboxes` replaces `Sandboxes` in every spec (no container is reached; `up`, `busy`, the
  sessions with the instances, token and files they were opened with and their calls; `statuses` for the admin's
  check; `tokenRefused` for a refused token; `extras` / `extrasOf` for the README of an image built on ours);
  `FakeAiModels` calls the tools for `[[python <code>]]` and
  `[[shell <command>]]` in the question.
- `controller.chat.CodeExecutionSpec`: when the tools are offered, that each tenant's code runs on its own instances
  with its token, the README of the tenant's own image in the prompt (only when its instances agree), the session's
  files (sent and conversation files), that it ends with the answer - also a failed
  one -, busy, a refused token, errors, cut output.
- `controller.tenant.TenantSpec`: the tenant's sandbox entered, kept, removed with its token, every invalid input
  (400), an address another tenant has (409), the token stored encrypted and never in a response, the admin's check
  per instance (video, own programs, whether they agree), 404, no check for a tenant admin.
- `services.sandbox.SandboxesSpec`: the HTTP protocol against instances in the spec - each tenant's token to its own
  instances, a wrong token refused and the wait ended (the instance not kept leased), files, a busy instance passed over
  without the files, a leased one not given twice, a session the instance ended itself gone and its instance free
  again, an unreachable one skipped and counted as down, one that takes no session until it is started again
  (`unusable`) not available, the admin's check, the README of an image built on ours and the agreement rule.
- `services.tools.ToolboxSpec`: a tool offered for the question whose sandbox went down meanwhile, and one never
  offered, are answered "not available right now".
- `Backend/podman/sandbox/selftest.py` against the real image (pull requests, release) - `extras` a string or null.

## Known weaknesses - to be addressed

Found in the reviews of 11 October 2026; each is a point of roadmap 26 until it is done. Closed since: a session left
files outside its directory and System V objects for the next session (now one sandbox per tenant, every writable
place emptied, System V IPC denied); a subprocess's output was mistaken for the kernel's answer; a program's output
filled the server's memory; SIGTERM reached the server by `rt_sigqueueinfo` and `tkill`; the next session's files went
with the last one's cleanup; a session the instance ended by itself read as "not reachable"; an address could be
entered at two tenants; a directory a session had locked against itself (`chmod 000`) survived the cleanup for the
next user to open again (the cleanup gives the right back first, `selftest.py` locks one); a container restarted
mid-session (its memory limit) started on that session's files (emptied at start - Kubernetes keeps `emptyDir`); a
session that took the read right off the root of `/work` or `/tmp` (owned by its user with Docker's `uid=`) stopped
the cleanup, so later sessions' files stayed for the next user, and an error in the reaper ended it for good (security
review of 20 October 2026: the roots' rights given back, every place emptied, the instance refuses sessions after a
failed cleanup, `selftest.py` locks both roots).

Agreed 11 October 2026: **nothing of this is built before a case asks for it** - the design is close to what runs
without any privilege; what would make it safer still is a runtime of the platform (below, 8). Point 5 comes first
when something comes: a company will ask what ran on its data.

1. **The seccomp filter is a deny list.** Everything not denied is allowed - io_uring showed the risk: it opened
   sockets past the `socket` rule until it was denied too. A new kernel can bring another such way. An allow list is
   safer but needs every system call Python, numpy, pandas and the shell tools use; until then the network isolation of
   the platform (internal network, NetworkPolicy) is a required layer, not an optional one - and on Compose, where the
   network is shared with the app (layer 3 above), the `socket` rule is the only thing between a session's code and
   the app's port. The deny list is not complete: a session's code may still be able to restart its own instance (the
   effect another limit allows anyway - weakness 4: one answer loses its code).
2. **The files go to the sandbox as they are read.** `Sandboxes.open` sends them one by one, streamed (`PUT
   /sessions/<id>/files`) - no longer base64 in one JSON body.
3. **One answer per instance.** With every instance of its tenant busy an answer waits `app.sandbox.wait` (15 s) and
   goes on without code. Another instance of the tenant helps (about 20 MB idle); there is no queue and no notice to
   the admin that answers went without. Another tenant is never held up by it.
4. **The memory limit is per program.** `SANDBOX_MEMORY_MB` holds each process, but many of them together (up to
   `SANDBOX_PROCESSES`) can exceed the container's limit: the platform then kills the whole container, the server
   included - the one answer loses its code, the instance restarts.
5. **Nothing records what ran** - the next point to build. Neither the code nor its output is logged - only the tool's name. What the model
   computed on a company's data cannot be traced afterwards - store every call with the answer (the code or command,
   its output's size, the time), as a changing tool call is stored, and show it in the chat's history; at least its
   kind and size.
6. **Not yet run where it will run.** Checked under Podman (rootful, SELinux, arm64 natively; amd64), Docker with
   AppArmor (CI) and Podman on Ubuntu 24.04 - where the host's AppArmor profiles denied every signal in the container
   (above, "The container may not send signals"). The release's arm64 images are built under QEMU, where only start and
   `/health` are checked (under the emulation the seccomp filter cannot be loaded), so the selftest on arm64 runs
   natively by hand; Kubernetes with `RuntimeDefault` stacks its own seccomp profile under ours, and
   its "restricted" pod security forbids `Unconfined` AppArmor - `selftest.py` against the cluster is the proof still
   missing; an instance that may not send signals says so there too.
7. **The code sees the file system of the server.** Server and code run as the same user (a second user needs
   `CAP_SETUID`), so the code's view of the files is the container's: `/proc` of the server (unreadable only because it
   is not dumpable), the server's own code, every library. **Landlock** (kernel 5.13 and newer, no privilege needed)
   would confine every program of a session to its directory, `/tmp`, `/dev/shm` and the libraries it imports - about
   fifty lines of ctypes in `confine.py`, skipped where the kernel lacks it - the first hardening step should the
   sandbox ever run outside the internal network. Weighed on 11 October 2026: a token leaked to the code is worth
   little - from inside the sandbox nothing can be reached with it (no network socket; the network holds only the app
   and the tenants' instances). The healthcheck runs isolated and not dumpable, like the server; Landlock would confine
   the code's view of the file system further.
8. **A runtime of the platform is the real step up.** gVisor or Kata (Kubernetes: a `runtimeClassName`) give every
   container a kernel of its own; nothing in the sandbox changes for it. Where an installation needs more than the
   layers above, this is the answer, not another layer in `confine.py`. Also possible without privileges but not built
   for want of a case: a gateway container holding the token next to a runner holding no secret at all.

Not yet: files a session's code writes (a chart, a CSV) as downloads of the answer; specialists that get the tools
with a model of their own (roadmap point 14).
