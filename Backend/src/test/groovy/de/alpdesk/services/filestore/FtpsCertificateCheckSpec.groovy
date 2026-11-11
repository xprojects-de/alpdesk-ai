package de.alpdesk.services.filestore

import spock.lang.Specification

import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * How an FTPS store's certificate is checked on every handshake (docs/architecture/file-stores.md "The server proves
 * itself"): one an authority of the installation vouches for, or the confirmed fingerprint - and once the control
 * connection's server proved itself, a data connection's handshake must prove itself too. No server: the handshakes are
 * the trust manager's calls, the installation's authorities a stand-in that vouches for one certificate.
 */
class FtpsCertificateCheckSpec extends Specification {

    X509Certificate vouched = certificate(1)
    X509Certificate own = certificate(2)
    X509Certificate other = certificate(3)
    Authorities authorities = new Authorities(vouchedFor: vouched)

    def "a server an authority vouches for is trusted - a data connection under another certificate is refused"() {
        given:
        def check = new FtpsStoreClient.CertificateCheck(null, authorities)

        when: "the control connection"
        handshake(check, vouched)

        then:
        check.trusted()

        when: "a data connection that did not resume the session, under a certificate of someone on the way"
        handshake(check, other)

        then:
        thrown(CertificateException)

        when: "a data connection under the server's own certificate"
        handshake(check, vouched)

        then:
        noExceptionThrown()
    }

    def "a server with a confirmed fingerprint is trusted - a data connection under another certificate is refused"() {
        given:
        def check = new FtpsStoreClient.CertificateCheck(FtpsStoreClient.fingerprint(own), authorities)

        when:
        handshake(check, own)

        then:
        check.trusted()
        !check.isChanged()

        when:
        handshake(check, other)

        then:
        thrown(CertificateException)
    }

    def "without a confirmed fingerprint the handshake goes through to show it - nothing is trusted"() {
        given:
        def check = new FtpsStoreClient.CertificateCheck(null, authorities)

        when:
        handshake(check, own)

        then:
        !check.trusted()
        !check.isChanged()
    }

    def "another certificate than the confirmed one is refused at the control connection"() {
        given:
        def check = new FtpsStoreClient.CertificateCheck(FtpsStoreClient.fingerprint(own), authorities)

        when:
        handshake(check, other)

        then:
        thrown(CertificateException)
        check.isChanged()
        !check.trusted()
    }

    private static void handshake(FtpsStoreClient.CertificateCheck check, X509Certificate certificate) {
        check.checkServerTrusted([certificate] as X509Certificate[], "ECDHE_RSA", new Socket())
    }

    /** A certificate is only its encoding here - that is what its fingerprint is taken of. */
    private X509Certificate certificate(int number) {
        Stub(X509Certificate) {
            getEncoded() >> ([number] * 32 as byte[])
        }
    }

    /** The installation's authorities: they vouch for one certificate, every other is refused. */
    static class Authorities extends X509ExtendedTrustManager {
        X509Certificate vouchedFor

        @Override
        void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            if (!chain[0].is(vouchedFor)) {
                throw new CertificateException("No authority vouches for it")
            }
        }

        @Override
        void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            checkServerTrusted(chain, authType, (Socket) null)
        }

        @Override
        void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            checkServerTrusted(chain, authType, (Socket) null)
        }

        @Override
        void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("Not used")
        }

        @Override
        void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("Not used")
        }

        @Override
        void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Not used")
        }

        @Override
        X509Certificate[] getAcceptedIssuers() {
            new X509Certificate[0]
        }
    }
}
