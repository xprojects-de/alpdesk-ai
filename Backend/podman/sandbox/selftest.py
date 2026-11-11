#!/usr/bin/env python3
"""Checks a running sandbox against what it promises (docs/architecture/code-execution.md) - CI runs it against the
image it built, and anyone can against an installation:

    python3 Backend/podman/sandbox/selftest.py http://localhost:8080 <token>

Standard library only. Every check prints one line; the exit code says whether all passed.
"""

import base64
import http.client
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

URL, TOKEN = sys.argv[1].rstrip("/"), sys.argv[2]
# sessions.py's CLEANED: the places a session's end empties.
CLEANED = ("/work", "/tmp", "/dev/shm", "/var/tmp", "/dev/mqueue", "/run/lock")
failures = []


def call(method, path, body=None, token=TOKEN):
    request = urllib.request.Request(URL + path, method=method,
                                     data=None if body is None else json.dumps(body).encode())
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            data = response.read()
            return response.status, json.loads(data) if data else None
    except urllib.error.HTTPError as e:
        data = e.read()
        return e.code, json.loads(data) if data else None


def raw(method, path, body, headers):
    """A request as written, past urllib's own Content-Length - for the protocol's edges."""
    address = urllib.parse.urlsplit(URL)
    connection = http.client.HTTPConnection(address.hostname, address.port, timeout=30)
    connection.request(method, path, body=body, headers=dict(headers, Authorization=f"Bearer {TOKEN}"),
                       encode_chunked="Transfer-Encoding" in headers)
    response = connection.getresponse()
    data = response.read()
    return response.status, json.loads(data) if data else None


def check(name, condition, detail=""):
    print(("ok    " if condition else "FAIL  ") + name + ("" if condition else f" - {detail}"))
    if not condition:
        failures.append(name)


def python(session, code):
    return call("POST", f"/sessions/{session}/python", {"code": code})[1]


def shell(session, command):
    return call("POST", f"/sessions/{session}/shell", {"command": command})[1]


def put_file(session, name, chunks, length):
    """A file streamed into the session (chunks: an iterable of bytes, length: what the request declares)."""
    address = urllib.parse.urlsplit(URL)
    connection = http.client.HTTPConnection(address.hostname, address.port, timeout=120)
    connection.request("PUT", f"/sessions/{session}/files?name={urllib.parse.quote(name)}", body=chunks,
                       headers={"Authorization": f"Bearer {TOKEN}", "Content-Length": str(length)})
    response = connection.getresponse()
    data = response.read()
    return response.status, json.loads(data) if data else None


def get_file(session, path):
    request = urllib.request.Request(f"{URL}/sessions/{session}/files?path={urllib.parse.quote(path)}")
    request.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def new_session(files):
    """files: a dict of name -> content, or a list of (name, content) pairs for a name given twice."""
    pairs = files.items() if isinstance(files, dict) else files
    status, body = call("POST", "/sessions", {"files": [
        {"name": name, "content": base64.b64encode(content.encode()).decode()} for name, content in pairs]})
    return status, body and body.get("session")


status, health = call("GET", "/health", token=None)
check("health answers without a token, and says whether the image examines videos",
      status == 200 and health["ok"] and isinstance(health.get("video"), bool), health)
check("health says which own programs an image built on ours carries - its README's text, or null",
      "extras" in health and (health["extras"] is None or isinstance(health["extras"], str)), health.get("extras"))
check("the instance takes sessions - it may send signals, and nothing was left behind at its start",
      health.get("unusable") is None, health.get("unusable"))
check("a session needs the token", call("POST", "/sessions", {"files": []}, token="wrong")[0] == 401)
status, answer = raw("POST", "/sessions", b"not json", {"Content-Type": "application/json"})
check("a body that is not JSON answers 400 with error", status == 400 and "error" in (answer or {}), (status, answer))
status, answer = call("GET", "/nothing")
check("an unknown route answers 404 with error", status == 404 and "error" in (answer or {}), (status, answer))
status, answer = raw("POST", "/sessions", iter([b'{"files": []}']), {"Transfer-Encoding": "chunked"})
check("a body without a length is refused (411) - it could pass the size limit", status == 411, (status, answer))

status, session = new_session({"machine.log": "INFO start\nERROR spindle stopped\nINFO restart\n"})
check("a session starts with its files", status == 201 and session, status)
check("a second session waits until the first ends", new_session({})[0] == 409)

