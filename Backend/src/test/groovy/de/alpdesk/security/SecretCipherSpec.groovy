package de.alpdesk.security

import spock.lang.Specification

/**
 * The stored keys (AI providers, connected software, own model servers, sandboxes) are AES-256-GCM with a fresh IV per
 * value: the same key stored twice reads differently, and a value of another APP_SECRET_KEY or one changed in the
 * database is refused instead of read as garbage.
 */
class SecretCipherSpec extends Specification {

    static final String KEY = Base64.encoder.encodeToString(("k" * 32).bytes)
    static final String OTHER_KEY = Base64.encoder.encodeToString(("o" * 32).bytes)

    def cipher = new SecretCipher(KEY)

    def "a secret is read back as it was stored"() {
        expect:
        cipher.decrypt(cipher.encrypt("sk-ant-musterfirma-1")) == "sk-ant-musterfirma-1"
    }

    def "the same secret stored twice reads differently - every value has an IV of its own"() {
        when:
        def first = cipher.encrypt("sk-ant-musterfirma-1")
        def second = cipher.encrypt("sk-ant-musterfirma-1")

        then:
        first.startsWith(SecretCipher.PREFIX)
        first != second
        iv(first) != iv(second)
    }

    def "a value of another APP_SECRET_KEY is refused"() {
        given:
        def stored = new SecretCipher(OTHER_KEY).encrypt("sk-ant-musterfirma-1")

        when:
        cipher.decrypt(stored)

        then:
        def e = thrown(IllegalStateException)
        !e.message.contains("musterfirma")
    }

    def "a value changed in the database is refused: #reason"() {
        when:
        cipher.decrypt(changed(cipher.encrypt("sk-ant-musterfirma-1")))

        then:
        thrown(IllegalStateException)

        where:
        reason                   | changed
        "one byte of the secret" | { String stored -> flip(stored, 20) }
        "one byte of the IV"     | { String stored -> flip(stored, 0) }
        "not encrypted at all"   | { String stored -> "sk-ant-musterfirma-1" }
        "not base64"             | { String stored -> SecretCipher.PREFIX + "%%%" }
    }

    def "APP_SECRET_KEY must be 32 bytes of base64: #reason"() {
        when:
        new SecretCipher(key)

        then:
        thrown(IllegalStateException)

        where:
        reason        | key
        "16 bytes"    | Base64.encoder.encodeToString(("k" * 16).bytes)
        "33 bytes"    | Base64.encoder.encodeToString(("k" * 33).bytes)
        "not base64"  | "kein base64 %%%"
    }

    private static byte[] iv(String stored) {
        Base64.decoder.decode(stored.substring(SecretCipher.PREFIX.length()))[0..<12] as byte[]
    }

    private static String flip(String stored, int index) {
        byte[] data = Base64.decoder.decode(stored.substring(SecretCipher.PREFIX.length()))
        data[index] = (byte) (data[index] ^ 0x01)
        SecretCipher.PREFIX + Base64.encoder.encodeToString(data)
    }
}
