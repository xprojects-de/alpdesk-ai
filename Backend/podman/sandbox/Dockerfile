# The sandbox for the chat's Python and shell (docs/architecture/code-execution.md): a container of its own, next to the
# app, holding no secret but its token. It needs no root, no capabilities and no cgroups and runs on any kernel from 4.x
# on - Podman, Docker and Kubernetes with the "restricted" pod security alike. Build from the repository root (the
# image ships the licenses):
#
#   podman build -f Backend/podman/sandbox/Dockerfile -t localhost/alpdesk/alpdesk-ai-sandbox:latest .
#   podman build -f Backend/podman/sandbox/Dockerfile --target video -t localhost/alpdesk/alpdesk-ai-sandbox-video:latest .
#
# Two images from one file (decided 18 October 2026): the sandbox, the default target, and "video" - the same with what
# a video is examined with (ffmpeg, a person's joints with RTMPose, DejaVu for text drawn into a still). An operator
# gives a tenant the one it needs; the sandbox says in /health which it is.
#
# The base is plain Ubuntu on purpose: Debian slim, python:3.14-slim, a chiselled Ubuntu and Alpine were checked and
# measured - why Ubuntu, and when and how to check again: docs/architecture/code-execution.md "The base image".

# BUILD: two venvs - the Python libraries the code may use (/opt/venv) and the HTTP server's (/opt/server-venv), kept
# apart so that code never imports the server's packages and an update of one never touches the other. pip itself
# stays behind in this stage.
FROM docker.io/library/ubuntu:26.04 AS libraries
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv && rm -rf /var/lib/apt/lists/*
COPY Backend/podman/sandbox/requirements.txt Backend/podman/sandbox/requirements-server.txt /tmp/
RUN python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir --only-binary=:all: -r /tmp/requirements.txt \
    && /opt/venv/bin/pip uninstall -y pip \
    && python3 -m venv /opt/server-venv \
    && /opt/server-venv/bin/pip install --no-cache-dir --only-binary=:all: -r /tmp/requirements-server.txt \
    && /opt/server-venv/bin/pip uninstall -y pip

# BUILD of the video image's libraries (requirements-video.txt) into a folder of their own, /opt/video-libs - the
# video image adds it to the code's venv instead of copying the venv twice -, and the models of RTMPose "balanced" (a
# person detector and the joints with heels and toes) fetched into /opt/models/rtmlib, where rtmlib finds them through
# TORCH_HOME: a session has no network (docs/roadmap.md point 35, measured 18 October 2026).
FROM docker.io/library/ubuntu:26.04 AS video-libraries
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY Backend/podman/sandbox/requirements.txt Backend/podman/sandbox/requirements-video.txt /tmp/
RUN python3 -m venv /tmp/build-venv \
    && /tmp/build-venv/bin/pip install --no-cache-dir --only-binary=:all: -r /tmp/requirements.txt \
    && /tmp/build-venv/bin/pip install --no-cache-dir --only-binary=:all: --no-deps --target /opt/video-libs \
        -r /tmp/requirements-video.txt \
    && PYTHONPATH=/opt/video-libs TORCH_HOME=/opt/models/rtmlib /tmp/build-venv/bin/python3 -c \
        "from rtmlib import BodyWithFeet; BodyWithFeet()"

# RUNTIME
FROM docker.io/library/ubuntu:26.04 AS sandbox
# python3-seccomp confines every program a session starts (confine.py); the rest is what code and shell commands use.
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-seccomp \
        bash coreutils findutils diffutils grep gawk sed jq sqlite3 file bzip2 xz-utils gzip unzip \
    && rm -rf /var/lib/apt/lists/*
COPY --from=libraries /opt/venv /opt/venv
COPY --from=libraries /opt/server-venv /opt/server-venv
COPY Backend/podman/sandbox/app.py Backend/podman/sandbox/sessions.py Backend/podman/sandbox/confine.py \
     Backend/podman/sandbox/kernel.py /opt/sandbox/
COPY LICENSE NOTICE THIRD-PARTY.md /usr/share/doc/alpdesk-ai-sandbox/

# Not root - a program of a session runs as the same user, confined by confine.py.
RUN groupadd -r -g 10001 sandbox && useradd --no-log-init -r -u 10001 -g sandbox -d /work sandbox \
    && mkdir -p /work && chown sandbox:sandbox /work
USER 10001:10001

# /work (the session's files) and /tmp are the only places written to: mount both as tmpfs and the root read-only.
EXPOSE 8080
# -I: the check runs outside every session's confinement, as the sessions' user with HOME=/work - without it Python
# would run a usercustomize.py or .pth file a session's code left under /work/.local, unconfined and with the token.
# Not dumpable first (prctl 4, as the server does): a session's program, the same user, can then neither read the
# check's environment (the token) nor write its memory through /proc - only the instant before that line is left.
HEALTHCHECK --interval=30s --timeout=5s CMD ["/usr/bin/python3", "-I", "-c", "import ctypes; ctypes.CDLL(None).prctl(4, 0, 0, 0, 0); import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=4)"]
# The server is PID 1 itself and reaps what dies orphaned: an init like tini would hold the token in an environment a
# session (same user) could read through /proc - the server makes itself unreadable.
ENTRYPOINT ["/opt/server-venv/bin/python3", "-I", "/opt/sandbox/app.py"]

# THE VIDEO IMAGE: the sandbox, plus ffmpeg (with ffprobe) to cut frames and clips, DejaVu for text drawn into a still
# (Pillow's default font has no umlauts), rtmlib in the code's venv with the RTMPose models and posture.py (the
# posture's measures from its points). /health says "video": true.
FROM sandbox AS video
USER 0:0
COPY --from=video-libraries /opt/video-libs /opt/video-libs
COPY --from=video-libraries /opt/models /opt/models
# The posture's measures from RTMPose's points - the same definitions in every answer (posture.py).
COPY Backend/podman/sandbox/posture.py /opt/video-libs/posture.py
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && echo /opt/video-libs > "$(echo /opt/venv/lib/python3*/site-packages)/video-libs.pth"
USER 10001:10001

# The default target - a build without --target gets the sandbox without the video tools.
FROM sandbox