answer = python(session, "lines = open('data/machine.log').read().splitlines()\nlen(lines)")
check("python reads the files and shows a last expression", answer["output"].strip() == "3", answer)
answer = python(session, "[l for l in lines if 'ERROR' in l]")
check("python keeps its variables like a notebook", "spindle stopped" in answer["output"], answer)
answer = python(session, "import pandas, numpy, openpyxl, matplotlib\nmatplotlib.use('Agg')\nimport matplotlib.pyplot\n"
                         "pandas.DataFrame({'a': [1, 2]})['a'].sum()")
check("pandas, numpy, openpyxl and matplotlib import", answer["error"] is None and "3" in answer["output"], answer)
answer = python(session, "1/0")
check("an error comes back as text", "ZeroDivisionError" in (answer["error"] or ""), answer)

answer = shell(session, "grep -c ERROR data/machine.log && sort data/machine.log | uniq -c | wc -l && jq -n 1+1")
check("the shell has grep, sort, uniq and jq", answer["output"].split() == ["1", "3", "2"], answer)
if health.get("video"):
    # A video with sound in full HD: ffmpeg's threads ran out of address space under the memory limit without
    # MALLOC_ARENA_MAX, at a fraction of the memory really used.
    answer = shell(session, "ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=1920x1080:rate=30 "
                            "-f lavfi -i sine -t 2 -c:v libx264 -preset ultrafast -c:a aac clip.mp4 "
                            "&& ffprobe -v error -show_entries format=duration -of csv=p=0 clip.mp4 "
                            "&& ffmpeg -hide_banner -loglevel error -ss 1 -i clip.mp4 -t 0.5 -vf fps=10,scale=640:-2 "
                            "frame_%02d.jpg && ls frame_*.jpg | wc -l")
    check("video: ffmpeg cuts frames out of a full HD video with sound", answer["output"].split() == ["2.000000", "5"],
          answer)
    answer = python(session, "from PIL import ImageFont\n"
                             "ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 18).getlength('Hüfte') > 0")
    check("video: Pillow writes umlauts in DejaVu - the prompt names that font", answer["output"].strip() == "True", answer)
    # RTMPose loads its baked-in models without network, under the limit per program (measured: 447 MB of 1 GB). On a
    # black frame its detector finds nobody and rtmlib takes the whole frame as one person, with scores posture drops.
    answer = python(session, "import numpy, posture\nfrom rtmlib import BodyWithFeet\n"
                             "points, scores = BodyWithFeet()(numpy.zeros((480, 640, 3), numpy.uint8))\n"
                             "(len(points), posture.person(points, scores))")
    check("video: RTMPose runs its baked-in models, offline, under the limit",
          answer["error"] is None and "load /opt/models/rtmlib/" in answer["output"]
          and answer["output"].strip().splitlines()[-1] in ("(0, {})", "(1, {})"), answer)
    # posture.py on a made-up pose with known angles: a left knee bent at a right angle, the trunk leaning 45 degrees.
    answer = python(session, "import numpy, posture\n"
                             "pose = {'left_shoulder': (200, 100), 'right_shoulder': (220, 100), 'left_hip': (100, 200), "
                             "'right_hip': (120, 200), 'left_knee': (100, 300), 'right_knee': (120, 300), "
                             "'left_ankle': (200, 300), 'right_ankle': (120, 400)}\n"
                             "points = numpy.zeros((1, 26, 2)); scores = numpy.zeros((1, 26))\n"
                             "for name, xy in pose.items(): points[0][posture.POINTS[name]] = xy; "
                             "scores[0][posture.POINTS[name]] = 0.9\n"
                             "found = posture.person(points, scores); values = posture.measure(found)\n"
                             "posture.draw(numpy.zeros((480, 640, 3), numpy.uint8), found, values)\n"
                             "(values['knee_left'], values['knee_right'], values['trunk_lean'], values['trunk_lean_side'])")
    check("video: posture measures a made-up pose's known angles and draws it",
          answer["error"] is None and answer["output"].strip() == "(90.0, 180.0, 45.0, 'right')", answer)
else:
    answer = shell(session, "command -v ffmpeg || echo none; python3 -c 'import rtmlib' 2>&1 | tail -1")
    check("the sandbox without video has neither ffmpeg nor rtmlib (nor posture)",
          answer["output"].split()[0] == "none" and "No module named" in answer["output"], answer)

