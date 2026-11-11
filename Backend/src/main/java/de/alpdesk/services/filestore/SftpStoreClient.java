package de.alpdesk.services.filestore;

import de.alpdesk.services.common.ServiceException;
import io.micronaut.http.HttpStatus;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SFTP through Apache MINA SSHD. One client for the whole app; each connection carries what its server's key is
 * checked against. The password goes out only after the key matched the confirmed fingerprint - before that the
 * connection ends after the key exchange.
 */
@Singleton
public class SftpStoreClient implements FileStoreClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(15);
    /**
     * How long one SFTP request waits for its answer (MINA's idle timeout, else 10 minutes): a server that hangs with
     * the connection open would hold one of the two task threads for all that time, and a cancel waits for it.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final AttributeRepository.AttributeKey<HostKeyCheck> HOST_KEY = new AttributeRepository.AttributeKey<>();

    private final SshClient client;

    public SftpStoreClient() {
        client = SshClient.setUpDefaultClient();
        // Only what a store gives: no ~/.ssh/config of the server, none of its own keys, the password alone.
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE));
        CoreModuleProperties.IDLE_TIMEOUT.set(client, READ_TIMEOUT);
        client.setServerKeyVerifier((session, remote, key) ->
                session.getConnectionContext().getAttribute(HOST_KEY).accept(KeyUtils.getFingerPrint(key)));
        client.start();
    }

    @PreDestroy
    void stop() throws IOException {
        client.stop();
        client.close();
    }

    @Override
    public CheckResult check(InetAddress address, ServerSettings settings, String password) {
        HostKeyCheck hostKey = new HostKeyCheck(settings.fingerprint());
        try (ClientSession session = connect(address, settings, hostKey)) {
            if (settings.fingerprint() == null) {
                return CheckResult.unconfirmed(hostKey.seen);
            }
            if (!hostKey.matches()) {
                return CheckResult.changed(hostKey.seen);
            }
            login(session, password);
            return CheckResult.ok(hostKey.seen, list(session, settings.basePath()));
        } catch (IOException e) {
            if (hostKey.seen != null && settings.fingerprint() != null && !hostKey.matches()) {
                return CheckResult.changed(hostKey.seen);
            }
            throw unreachable(settings, e);
        }
    }

    @Override
    public FileStoreSession open(InetAddress address, ServerSettings settings, String password) {
        HostKeyCheck hostKey = new HostKeyCheck(settings.fingerprint());
        ClientSession session = null;
        try {
            session = connect(address, settings, hostKey);
            if (settings.fingerprint() == null) {
                throw StoreFailures.unconfirmed();
            }
            if (!hostKey.matches()) {
                throw StoreFailures.changed();
            }
            login(session, password);
            return new SftpSession(session, SftpClientFactory.instance().createSftpClient(session));
        } catch (IOException e) {
            closeQuietly(session);
            if (hostKey.seen != null && settings.fingerprint() != null && !hostKey.matches()) {
                throw StoreFailures.changed();
            }
            throw unreachable(settings, e);
        } catch (RuntimeException e) {
            closeQuietly(session);
            throw e;
        }
    }

    private static void closeQuietly(ClientSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (IOException ignored) {
            // the connection is dropped anyway
        }
    }

    /** Connects and waits for the key exchange - the server's key is known (and checked) once it is through. */
    private ClientSession connect(InetAddress address, ServerSettings settings, HostKeyCheck hostKey) throws IOException {
        ClientSession session = client.connect(settings.username(), new InetSocketAddress(address, settings.port()),
                        AttributeRepository.ofKeyValuePair(HOST_KEY, hostKey), null)
                .verify(CONNECT_TIMEOUT).getSession();
        Set<ClientSession.ClientSessionEvent> reached = session.waitFor(
                EnumSet.of(ClientSession.ClientSessionEvent.WAIT_AUTH, ClientSession.ClientSessionEvent.CLOSED),
                CONNECT_TIMEOUT);
        if (!reached.contains(ClientSession.ClientSessionEvent.WAIT_AUTH) && hostKey.seen == null) {
            session.close();
            throw new IOException("No key exchange with " + settings.host());
        }
        return session;
    }

    private static void login(ClientSession session, String password) {
        session.addPasswordIdentity(password);
        try {
            session.auth().verify(AUTH_TIMEOUT);
        } catch (IOException e) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.loginFailed",
                    "The server refused the user name or the password");
        }
    }

    /** Opens the folder first: a folder that cannot be read fails there, not halfway through the listing. */
    private static List<String> list(ClientSession session, String folder) throws IOException {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
             SftpClient.CloseableHandle directory = openFolder(sftp, folder)) {
            List<String> names = new ArrayList<>();
            List<SftpClient.DirEntry> entries;
            while (names.size() < CHECK_ENTRIES && (entries = sftp.readDir(directory)) != null) {
                for (SftpClient.DirEntry entry : entries) {
                    if (!entry.getFilename().equals(".") && !entry.getFilename().equals("..") && names.size() < CHECK_ENTRIES) {
                        names.add(entry.getFilename());
                    }
                }
            }
            names.sort(String::compareTo);
            return names;
        }
    }

    private static SftpClient.CloseableHandle openFolder(SftpClient sftp, String folder) throws IOException {
        try {
            return sftp.openDir(folder);
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE || e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH
                    || e.getStatus() == SftpConstants.SSH_FX_PERMISSION_DENIED) {
                throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.folderNotFound",
                        "The folder " + folder + " does not exist or may not be read", Map.of("path", folder));
            }
            throw e;
        }
    }

    private static ServiceException unreachable(ServerSettings settings, IOException e) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.unreachable", "The server " + settings.host()
                + ":" + settings.port() + " could not be reached: " + e.getMessage(), Map.of("host", settings.host()));
    }

    /**
     * What one connection's server key is checked against. Without a confirmed fingerprint the key is accepted to end
     * the key exchange - the connection is closed before any login.
     */
    private static final class HostKeyCheck {
        private final String confirmed;
        private volatile String seen;

        HostKeyCheck(String confirmed) {
            this.confirmed = confirmed;
        }

        boolean accept(String fingerprint) {
            seen = fingerprint;
            return confirmed == null || matches();
        }

        boolean matches() {
            return confirmed != null && confirmed.equals(seen);
        }
    }

    /** One logged-in connection with its SFTP channel. */
    private static final class SftpSession implements FileStoreSession {
        private final ClientSession session;
        private final SftpClient sftp;

        SftpSession(ClientSession session, SftpClient sftp) {
            this.session = session;
            this.sftp = sftp;
        }

        @Override
        public List<RemoteFile> list(String folder) {
            try (SftpClient.CloseableHandle directory = openFolder(sftp, folder)) {
                List<RemoteFile> files = new ArrayList<>();
                List<SftpClient.DirEntry> entries;
                int read = 0;
                while ((entries = sftp.readDir(directory)) != null) {
                    read += entries.size();
                    if (read > MAX_ENTRIES) {
                        throw StoreFailures.tooManyEntries(folder);
                    }
                    for (SftpClient.DirEntry entry : entries) {
                        SftpClient.Attributes attributes = entry.getAttributes();
                        boolean subfolder = attributes.isDirectory() && !RemoteFile.isDotEntry(entry.getFilename());
                        if (attributes.isRegularFile() || subfolder) {
                            files.add(new RemoteFile(entry.getFilename(), subfolder ? 0 : attributes.getSize(),
                                    attributes.getModifyTime() == null ? null : attributes.getModifyTime().toInstant(),
                                    subfolder));
                        }
                    }
                }
                files.sort(Comparator.comparing(RemoteFile::name));
                return files;
            } catch (IOException e) {
                throw StoreFailures.readFailed(folder, e);
            }
        }

        @Override
        public byte[] read(String path, long offset, int maxBytes) {
            try (SftpClient.CloseableHandle file = sftp.open(path, SftpClient.OpenMode.Read)) {
                byte[] buffer = new byte[maxBytes];
                int total = 0;
                while (total < maxBytes) {
                    int read = sftp.read(file, offset + total, buffer, total, maxBytes - total);
                    if (read <= 0) {
                        break;
                    }
                    total += read;
                }
                return total == maxBytes ? buffer : Arrays.copyOf(buffer, total);
            } catch (IOException e) {
                throw StoreFailures.readFailed(path, e);
            }
        }

        /**
         * Written beside it as a temporary file, then renamed over it - a server without rename onto an existing file
         * (SFTP version 3) gets the old file removed first. A temporary file left by a failure is removed - unless the
         * old file is gone already: then it is the only copy and stays, named in the failure.
         */
        @Override
        public void write(String path, byte[] content) {
            String temporary = path + ".tmp-" + UUID.randomUUID().toString().substring(0, 8);
            try {
                try (OutputStream out = sftp.write(temporary)) {
                    out.write(content);
                }
                renameOver(temporary, path);
            } catch (IOException e) {
                removeQuietly(temporary);
                throw StoreFailures.writeFailed(path, e);
            }
        }

        /**
         * OpenSSH's posix-rename where the server has it (onto an existing file, at once); SFTP 5 and later with
         * overwrite; else - SFTP 3 without the extension - the old file removed first.
         */
        private void renameOver(String from, String to) throws IOException {
            OpenSSHPosixRenameExtension posix = sftp.getExtension(OpenSSHPosixRenameExtension.class);
            if (posix != null && posix.isSupported()) {
                posix.posixRename(from, to);
            } else if (sftp.getVersion() >= 5) {
                sftp.rename(from, to, SftpClient.CopyMode.Overwrite);
            } else {
                removeQuietly(to);
                try {
                    sftp.rename(from, to);
                } catch (IOException e) {
                    throw StoreFailures.writeKept(to, from, e.getMessage());
                }
            }
        }

        private void removeQuietly(String path) {
            try {
                sftp.remove(path);
            } catch (IOException ignored) {
                // not there - nothing to remove
            }
        }

        @Override
        public void close() {
            try {
                sftp.close();
            } catch (IOException ignored) {
                // the session below is closed next
            }
            closeQuietly(session);
        }
    }
}
