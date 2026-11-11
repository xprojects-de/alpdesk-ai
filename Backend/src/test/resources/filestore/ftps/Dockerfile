# The FTPS server of the file store specs (FileStoreServers): ProFTPD with explicit TLS required and a certificate of
# its own - and, like ProFTPD's default, data connections must resume the control connection's TLS session.
FROM docker.io/library/debian:trixie-slim
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends proftpd-core proftpd-mod-crypto openssl \
 && rm -rf /var/lib/apt/lists/* \
 && openssl req -x509 -nodes -newkey rsa:2048 -days 3650 -subj "/CN=ftps.example.com" \
      -keyout /etc/proftpd/key.pem -out /etc/proftpd/cert.pem \
 && useradd -m -d /home/check -s /bin/bash check && echo "check:check-secret" | chpasswd \
 && mkdir -p /home/check/logs /home/check/empty /home/check/large /home/check/names /home/check/export \
 && printf 'INFO a name outside ASCII\n' > '/home/check/names/Störung.log' \
 && printf '2026-10-13 08:00:05 ERROR connection to interface X timed out\n' > /home/check/logs/2026-10-13_interface.log \
 && seq -f "2026-10-13 08:00:06 INFO line %.0f of a large log" 1 200000 > /home/check/large/large.log \
 && mkdir -p /home/check/locked && printf 'INFO in a folder that may be entered, not listed\n' > /home/check/locked/hidden.log \
 && chown -R check /home/check \
 && chmod 0311 /home/check/locked
COPY tls.conf /etc/proftpd/conf.d/tls.conf
# The passive ports 39400-39404 are published by FileStoreServers - a range here is not readable for Testcontainers.
EXPOSE 21
CMD ["proftpd", "--nodaemon"]