answer = python(session, "import subprocess, os\nsubprocess.run(['wc', '-l', 'data/machine.log'])\nos.system('echo from-a-shell')\n"
                         "print('from-python')\n'kept'")
check("what a subprocess prints is output of the call, not an answer of the kernel",
      answer["error"] is None and "3 data/machine.log" in answer["output"] and "from-a-shell" in answer["output"]
      and "from-python" in answer["output"] and "'kept'" in answer["output"], answer)
answer = python(session, "lines")
check("the kernel survives a subprocess", "spindle stopped" in answer["output"], answer)
answer = python(session, "print('x' * 300000)\n'after'")
check("python's output is cut in the kernel, the call goes on", "more bytes cut" in answer["output"]
      and len(answer["output"]) < 200000 and answer["error"] is None, answer)
answer = shell(session, "yes | head -c 50000000; echo end")
check("a shell command's output is cut and the command stopped", "more output cut" in answer["output"]
      and len(answer["output"]) < 200000 and answer["exitCode"] == -1, answer)

answer = python(session, "import socket\ntry:\n    socket.create_connection(('1.1.1.1', 80), timeout=3)\n"
                         "    result = 'open'\nexcept OSError as e:\n    result = 'blocked ' + str(e.errno)\nresult")
check("no network for python", "blocked" in answer["output"], answer)
answer = shell(session, "exec 3<>/dev/tcp/1.1.1.1/80 && echo open || echo blocked")
check("no network for the shell", "blocked" in answer["output"] and "open" not in answer["output"], answer)
answer = shell(session, "cat /proc/1/environ | tr '\\0' '\\n' | grep -c SANDBOX_TOKEN; echo done")
check("the server's environment (token) is not readable", "SANDBOX_TOKEN" not in answer["output"]
      and not answer["output"].startswith("1"), answer)
answer = shell(session, "env | grep -c SANDBOX_TOKEN; echo done")
check("a program's own environment holds no token (shell)", answer["output"].startswith("0"), answer)
answer = python(session, "import os\n'SANDBOX_TOKEN' in os.environ")
check("a program's own environment holds no token (python)", "False" in answer["output"], answer)
answer = shell(session, "kill -9 1; kill -9 -1; echo still here")
check("no signals to the server or to every process", call("GET", "/health", token=None)[0] == 200, answer)
# SIGTERM, which the server handles (SIGKILL to PID 1 from inside the container the kernel drops anyway): by
# sigqueue (rt_sigqueueinfo) to its pid, and by tkill to one of its threads - the two ways past a rule on kill alone.
answer = python(session, "import ctypes, os, platform\nlibc = ctypes.CDLL(None, use_errno=True)\n"
                         "queued = libc.sigqueue(1, 15, ctypes.c_void_p(0))\n"
                         "tkill = {'x86_64': 200, 'aarch64': 130}.get(platform.machine())\n"
                         "tid = int(sorted(os.listdir('/proc/1/task'))[-1])\n"
                         "threaded = libc.syscall(tkill, tid, 15) if tkill else -1\n"
                         "f'sigqueue {queued} tkill {threaded}'")
check("no SIGTERM to the server by sigqueue or to one of its threads",
      "sigqueue -1 tkill -1" in answer["output"] and call("GET", "/health", token=None)[0] == 200, answer)
answer = shell(session, "touch /usr/x 2>&1; touch /opt/sandbox/x 2>&1; echo done")
check("the system is read-only", "Read-only" in answer["output"] or "Permission denied" in answer["output"], answer)

answer = python(session, "import os\ntry:\n    os.setsid()\n    result = 'left its group'\nexcept OSError:\n"
                         "    result = 'kept in its group'\nresult")
check("no program leaves the session's process group", "kept in its group" in answer["output"], answer)
answer = python(session, "import os\ntry:\n    os.setpgid(0, 0)\n    result = 'left its group'\nexcept OSError:\n"
                         "    result = 'kept in its group'\nresult")
check("no program starts a process group of its own", "kept in its group" in answer["output"], answer)
answer = python(session, "try:\n    block = bytearray(4 * 1024 ** 3)\n    result = 'got 4 GB'\nexcept MemoryError:\n"
                         "    result = 'memory limited'\nresult")
check("memory is limited", "memory limited" in answer["output"], answer)

