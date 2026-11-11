# A tenant's own sandbox image, built on ours: its own programs, and a README that tells the chat's model what they are
# and how to call them (README.md "Your own sandbox image"). Build it next to this file and tag it with the version of
# the base image:
#
#   podman build -t registry.example.com/musterfirma/sandbox:<version> .
#
# The example's program is invented: simtest, the checker of the Musterfirma's test scripts (simtest.py) - and pytest,
# with which its Python test scripts run. Replace both with yours.
ARG BASE=ghcr.io/xprojects-de/alpdesk-ai-sandbox:2.0.0
FROM ${BASE}
USER 0
# Programs from Ubuntu's packages, Python libraries next to ours: into /opt/extras/python, made known to the code's
# Python by a .pth file - nothing is written into /opt/venv itself. The image's Python has no pip: the system's is
# installed for the build and removed again.
RUN apt-get update && apt-get install -y --no-install-recommends python3-pip \
 && python3 -m pip install --no-cache-dir --target /opt/extras/python pytest \
 && apt-get purge -y --auto-remove python3-pip && rm -rf /var/lib/apt/lists/* \
 && echo /opt/extras/python > "$(echo /opt/venv/lib/python3*/site-packages)/extras.pth"
# Programs of your own: anywhere on the PATH (/usr/local/bin).
COPY simtest.py /usr/local/bin/simtest
RUN chmod 755 /usr/local/bin/simtest
# What the model reads - at most 16 KB.
COPY README.md /opt/extras/README.md
USER 10001:10001
