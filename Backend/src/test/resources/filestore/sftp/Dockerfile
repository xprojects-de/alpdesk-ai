# The SFTP server of the file store specs (FileStoreServers): OpenSSH with password login, a user "check" and a folder
# with an invented log file. A new host key with every image build - the specs read it from a check.
FROM docker.io/library/debian:trixie-slim
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server \
 && rm -rf /var/lib/apt/lists/* && mkdir -p /run/sshd \
 && useradd -m -d /home/check -s /bin/bash check && echo "check:check-secret" | chpasswd \
 && mkdir -p /home/check/logs /home/check/empty /home/check/large /home/check/names /home/check/export \
 && printf 'INFO a name outside ASCII\n' > '/home/check/names/Störung.log' \
 && printf '2026-10-13 08:00:05 ERROR connection to interface X timed out\n' > /home/check/logs/2026-10-13_interface.log \
 && seq -f "2026-10-13 08:00:06 INFO line %.0f of a large log" 1 200000 > /home/check/large/large.log \
 && chown -R check /home/check \
 && printf 'PasswordAuthentication yes\nKbdInteractiveAuthentication no\nSubsystem sftp internal-sftp\n' > /etc/ssh/sshd_config.d/check.conf
EXPOSE 22
CMD ["/usr/sbin/sshd", "-D", "-e"]