answer = python(session, "import ctypes\nlibc = ctypes.CDLL(None)\nresult = libc.syscall(425, 8, ctypes.create_string_buffer(256))\n"
                         "'io_uring ' + ('blocked' if result < 0 else 'open')")
check("no io_uring (it would open sockets past the filter)", "io_uring blocked" in answer["output"], answer)

answer = python(session, "import ctypes\nlibc = ctypes.CDLL(None, use_errno=True)\n"
                         "result = libc.shmget(0x1234, 4096, 0o1600)\n"
                         "'shm ' + ('blocked' if result < 0 else 'open')")
check("no System V shared memory (it outlives the session)", "shm blocked" in answer["output"], answer)
# The user's keyring and POSIX message queues outlive a session too; a namespace of its own would leave the filter's
# view of the process. By syscall number where libc has no wrapper (add_key: libkeyutils).
answer = python(session, "import ctypes, os, platform\nlibc = ctypes.CDLL(None, use_errno=True)\n"
                         "add_key = {'x86_64': 248, 'aarch64': 217}.get(platform.machine())\n"
                         "key = libc.syscall(add_key, b'user', b'left', b'x', 1, -4) if add_key else -1\n"
                         "queue = libc.mq_open(b'/left', os.O_CREAT | os.O_RDWR, 0o600, None)\n"
                         "spaces = libc.unshare(0x10000000)\n"
                         "f'key {key < 0} queue {queue < 0} unshare {spaces < 0}'")
check("no keyring, no message queue, no namespace of its own",
      "key True queue True unshare True" in answer["output"], answer)
answer = python(session, "from multiprocessing import Pool\ndef double(x):\n    return 2 * x\n"
                         "with Pool(2) as pool:\n    total = sum(pool.map(double, [1, 2]))\ntotal")
check("multiprocessing works with the notebook's own functions", answer["output"].strip() == "6", answer)
answer = python(session, "import multiprocessing\nwith multiprocessing.get_context('spawn').Pool(1) as pool:\n"
                         "    result = pool.map(abs, [-5])\nresult")
check("a spawned worker does not run the kernel again", answer["output"].strip() == "[5]", answer)

# The image's health check runs every 30 s outside every session, as the same user with HOME=/work: a Python started
# without -I would run what a session leaves in the user's site directory - unconfined, with the token and a network.
answer = shell(session, "site=$(/usr/bin/python3 -I -c \"import sys; print('/work/.local/lib/python%d.%d/site-packages' "
                        "% sys.version_info[:2])\") && mkdir -p $site && "
                        "echo \"open('/tmp/health-check-ran', 'w').write('ran')\" > $site/usercustomize.py && echo planted")
if "planted" in answer["output"]:
    time.sleep(35)
    answer = shell(session, "cat /tmp/health-check-ran 2>/dev/null || echo 'not run'")
check("the image's health check runs nothing a session left in /work", "not run" in answer["output"], answer)

answer = python(session, "while True: pass")
check("a call running too long is stopped", "longer than" in (answer["error"] or ""), answer)
answer = python(session, "'fresh'")
check("python starts anew after a timeout", "fresh" in answer["output"], answer)

shell(session, "nohup sleep 600 >/dev/null 2>&1 & echo started; echo secret > /tmp/left-behind; "
               "echo secret > /work/left-behind; echo secret > /dev/shm/left-behind; "
               "echo secret > /var/tmp/left-behind 2>/dev/null; touch /dev/mqueue/left-behind 2>/dev/null; "
               "echo secret > /run/lock/left-behind 2>/dev/null; "
               # Directories the session locked against itself: without the write right the cleanup could not
               # delete them, and the next session (same user) could give the right back and read what is inside.
               "mkdir -p locked/inner /tmp/locked; echo secret > locked/inner/left-behind; "
               "echo secret > /tmp/locked/left-behind; chmod 000 locked/inner locked /tmp/locked; "
               # The mounts' roots too, where the session's user owns them (Docker's tmpfs with uid=): writable but
               # not listable, a cleanup that does not give the rights back stops at the first of them.
               "chmod 555 data; chmod 300 /work /tmp 2>/dev/null; chmod 000 .; true")
