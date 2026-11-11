package de.alpdesk.services.filestore

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.Ports
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.MountableFile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

/**
 * The file store specs' servers, built from src/test/resources/filestore/ and started once for the whole test JVM: an
 * SFTP server (OpenSSH) and an FTPS server (ProFTPD with a certificate of its own, requiring data connections to resume
 * the control connection's TLS session). Both have the user "check" with a folder /home/check/logs holding one log file.
 * The S3 server is SeaweedFS (pulled, checks the requests' signatures - MinIO publishes no images any more), its bucket
 * "check" filled like the other servers' folders, plus "many" with more files than one listing page holds.
 */
class FileStoreServers {

    static final String USER = "check"
    static final String PASSWORD = "check-secret"
    static final String LOG_FILE = "2026-10-13_interface.log"
    /** In the folder names of both servers' images: a name outside ASCII. */
    static final String UMLAUT_FILE = "Störung.log"
    /** ProFTPD's passive ports, published 1:1 - the data connection goes to the port the server names. */
    private static final List<Integer> PASSIVE_PORTS = (39400..39404).toList()

    static final String S3_ACCESS_KEY = "CHECKACCESSKEY"
    static final String S3_SECRET = "check-secret-key"
    static final String S3_BUCKET = "check"
    /** A bucket with nothing in it. */
    static final String S3_EMPTY_BUCKET = "leer"
    /** The files in the bucket's folder "many" - more than the 1000 one listing page holds. */
    static final int S3_MANY = 1005
    private static final String S3_IMAGE = "docker.io/chrislusf/seaweedfs:4.50"

    private static GenericContainer sftp
    private static GenericContainer ftps
    private static GenericContainer s3

    /** The SFTP server's port on localhost; the folder is /home/check/logs. */
    static synchronized int sftpPort() {
        if (sftp == null) {
            sftp = new GenericContainer(new ImageFromDockerfile("alpdesk-ai-test-sftp", false)
                    .withFileFromClasspath("Dockerfile", "filestore/sftp/Dockerfile"))
                    .withExposedPorts(22)
                    .waitingFor(Wait.forListeningPort())
            sftp.start()
        }
        sftp.getMappedPort(22)
    }

    /** The FTPS server's port on localhost; the folder is /logs (ProFTPD shows the user's home as /). */
    static synchronized int ftpsPort() {
        if (ftps == null) {
            ftps = new GenericContainer(new ImageFromDockerfile("alpdesk-ai-test-ftps", false)
                    .withFileFromClasspath("Dockerfile", "filestore/ftps/Dockerfile")
                    .withFileFromClasspath("tls.conf", "filestore/ftps/tls.conf"))
                    .withExposedPorts(21)
                    .withCreateContainerCmdModifier { cmd ->
                        cmd.withExposedPorts((cmd.exposedPorts as List<ExposedPort>) + PASSIVE_PORTS.collect { new ExposedPort(it) })
                        Ports bindings = cmd.hostConfig.portBindings ?: new Ports()
                        PASSIVE_PORTS.each { bindings.bind(new ExposedPort(it), Ports.Binding.bindPort(it)) }
                        cmd.hostConfig.withPortBindings(bindings)
                    }
                    .waitingFor(Wait.forListeningPorts(21))
            ftps.start()
        }
        ftps.getMappedPort(21)
    }

    /** The S3 server's endpoint on localhost (plain http - localhost is an internal host of the test properties). */
    static synchronized String s3Endpoint() {
        if (s3 == null) {
            s3 = new GenericContainer(S3_IMAGE)
                    .withCopyFileToContainer(MountableFile.forClasspathResource("filestore/s3/s3.json"), "/etc/s3.json")
                    .withCommand("server", "-s3", "-s3.config=/etc/s3.json", "-dir=/data")
                    .withExposedPorts(8333)
                    .waitingFor(Wait.forListeningPorts(8333))
            s3.start()
            fillS3("http://localhost:${s3.getMappedPort(8333)}")
        }
        "http://localhost:${s3.getMappedPort(8333)}"
    }

    private static void fillS3(String endpoint) {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(S3_ACCESS_KEY, S3_SECRET)))
                .build()
        try {
            // The gateway answers before its filer is up - the first bucket waits for it
            createBucketWhenReady(client, S3_BUCKET)
            client.createBucket { it.bucket(S3_EMPTY_BUCKET) }
            put(client, "logs/" + LOG_FILE, "2026-10-13 08:00:05 ERROR connection to interface X timed out\n")
            put(client, "names/" + UMLAUT_FILE, "INFO a name outside ASCII\n")
            put(client, "empty/", "")
            put(client, ".trash/old.log", "INFO deleted\n")
            put(client, "large/large.log", (1..200_000).collect { "2026-10-13 08:00:06 INFO line $it of a large log\n" }.join(""))
            (1..S3_MANY).each { put(client, String.format("many/%04d.log", it), "INFO file $it\n") }
        } finally {
            client.close()
        }
    }

    private static void createBucketWhenReady(S3Client client, String bucket) {
        long until = System.currentTimeMillis() + 60_000
        while (true) {
            try {
                client.createBucket { it.bucket(bucket) }
                return
            } catch (Exception e) {
                if (System.currentTimeMillis() > until) {
                    throw e
                }
                Thread.sleep(500)
            }
        }
    }

    private static void put(S3Client client, String key, String content) {
        client.putObject({ it.bucket(S3_BUCKET).key(key) }, RequestBody.fromString(content, java.nio.charset.StandardCharsets.UTF_8))
    }
}
