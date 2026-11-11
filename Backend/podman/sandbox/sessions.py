"""A sandbox session's processes and files (docs/architecture/code-execution.md) - everything but HTTP, which app.py
serves: no root, capabilities or cgroups needed, any kernel from 4.x on.

Every instance belongs to one tenant - its container keeps tenants apart. One session at a time keeps the tenant's users
apart: without privileges a process cannot be kept out of another's files, so there never is another's. A session ends
when the app says so, when it was idle too long or when it ran too long; then every process it started is killed and
everything written anywhere writable is deleted - the next session finds nothing.
"""

import base64
import json
import os
import select
import shutil
import signal
import stat
import subprocess
import sys
import threading
import time
import uuid

WORK = os.environ.get("SANDBOX_WORK", "/work")
TIMEOUT = int(os.environ.get("SANDBOX_TIMEOUT_SECONDS", "300"))
IDLE = int(os.environ.get("SANDBOX_IDLE_SECONDS", "300"))
LIFETIME = int(os.environ.get("SANDBOX_MAX_SECONDS", "1800"))
OUTPUT = int(os.environ.get("SANDBOX_OUTPUT_CHARS", "100000"))
# The most the server reads of a program's pipe: a program's output is cut at OUTPUT bytes (the kernel cuts its own, a
# shell command's is cut here), and the kernel's answer line - JSON of at most OUTPUT bytes of output and an error -
# can only be longer if it is not the kernel writing.
MAX_ANSWER_LINE = OUTPUT * 8 + 65536
MEMORY_MB = int(os.environ.get("SANDBOX_MEMORY_MB", "1024"))
PROCESSES = int(os.environ.get("SANDBOX_PROCESSES", "256"))
FILE_MB = int(os.environ.get("SANDBOX_FILE_MB", "512"))

# Every place a session can write to, emptied when it ends: the session's directory is not the only one - code may
# write next to it in WORK, or into /dev/shm, /var/tmp and /dev/mqueue (a plain open() creates a message queue there),
# which a read-only root still leaves writable, and /run/lock, which Podman's tmpfs on /run copies from the image with
# everyone's write right. selftest.py fails when it finds another writable place.
CLEANED = (WORK, "/tmp", "/dev/shm", "/var/tmp", "/dev/mqueue", "/run/lock")

HERE = os.path.dirname(os.path.abspath(__file__))
# confine.py needs the system's Python (python3-seccomp) - the server runs on a venv of its own, the code on another.
SYSTEM_PYTHON = "/usr/bin/python3"
VENV_PYTHON = "/opt/venv/bin/python3"
# What a program of a session sees of the environment - nothing of the server's.
PROGRAM_ENVIRONMENT = {
    "PATH": "/opt/venv/bin:/usr/local/bin:/usr/bin:/bin",
    "LANG": "C.UTF-8",
    "TMPDIR": "/tmp",
    "MPLBACKEND": "Agg",
    "MPLCONFIGDIR": "/tmp/matplotlib",
    "OPENBLAS_NUM_THREADS": "1",
    "OMP_NUM_THREADS": "1",
    # glibc reserves an arena of up to 64 MB address space per thread, and the address space is what SANDBOX_MEMORY_MB
    # limits: ffmpeg's threads failed with "Cannot allocate memory" at 158 MB really used (measured 17 October 2026).
    "MALLOC_ARENA_MAX": "2",
    # Where rtmlib finds the RTMPose models the video image bakes in - it would download them, and a session has no network.
    "TORCH_HOME": "/opt/models/rtmlib",
    "PYTHONDONTWRITEBYTECODE": "1",
}
# Whether this is the video image (Dockerfile target "video"): what a video is examined with is there - read from the
# image itself, so /health cannot claim what is missing.
VIDEO = shutil.which("ffmpeg") is not None and os.path.isdir(PROGRAM_ENVIRONMENT["TORCH_HOME"])
# What an image built on ours says it carries (docs/architecture/code-execution.md "Your own image"): the operator's
# README, passed through /health to the app, which puts it into the model's prompt as it is - read once at the start,
# None without one. At most EXTRAS_LIMIT bytes: the text stands in every code question of the tenant.
EXTRAS_FILE = "/opt/extras/README.md"
EXTRAS_LIMIT = 16 * 1024
EXTRAS_CUT = "\n\n[This README was cut at 16 KB - the rest is not shown.]"
EXTRAS = None