check("the session ends", call("DELETE", f"/sessions/{session}")[0] == 204)
status, next_session = new_session({})
check("the next session starts", status == 201, status)
answer = shell(next_session, "chmod -R u+rwx /work /tmp 2>/dev/null; find " + " ".join(CLEANED) + " "
                             "2>/dev/null; grep -l -e sleep -e nohup /proc/[0-9]*/comm 2>/dev/null | wc -l; "
                             "grep -h '^State:.*Z' /proc/[0-9]*/status 2>/dev/null | wc -l; echo done")
check("nothing of the session before is left - no files anywhere writable (locked directories included), "
      "no processes, no zombies",
      "left-behind" not in answer["output"] and "locked" not in answer["output"] and session not in answer["output"]
      and answer["output"].split()[-3:-1] == ["0", "0"], answer)
# The list above only holds if it names every writable place: the runtime decides which there are (Podman's tmpfs on
# /run brings /run/lock, Docker's does not), so the code looks for them itself.
answer = shell(next_session, r"find / \( -path /proc -o -path /sys \) -prune -o -type d -writable -print 2>/dev/null")
uncleaned = [path for path in answer["output"].split()
             if not any(path == place or path.startswith(place + "/") for place in CLEANED)]
check("every place the code can write to is emptied when a session ends", not uncleaned, uncleaned)
call("DELETE", f"/sessions/{next_session}")

status, named = new_session([("src/a/Util.java", "a\n"), ("src/b/Util.java", "b\n"), ("/abs/x.txt", "x\n"),
                             ("export.csv", "1\n"), ("export.csv", "2\n")])
answer = shell(named, "cat data/src/a/Util.java data/src/b/Util.java data/abs/x.txt data/export.csv "
                      "'data/export (2).csv'")
check("files keep their folders, and a name given twice overwrites nothing",
      status == 201 and answer["output"].split() == ["a", "b", "x", "1", "2"], (status, answer))
call("DELETE", f"/sessions/{named}")
check("a name leaving data/ is refused", new_session({"../x.txt": "x"})[0] == 400)

# Files streamed in (a video - more than a JSON body takes) and read back out (the frames the code cut).
status, files = new_session({})
answer = put_file(files, "clips/run.mp4", [b"frame-" * 10, b"end"], 63)
check("a file is streamed into data/, its folders kept", answer == (201, {"name": "clips/run.mp4"})
      and shell(files, "cat data/clips/run.mp4")["output"].endswith("frame-end"), answer)
check("a file name leaving data/ is refused", put_file(files, "../x", [b"x"], 1)[0] == 400)
check("a file above SANDBOX_FILE_MB is refused before it is read", put_file(files, "big", [], 513 * 1024 * 1024)[0] == 413)
megabyte = b"\0" * (1024 * 1024)
answer = put_file(files, "large.bin", (megabyte for _ in range(300)), 300 * 1024 * 1024)
check("a file larger than a JSON body (300 MB) is streamed in",
      answer[0] == 201 and shell(files, "stat -c %s data/large.bin")["output"].strip() == str(300 * 1024 * 1024), answer)
shell(files, "rm data/large.bin; mkdir -p out && printf 'still' > out/frame.jpg && ln -s /etc out/etc "
             "&& ln -s /proc/self/environ out/environ && ln -s ../../ out/up")
check("a file the code wrote is read back", get_file(files, "out/frame.jpg") == (200, b"still"))
check("... also under its absolute path in the session",
      get_file(files, shell(files, "pwd")["output"].strip() + "/out/frame.jpg") == (200, b"still"))
for path in ["../../etc/passwd", "/etc/passwd", "out/etc/passwd", "out/environ", "out/up/etc/passwd", "out", "nothing"]:
    check(f"no file outside the session or that is none: {path}", get_file(files, path)[0] == 400)
shell(files, "mkfifo out/pipe")
try:
    pipe = get_file(files, "out/pipe")[0]
except OSError:  # the request timed out: the server's thread waits on the pipe for a writer
    pipe = None
check("a named pipe is no file - refused at once, no thread of the server left waiting on it", pipe == 400, pipe)
check("files of a session that ended are not read", call("DELETE", f"/sessions/{files}")[0] == 204
      and get_file(files, "out/frame.jpg")[0] == 404)

status, health = call("GET", "/health", token=None)
check("the instance still takes sessions after all of them ended - every process killed, every place emptied",
      health.get("unusable") is None, health.get("unusable"))

print("selftest", "passed" if not failures else f"FAILED: {len(failures)}")
sys.exit(1 if failures else 0)
