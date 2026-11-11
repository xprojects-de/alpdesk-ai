package de.alpdesk.services.filestore;

import de.alpdesk.services.common.ServiceException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.FilterInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * FTPS (FTP with explicit TLS, AUTH TLS) through Apache Commons Net - plain FTP is never spoken: the password and the
 * files would cross the network readable. A certificate an authority the installation trusts vouches for is taken as
 * it is (renewing it changes nothing); any other - a server's own, common in a company's network - must match the
 * fingerprint a tenant admin confirmed, and before that nothing is logged in to.
 */
@Singleton
public class FtpsStoreClient implements FileStoreClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    /**
     * Bytes of a listing read at most: Commons Net reads a listing whole before it parses it, so a server a tenant
     * admin runs could answer without end and fill the memory every tenant shares (FileStoreSession.MAX_ENTRIES).
     * Holds MAX_ENTRIES lines of MLSD with its facts and a long name; a file's transfer is not bounded here - read()
     * takes at most what it was asked for.
     */
    static final int LISTING_MAX_BYTES = 32 * 1024 * 1024;
    /** "Requested action not taken" - Commons Net names no constant for it. */
    private static final int INVALID_RESTART_OFFSET = 554;

    @Override
    public CheckResult check(InetAddress address, ServerSettings settings, String password) {
        CertificateCheck certificate = new CertificateCheck(settings.fingerprint());
        SSLContext context = sslContext(certificate);
        FTPSClient ftp = new BoundedListings(context);
        try {
            connect(ftp, address, settings, certificate);
            if (certificate.isChanged()) {
                return CheckResult.changed(certificate.seen);
            }
            if (!certificate.trusted()) {
                return CheckResult.unconfirmed(certificate.seen);
            }
            login(ftp, settings, password, context);
            return CheckResult.ok(certificate.seen, list(ftp, settings.basePath()));
        } catch (IOException e) {
            if (certificate.isChanged()) {
                return CheckResult.changed(certificate.seen);
            }
            throw unreachable(settings, e);
        } finally {
            disconnect(ftp);
        }
    }

    @Override
    public FileStoreSession open(InetAddress address, ServerSettings settings, String password) {
        return new FtpsSession(loggedIn(address, settings, password), () -> loggedIn(address, settings, password));
    }

    /** A connection whose certificate was checked against the confirmed one, logged in - every connection of a session. */
    private FTPSClient loggedIn(InetAddress address, ServerSettings settings, String password) {
        CertificateCheck certificate = new CertificateCheck(settings.fingerprint());
        SSLContext context = sslContext(certificate);
        FTPSClient ftp = new BoundedListings(context);
        try {
            connect(ftp, address, settings, certificate);
            if (certificate.isChanged()) {
                throw StoreFailures.changed();
            }
            if (!certificate.trusted()) {
                throw StoreFailures.unconfirmed();
            }
            login(ftp, settings, password, context);
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            return ftp;
        } catch (IOException e) {
            disconnect(ftp);
            if (certificate.isChanged()) {
                throw StoreFailures.changed();
            }
            throw unreachable(settings, e);
        } catch (RuntimeException e) {
            disconnect(ftp);
            throw e;
        }
    }

    /**
     * Connects by the host's name - its TLS certificate names that, not the address - and agrees on TLS before anything
     * else is sent. Connecting by name looks the host up again: a name server answering differently now (its entry
     * expired from the JVM's cache, or on purpose) must not lead to a server the address check never saw - so the
     * connection is dropped unless it reached the address allowed, before a fingerprint is shown or a password sent.
     */
    private static void connect(FTPSClient ftp, InetAddress address, ServerSettings settings, CertificateCheck certificate)
            throws IOException {
        ftp.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        ftp.setDefaultTimeout(READ_TIMEOUT_MILLIS);
        ftp.setDataTimeout(Duration.ofMillis(READ_TIMEOUT_MILLIS));
        ftp.setEndpointCheckingEnabled(true);
        // File names in UTF-8, as every current server writes them (RFC 2640) - commons-net's default Latin-1 turns
        // "Störung.log" into another name, and a pattern never finds it. Not by FEAT: that would go out before TLS.
        ftp.setControlEncoding(StandardCharsets.UTF_8);
        // The data connection always goes to the control connection's server, whatever address PASV names: a server
        // must not send our app to another host of its network.
        ftp.setPassiveNatWorkaroundStrategy(_ -> address.getHostAddress());
        try {
            ftp.connect(settings.host(), settings.port());
        } catch (SSLException e) {
            if (certificate.seen == null) {
                throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.tlsRefused", "The server "
                        + settings.host() + " offers no TLS (AUTH TLS) - plain FTP is not used",
                        Map.of("host", settings.host()));
            }
            throw e;
        }
        if (!address.equals(ftp.getRemoteAddress())) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.addressChanged", "The host "
                    + settings.host() + " answered with another address while connecting - try again",
                    Map.of("host", settings.host()));
        }
    }

    private static void login(FTPSClient ftp, ServerSettings settings, String password, SSLContext context)
            throws IOException {
        if (!ftp.login(settings.username(), password)) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.loginFailed",
                    "The server refused the user name or the password");
        }
        ftp.execPBSZ(0);
        ftp.execPROT("P");
        // Some servers (Windows, FileZilla) send UTF-8 names only when asked; one that does anyway may refuse it
        ftp.sendCommand("OPTS", "UTF8 ON");
        ftp.setSocketFactory(new ResumingDataSockets(context.getSocketFactory(), settings));
        ftp.enterLocalPassiveMode();
    }

    private static List<String> list(FTPSClient ftp, String folder) throws IOException {
        if (!ftp.changeWorkingDirectory(folder)) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.folderNotFound",
                    "The folder " + folder + " does not exist or may not be read", Map.of("path", folder));
        }
        String[] names;
        try {
            names = ftp.listNames();
        } catch (ListingTooLong e) {
            throw StoreFailures.tooManyEntries(folder);
        }
        if (names == null && isEmptyFolderReply(ftp.getReplyCode())) {
            return List.of();
        }
        if (names == null) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.dataConnectionFailed",
                    "The server refused the data connection: " + ftp.getReplyString().strip());
        }
        if (names.length > FileStoreSession.MAX_ENTRIES) {
            throw StoreFailures.tooManyEntries(folder);
        }
        List<String> sorted = new ArrayList<>(Arrays.asList(names).subList(0, Math.min(names.length, CHECK_ENTRIES)));
        sorted.replaceAll(FtpsStoreClient::lastSegment);
        sorted.sort(String::compareTo);
        return sorted;
    }

    /** Many servers answer NLST in an empty folder with "450/550 No files found" instead of an empty list. */
    private static boolean isEmptyFolderReply(int reply) {
        return reply == FTPReply.FILE_ACTION_NOT_TAKEN || reply == FTPReply.FILE_UNAVAILABLE;
    }

    /** MLSD's and LIST's 450/550: the folder's entries may not be read - an empty folder answers 226. */
    private static void requireListable(FTPSClient ftp, String folder) {
        if (isEmptyFolderReply(ftp.getReplyCode())) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.folderNotFound",
                    "The folder " + folder + " does not exist or may not be read", Map.of("path", folder));
        }
    }

    /** Some servers answer NLST with paths, others with names. */
    private static String lastSegment(String name) {
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private static void disconnect(FTPSClient ftp) {
        if (!ftp.isConnected()) {
            return;
        }
        try {
            if (FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                ftp.logout();
            }
        } catch (IOException ignored) {
            // the connection is dropped anyway
        }
        try {
            ftp.disconnect();
        } catch (IOException ignored) {
            // nothing left to close
        }
    }

    private static ServiceException unreachable(ServerSettings settings, IOException e) {
        return new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.unreachable", "The server " + settings.host()
                + ":" + settings.port() + " could not be reached: " + e.getMessage(), Map.of("host", settings.host()));
    }

    private static SSLContext sslContext(CertificateCheck certificate) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{certificate}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS is not available", e);
        }
    }

    static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new CertificateException(e);
        }
    }

    /**
     * Checks the server's certificate on every handshake: the installation's authorities first (with the host name),
     * else the confirmed fingerprint. Without a confirmed one the handshake goes through so its fingerprint can be
     * shown - and check() stops before the login. Once the control connection's server proved itself, every later
     * handshake (a data connection that did not resume its session) must prove itself too - else someone on the way
     * could hand over files under a certificate of their own.
     */
    static final class CertificateCheck extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager system;
        private final String confirmed;
        private volatile String seen;
        private volatile boolean trustedBySystem;
        private volatile boolean proven;

        CertificateCheck(String confirmed) {
            this(confirmed, systemTrustManager());
        }

        CertificateCheck(String confirmed, X509ExtendedTrustManager system) {
            this.confirmed = confirmed;
            this.system = system;
        }

        boolean trusted() {
            return trustedBySystem || confirmed != null && confirmed.equals(seen);
        }

        boolean isChanged() {
            return seen != null && !trustedBySystem && confirmed != null && !confirmed.equals(seen);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            String fingerprint = fingerprint(chain[0]);
            boolean bySystem = vouchedForBySystem(chain, authType, socket);
            boolean asConfirmed = fingerprint.equals(confirmed);
            if (proven && !bySystem && !asConfirmed) {
                throw new CertificateException("A data connection presented another certificate than the server proved "
                        + "itself with");
            }
            seen = fingerprint;
            trustedBySystem = bySystem;
            proven |= bySystem || asConfirmed;
            if (isChanged()) {
                throw new CertificateException("The server's certificate is not the one confirmed");
            }
        }

        private boolean vouchedForBySystem(X509Certificate[] chain, String authType, Socket socket) {
            try {
                system.checkServerTrusted(chain, authType, socket);
                return true;
            } catch (CertificateException e) {
                return false;
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("Not used with engines");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            checkServerTrusted(chain, authType, (Socket) null);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("Client certificates are not checked");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("Client certificates are not checked");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Client certificates are not checked");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return system.getAcceptedIssuers();
        }

        private static X509ExtendedTrustManager systemTrustManager() {
            try {
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init((KeyStore) null);
                for (TrustManager manager : factory.getTrustManagers()) {
                    if (manager instanceof X509ExtendedTrustManager extended) {
                        return extended;
                    }
                }
                throw new IllegalStateException("No X509 trust manager");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("The installation's authorities cannot be read", e);
            }
        }
    }

    /**
     * Data connections that resume the control connection's TLS session. Many servers require that (vsftpd's
     * require_ssl_reuse, ProFTPD's default, FileZilla Server): they refuse a data connection whose session is new, so
     * nobody else can take over a transfer. JSSE resumes a session it finds under the peer's host and port - and takes
     * the port from the socket below - so the data connection is layered over a socket that reports the control
     * connection's port. No reflection, no JVM flag.
     */
    private static final class ResumingDataSockets extends SocketFactory {
        private final SSLSocketFactory ssl;
        private final String controlHost;
        private final int controlPort;

        /** The control connection's context - its client session cache holds the session to resume. */
        ResumingDataSockets(SSLSocketFactory ssl, ServerSettings settings) {
            this.ssl = ssl;
            this.controlHost = settings.host();
            this.controlPort = settings.port();
        }

        @Override
        public Socket createSocket() {
            return new ResumingDataSocket(ssl, controlHost, controlPort);
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new UnsupportedOperationException("Data sockets are created unconnected");
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
            throw new UnsupportedOperationException("Data sockets are created unconnected");
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new UnsupportedOperationException("Data sockets are created unconnected");
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
            throw new UnsupportedOperationException("Data sockets are created unconnected");
        }
    }

    /** The client whose listings' data connections are bounded (LISTING_MAX_BYTES) - a file's transfer is not. */
    private static final class BoundedListings extends FTPSClient {
        BoundedListings(SSLContext context) {
            super(false, context);
        }

        @Override
        protected Socket _openDataConnection_(String command, String arg) throws IOException {
            Socket socket = super._openDataConnection_(command, arg);
            if (socket instanceof ResumingDataSocket data) {
                data.listing = switch (command) {
                    case "LIST", "MLSD", "NLST" -> true;
                    default -> false;
                };
            }
            return socket;
        }
    }

    /** Thrown by a listing's data connection once it read more than LISTING_MAX_BYTES. */
    static final class ListingTooLong extends IOException {
    }

    /** A listing's bytes, counted: more than LISTING_MAX_BYTES ends it with ListingTooLong. */
    static final class BoundedListing extends FilterInputStream {
        private long read;

        BoundedListing(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > LISTING_MAX_BYTES) {
                throw new ListingTooLong();
            }
        }
    }

    private static final class ResumingDataSocket extends Socket {
        private final SSLSocketFactory ssl;
        private final String controlHost;
        private final int controlPort;
        private final Socket plain;
        private SSLSocket tls;
        private boolean handshakeDone;
        /** Set by BoundedListings when this data connection carries a listing. */
        boolean listing;

        ResumingDataSocket(SSLSocketFactory ssl, String controlHost, int controlPort) {
            this.ssl = ssl;
            this.controlHost = controlHost;
            this.controlPort = controlPort;
            this.plain = new Socket() {
                @Override
                public int getPort() {
                    return ResumingDataSocket.this.controlPort;
                }
            };
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            plain.connect(endpoint, timeout);
            tls = (SSLSocket) ssl.createSocket(plain, controlHost, controlPort, true);
            tls.setUseClientMode(true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        /** FTP sends its command (NLST, RETR) on the control connection first - the server starts TLS after it. */
        private SSLSocket handshaken() throws IOException {
            if (!handshakeDone) {
                tls.startHandshake();
                handshakeDone = true;
            }
            return tls;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream in = handshaken().getInputStream();
            return listing ? new BoundedListing(in) : in;
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return handshaken().getOutputStream();
        }

        @Override
        public InetAddress getInetAddress() {
            return plain.getInetAddress();
        }

        @Override
        public boolean isConnected() {
            return plain.isConnected();
        }

        @Override
        public boolean isClosed() {
            return plain.isClosed();
        }

        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            plain.setSoTimeout(timeout);
        }

        @Override
        public int getSoTimeout() throws SocketException {
            return plain.getSoTimeout();
        }

        @Override
        public void setReceiveBufferSize(int size) throws SocketException {
            plain.setReceiveBufferSize(size);
        }

        @Override
        public void setSendBufferSize(int size) throws SocketException {
            plain.setSendBufferSize(size);
        }

        @Override
        public synchronized void close() throws IOException {
            if (tls != null) {
                tls.close();
            }
            plain.close();
        }
    }

    /**
     * One logged-in control connection; every listing and file gets a data connection of its own. A transfer broken off
     * before the file's end (a file larger than the step's limit, or grown since it was listed) ends the whole session
     * on some servers - ProFTPD closes the control connection without a word - so the session drops it itself and the
     * next command opens a new one, its certificate checked again.
     */
    private static final class FtpsSession implements FileStoreSession {
        private final Supplier<FTPSClient> reconnect;
        private @Nullable FTPSClient ftp;

        FtpsSession(FTPSClient ftp, Supplier<FTPSClient> reconnect) {
            this.ftp = ftp;
            this.reconnect = reconnect;
        }

        private FTPSClient ftp() {
            if (ftp == null) {
                ftp = reconnect.get();
            }
            return ftp;
        }

        /** MLSD where the server has it (exact sizes and times), else LIST. */
        @Override
        public List<RemoteFile> list(String folder) {
            FTPSClient ftp = ftp();
            try {
                if (!ftp.changeWorkingDirectory(folder)) {
                    throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.folderNotFound",
                            "The folder " + folder + " does not exist or may not be read", Map.of("path", folder));
                }
                FTPFile[] entries = ftp.mlistDir();
                // 450/550 to MLSD or LIST in a folder we could enter: its entries may not be read (no read right, busy)
                // - never an empty folder, which both answer with 226. Nor is LIST asked then: ProFTPD answers it with
                // an empty list where MLSD said "Permission denied". Taken as empty, a knowledge source would delete
                // the documents of every file below it.
                requireListable(ftp, folder);
                if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                    entries = ftp.listFiles();
                    requireListable(ftp, folder);
                }
                if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                    throw new ServiceException(HttpStatus.BAD_GATEWAY, "fileStore.dataConnectionFailed",
                            "The server refused the data connection: " + ftp.getReplyString().strip());
                }
                if (entries.length > MAX_ENTRIES) {
                    throw StoreFailures.tooManyEntries(folder);
                }
                List<RemoteFile> files = new ArrayList<>();
                for (FTPFile entry : entries) {
                    if (entry == null) {
                        continue;
                    }
                    String name = lastSegment(entry.getName());
                    // MLSD lists the folder itself and its parent as folders (type=cdir, pdir), LIST often too.
                    boolean subfolder = entry.isDirectory() && !RemoteFile.isDotEntry(name);
                    if (entry.isFile() || subfolder) {
                        files.add(new RemoteFile(name, subfolder ? 0 : entry.getSize(),
                                entry.getTimestamp() == null ? null : entry.getTimestamp().toInstant(), subfolder));
                    }
                }
                files.sort(Comparator.comparing(RemoteFile::name));
                return files;
            } catch (ListingTooLong e) {
                drop();
                throw StoreFailures.tooManyEntries(folder);
            } catch (IOException e) {
                drop();
                throw StoreFailures.readFailed(folder, e);
            }
        }

        /** REST before RETR; read up to maxBytes - a transfer broken off before the file's end drops the connection. */
        @Override
        public byte[] read(String path, long offset, int maxBytes) {
            FTPSClient ftp = ftp();
            try {
                ftp.setRestartOffset(offset);
                InputStream in = ftp.retrieveFileStream(path);
                ftp.setRestartOffset(0);
                // ProFTPD refuses a restart offset past the file's end (554) instead of sending nothing.
                if (in == null && offset > 0 && ftp.getReplyCode() == INVALID_RESTART_OFFSET) {
                    return new byte[0];
                }
                if (in == null) {
                    throw new IOException(ftp.getReplyString().strip());
                }
                byte[] bytes;
                boolean wholeRest;
                try (in) {
                    bytes = in.readNBytes(maxBytes);
                    wholeRest = in.read() < 0;
                }
                if (!wholeRest) {
                    drop();
                    return bytes;
                }
                // a transfer the server ended with an error (a data connection it refused) delivered nothing - never
                // taken for an empty file, which a task writing a CSV would then replace
                if (!ftp.completePendingCommand()) {
                    String reply = ftp.getReplyString().strip();
                    drop();
                    throw new IOException(reply);
                }
                return bytes;
            } catch (IOException e) {
                drop();
                throw StoreFailures.readFailed(path, e);
            }
        }

        /**
         * Stored as a temporary file, then renamed over it (RNFR/RNTO) - a server that refuses to rename onto an existing
         * file gets the old one deleted first. A temporary file left by a failure is deleted - unless the old one is
         * gone already: then it is the only copy and stays, named in the failure.
         */
        @Override
        public void write(String path, byte[] content) {
            FTPSClient ftp = ftp();
            String temporary = path + ".tmp-" + UUID.randomUUID().toString().substring(0, 8);
            try {
                if (!ftp.storeFile(temporary, new ByteArrayInputStream(content))) {
                    throw new IOException(ftp.getReplyString().strip());
                }
                if (!ftp.rename(temporary, path)) {
                    ftp.deleteFile(path);
                    if (!ftp.rename(temporary, path)) {
                        String reply = ftp.getReplyString().strip();
                        drop();
                        throw StoreFailures.writeKept(path, temporary, reply);
                    }
                }
                // the next transfer on a fresh connection: after an upload ProFTPD's next data connection was seen to
                // deliver nothing on the old one (4 November 2026)
                drop();
            } catch (IOException e) {
                deleteQuietly(temporary);
                drop();
                throw StoreFailures.writeFailed(path, e);
            }
        }

        private void deleteQuietly(String path) {
            try {
                if (ftp != null) {
                    ftp.deleteFile(path);
                }
            } catch (IOException ignored) {
                // the connection is gone - the next session finds the temporary file, nothing reads it
            }
        }

        /** Without waiting for the server's answer to the broken-off transfer - it may never come. */
        private void drop() {
            if (ftp != null) {
                disconnect(ftp);
                ftp = null;
            }
        }

        @Override
        public void close() {
            drop();
        }
    }
}