class Session:
    """The one session: its directory, its Python, every process group it started."""

    def __init__(self):
        self.id = uuid.uuid4().hex
        self.directory = os.path.join(WORK, self.id)
        self.started = time.monotonic()
        self.used = self.started
        self.kernel = None
        self.groups = set()
        self.lock = threading.Lock()


state_lock = threading.Lock()
session = None
# True while a session's processes are killed and its files deleted: the instance stays busy until that is done, or the
# next session's files would go with them.
ending = False
# Why the instance takes no session any more, None while it does: a cleanup left something behind (the next user's code
# could read it) or a session's processes could not be killed - until a restart empties everything again; or, found at
# the start, the container may not send signals at all. /health says it, so the admin's check shows it.
unusable = None
# The programs the server started and still waits for - subprocess collects their exit codes itself.
started_lock = threading.Lock()
started = set()


def confined(program, cwd):
    """Starts a program in a process group of its own (confine.py keeps every descendant in it)."""
    command = [SYSTEM_PYTHON, "-I", os.path.join(HERE, "confine.py"), "--server-pid", str(os.getpid()),
               "--memory-mb", str(MEMORY_MB), "--processes", str(PROCESSES), "--file-mb", str(FILE_MB), "--"]
    environment = dict(PROGRAM_ENVIRONMENT, HOME=cwd)
    with started_lock:
        process = subprocess.Popen(command + program, cwd=cwd, env=environment, start_new_session=True,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        started.add(process.pid)
    return process


def finished(process):
    with started_lock:
        started.discard(process.pid)


def reap_orphans():
    """As PID 1 the server inherits every process orphaned in a session; dead, each would stay a zombie. Only those it
    did not start itself are collected here - waiting for its own would take subprocess's exit codes."""
    with started_lock:
        own = set(started)
    for entry in os.listdir("/proc"):
        if not entry.isdigit() or int(entry) in own:
            continue
        try:
            with open(f"/proc/{entry}/stat") as stat:
                fields = stat.read().rsplit(")", 1)[1].split()
            if fields[0] == "Z" and int(fields[1]) == os.getpid():
                os.waitpid(int(entry), os.WNOHANG)
        except (OSError, IndexError, ValueError):
            pass


def kill_group(group):
    """SIGKILL to a session's process group; False when the kernel refused it - a security profile of the host that
    denies signals inside the container. signals_allowed finds that at the start, before any session: an instance that
    runs sessions is never refused here, so a call's timeout may wait for what it killed."""
    try:
        os.killpg(group, signal.SIGKILL)
    except ProcessLookupError:
        pass
    except PermissionError as e:
        log(f"could not kill the session's processes: {e.strerror} - the container may not send signals (the host's "
            "AppArmor profile? see README.md \"Code execution\")")
        return False
    return True


def signals_allowed():
    """Whether this process may send signals, tried on itself with one that is ignored: a security profile of the host
    can deny every signal inside the container - seen on Ubuntu 24.04 with Podman, whose AppArmor profile for crun
    stacks its label under the container's profile, which allows signals to its own label only. Then no session could
    ever be ended; the instance says so at its start instead of after the first answer."""
    try:
        os.kill(os.getpid(), signal.SIGCHLD)
        return True
    except PermissionError:
        return False


def end(current):
    """Kills everything the session started and deletes its files and /tmp. None when everything is gone, else why the
    instance must take no further session: its processes still run, or a cleanup left files behind. A call still
    running gets its program killed under it - it answers with an error, not after its timeout."""
    killed = all([kill_group(group) for group in list(current.groups)])
    kernel = current.kernel
    if kernel is not None and killed:
        kernel.wait()
        finished(kernel)
    with current.lock:
        # Again, under the lock: a call may have started a program while the first round killed.
        killed = all([kill_group(group) for group in current.groups]) and killed
        current.groups.clear()
        current.kernel = None
        cleaned = empty_all()
    # What was killed above is gone by now - collected before the next session starts.
    time.sleep(0.2)
    reap_orphans()
    if not killed:
        return "the processes of a session could not be killed"
    if not cleaned:
        return "a cleanup left files behind"
    return None


def empty_all():
    """Empties every place a session can write to - each one, even when another fails; False when something is left."""
    cleaned = [empty(directory) for directory in CLEANED]
    return all(cleaned)


def empty(directory):
    """Deletes everything in a directory the session could write to; the directory itself stays. False when something
    is left - logged, never passed over in silence. A session may have taken the rights off its own directories, the
    mount's root included where the session's user owns it (Docker's tmpfs with uid=) - the server is the same user,
    so it gives them back first."""
    if not os.path.isdir(directory):
        return True
    try:
        if os.stat(directory).st_uid == os.getuid():
            os.chmod(directory, stat.S_IMODE(os.stat(directory).st_mode) | 0o700)
        if not os.access(directory, os.W_OK):
            # Read-only for the server is read-only for the session too: it wrote nothing here.
            return True
        entries = os.listdir(directory)
    except OSError as e:
        log(f"could not empty {directory}: {e.strerror}")
        return False
    cleaned = True
    for entry in entries:
        path = os.path.join(directory, entry)
        try:
            if os.path.isdir(path) and not os.path.islink(path):
                unlock(path)
                shutil.rmtree(path)
            else:
                os.remove(path)
        except OSError as e:
            log(f"could not delete {path}: {e.strerror}")
            cleaned = False
    return cleaned


def unlock(directory):
    """Gives a directory tree the write and search right back (chmod 000 cannot be listed, chmod 555 not emptied) -
    top down, as a locked directory shows its content only once it is open again."""
    os.chmod(directory, 0o700)
    with os.scandir(directory) as entries:
        for entry in entries:
            if entry.is_dir(follow_symlinks=False):
                unlock(entry.path)


class Busy(Exception):
    """Another session runs - an instance runs one at a time."""


def open_session(files):
    """The one session, with these files (name, base64 content) in its data/. Busy while another runs; ValueError,
    TypeError or OSError when the files cannot be written - the session is ended again then."""
    global session
    with state_lock:
        if session is not None or ending or unusable is not None:
            raise Busy()
        session = Session()
        current = session
    try:
        write_files(current.directory, files)
    except (ValueError, TypeError, OSError):
        end_session(current.id)
        raise
    return current.id


def busy():
    """Whether a session runs or none can start - the health answer's busy."""
    with state_lock:
        return session is not None or ending or unusable is not None


def find(session_id):
    """The running session if it has this id, else None."""
    with state_lock:
        return session if session is not None and session.id == session_id else None


def run(session_id, kind, text):
    """Runs Python code or a shell command (kind) in the session with this id - None when there is none, also when it
    ended while the call came in. The session's idle time starts anew before and after."""
    current = find(session_id)
    if current is None:
        return None
    current.used = time.monotonic()
    result = run_python(current, text) if kind == "python" else run_shell(current, text)
    current.used = time.monotonic()
    return result


def end_session(session_id):
    global session, ending
    with state_lock:
        if session is None or session.id != session_id:
            return False
        current, session = session, None
        ending = True
    problem = "ending the session failed"
    try:
        problem = end(current)
    finally:
        with state_lock:
            ending = False
        if problem is not None:
            refuse_sessions(problem)
    return True


def refuse_sessions(reason):
    """No session from now on - the next user's code could find what this one left; /health says why."""
    global unusable
    with state_lock:
        unusable = reason
    log(f"{reason} - this instance takes no session until it is started again")


def reaper():
    """Ends a session idle or running too long - an app that died never says it is done -, and collects orphans. An
    error is logged and the next round goes on: without the reaper an abandoned session would never end."""
    while True:
        time.sleep(5)
        try:
            reap_once()
        except Exception as e:  # noqa: BLE001 - the thread must outlive any one round
            log(f"the reaper's round failed: {e}")


def reap_once():
    reap_orphans()
    with state_lock:
        current = session
    if current and (time.monotonic() - current.used > IDLE or time.monotonic() - current.started > LIFETIME):
        log(f"session ended by time ({current.id[:8]})")
        end_session(current.id)


def cut(text):
    return text if len(text) <= OUTPUT else text[:OUTPUT] + f"\n[... {len(text) - OUTPUT} more characters cut]"


def read_line(stream, deadline):
    """One line of the kernel's output, waiting at most until the deadline; None when it ran out of time. Reading stops
    at MAX_ANSWER_LINE - what is longer is not an answer of the kernel."""
    data = b""
    while not data.endswith(b"\n") and len(data) <= MAX_ANSWER_LINE:
        left = deadline - time.monotonic()
        if left <= 0 or not select.select([stream], [], [], left)[0]:
            return None
        chunk = os.read(stream.fileno(), 65536)
        if not chunk:
            break
        data += chunk
    return data.decode("utf-8", errors="replace")


def read_output(stream, deadline, limit):
    """A program's whole output up to the limit, waiting at most until the deadline: the bytes and why reading stopped -
    "done" (the program closed its output), "timeout" or "cut" (more than the limit)."""
    data = bytearray()
    while True:
        left = deadline - time.monotonic()
        if left <= 0 or not select.select([stream], [], [], left)[0]:
            return bytes(data), "timeout"
        chunk = os.read(stream.fileno(), 65536)
        if not chunk:
            return bytes(data), "done"
        data += chunk
        if len(data) > limit:
            return bytes(data[:limit]), "cut"


def run_python(current, code):
    with current.lock:
        if ended(current):
            return None
        if current.kernel is not None and current.kernel.poll() is not None:
            finished(current.kernel)
            current.kernel = None
        if current.kernel is None:
            current.kernel = confined([VENV_PYTHON, "-I", "-u", os.path.join(HERE, "kernel.py"), str(OUTPUT)],
                                      current.directory)
            current.groups.add(current.kernel.pid)
        kernel = current.kernel
        kernel.stdin.write((json.dumps({"code": code}) + "\n").encode("utf-8"))
        kernel.stdin.flush()
        line = read_line(kernel.stdout, time.monotonic() + TIMEOUT)
        if line is None or not line.strip():
            kill_group(kernel.pid)
            kernel.wait()
            finished(kernel)
            current.kernel = None
            reason = f"ran longer than {TIMEOUT} seconds" if line is None else "ended (memory or process limit?)"
            return {"output": "", "error": f"the code {reason} - stopped; the session's Python starts anew, its "
                                           f"variables are gone"}
        try:
            answer = json.loads(line)
        except ValueError:
            # Not the kernel's answer: confine.py could not start it and said why.
            kill_group(kernel.pid)
            kernel.wait()
            finished(kernel)
            current.kernel = None
            return {"output": "", "error": cut(line)}
        # The kernel cuts its output itself; a traceback carries the code's lines, which can be anything.
        return {"output": answer["output"], "error": answer["error"] and cut(answer["error"])}


def run_shell(current, command):
    with current.lock:
        if ended(current):
            return None
        process = confined(["/bin/bash", "-c", command], current.directory)
        current.groups.add(process.pid)
    try:
        deadline = time.monotonic() + TIMEOUT
        output, stopped = read_output(process.stdout, deadline, OUTPUT)
        if stopped == "done":
            try:
                process.wait(timeout=max(0.0, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                stopped = "timeout"
        if stopped != "done":
            kill_group(process.pid)
            process.wait()
        text = output.decode("utf-8", errors="replace")
        if stopped == "timeout":
            return {"output": text + f"\n[stopped after {TIMEOUT} seconds]", "exitCode": -1}
        if stopped == "cut":
            return {"output": text + f"\n[... more output cut at {OUTPUT} bytes - the command was stopped; print less, "
                                     f"e.g. counts or selected lines]", "exitCode": -1}
        return {"output": text, "exitCode": process.returncode}
    finally:
        kill_group(process.pid)
        process.stdin.close()
        process.stdout.close()
        finished(process)


def ended(current):
    """Whether the session ended while a call came in - asked under its lock before a program starts: the end empties
    its places under the same lock, so a program started after it would write into the next session's."""
    return find(current.id) is not current


def write_files(directory, files):
    """Every file under data/ at its name - folders kept (src/a/Util.java), so the model finds it where the question
    names it; a name given twice gets a number, never overwriting the one before."""
    data = os.path.join(directory, "data")
    os.makedirs(data)
    for file in files:
        target = unique(os.path.join(data, relative_name(file.get("name", ""))))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as out:
            out.write(base64.b64decode(file.get("content", "")))


def uploaded_into_ended(current, target):
    """True, and the file removed, when the session ended while the file came in - its end may have emptied data/
    before the file was written."""
    if not ended(current):
        return False
    try:
        os.remove(target)
    except OSError:
        pass
    return True


def upload_target(current, name):
    """Where a file sent into the running session lands: data/<name> like the files it was opened with - folders kept,
    a name given twice numbered. ValueError for a name leaving data/."""
    target = unique(os.path.join(current.directory, "data", relative_name(name)))
    os.makedirs(os.path.dirname(target), exist_ok=True)
    current.used = time.monotonic()
    return target


def open_file(current, path):
    """A file the session's code wrote, opened for reading: (descriptor, size), or None when the path names no regular
    file below the session's directory. Where it lies is checked on the open descriptor, not on the name: code could
    swap the file for a link between a check and the open - and the server may read its own /proc/self/environ (the
    token). Opened without waiting: a named pipe would hold the open - and the server's thread - until a writer
    comes, which nobody does; a regular file reads the same either way."""
    base = os.path.realpath(current.directory)
    name = str(path).strip()
    if os.path.isabs(name):
        name = os.path.relpath(name, base)
    try:
        descriptor = os.open(os.path.join(base, name), os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK)
    except (OSError, ValueError):
        return None
    try:
        info = os.fstat(descriptor)
        if not os.readlink(f"/proc/self/fd/{descriptor}").startswith(base + os.sep) or not stat.S_ISREG(info.st_mode):
            os.close(descriptor)
            return None
    except OSError:
        os.close(descriptor)
        return None
    current.used = time.monotonic()
    return descriptor, info.st_size


def relative_name(name):
    """The name as a path below data/: leading slashes and "." dropped, ".." refused."""
    parts = [part.strip() for part in str(name).replace("\\", "/").split("/")]
    parts = [part for part in parts if part not in ("", ".")]
    if not parts or ".." in parts:
        raise ValueError("every file needs a name below data/")
    return os.path.join(*parts)


def unique(path):
    """The path, or with " (2)", " (3)", ... before its extension when a file is already there."""
    stem, extension = os.path.splitext(path)
    number = 2
    while os.path.exists(path):
        path = f"{stem} ({number}){extension}"
        number += 1
    return path


def read_extras(path=EXTRAS_FILE):
    """The README of an image built on ours, cut at EXTRAS_LIMIT bytes - None when there is none or it cannot be read:
    the instance then runs as ours does."""
    try:
        with open(path, "rb") as file:
            content = file.read(EXTRAS_LIMIT + 1)
    except OSError:
        return None
    text = content[:EXTRAS_LIMIT].decode("utf-8", errors="ignore").strip()
    if not text:
        return None
    if len(content) > EXTRAS_LIMIT:
        log(f"{path} is longer than {EXTRAS_LIMIT} bytes - cut there")
        return text + EXTRAS_CUT
    return text


def log(message):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), message, file=sys.stderr, flush=True)
