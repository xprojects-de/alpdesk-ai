package de.alpdesk.security

import spock.lang.Specification

/** The packaged jar must not start with the secrets committed in application.properties. */
class ProductionSecretsCheckSpec extends Specification {

    static final Map<String, String> ALL = [APP_USERNAME: "admin", APP_PASSWORD: "s3cr3t-password-long",
                                            APP_SECRET_KEY: "key",
                                            JWT_GENERATOR_SIGNATURE_SECRET: "j" * 40]

    def "outside dev and test every secret must be set explicitly"() {
        when:
        ProductionSecretsCheck.forTest([] as Set, env, password)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains(missing)

        where:
        env                                        | password    | missing
        ALL - [APP_USERNAME: "admin"]              | "x"         | "APP_USERNAME"
        ALL - [APP_SECRET_KEY: "key"]              | "x"         | "APP_SECRET_KEY"
        ALL + [JWT_GENERATOR_SIGNATURE_SECRET: ""] | "x"         | "JWT_GENERATOR_SIGNATURE_SECRET"
        ALL + [APP_PASSWORD: "CHANGE_ME"]          | "CHANGE_ME" | "APP_PASSWORD"
        [:]                                        | "alpdesk"   | "APP_USERNAME, APP_PASSWORD, APP_SECRET_KEY, JWT_GENERATOR_SIGNATURE_SECRET"
        // values from the repository are no secrets
        ALL + [APP_PASSWORD: "alpdesk"]            | "alpdesk"   | "APP_PASSWORD"
        ALL + [JWT_GENERATOR_SIGNATURE_SECRET: "local-test-secret-change-me-local-test-secret-change-me"] | "x" | "JWT_GENERATOR_SIGNATURE_SECRET"
        // too short to withstand guessing
        ALL + [APP_PASSWORD: "kurz"]               | "kurz"      | "APP_PASSWORD"
        ALL + [JWT_GENERATOR_SIGNATURE_SECRET: "short"] | "x"    | "JWT_GENERATOR_SIGNATURE_SECRET"
        // the database password left as in .env.example or the local test stack
        ALL + [DATASOURCES_DEFAULT_PASSWORD: "CHANGE_ME"]         | "x" | "DATASOURCES_DEFAULT_PASSWORD"
        ALL + [DATASOURCES_DEFAULT_PASSWORD: "local-db-password"] | "x" | "DATASOURCES_DEFAULT_PASSWORD"
    }

    def "with every secret set, or in dev/test, it starts"() {
        when:
        ProductionSecretsCheck.forTest(environments as Set, env, "x")

        then:
        noExceptionThrown()

        where:
        environments | env
        []           | ALL
        []           | ALL + [DATASOURCES_DEFAULT_PASSWORD: "x" * 40]
        ["dev"]      | [:]
        ["dev"]      | [DATASOURCES_DEFAULT_PASSWORD: "local-db-password"]
        ["test"]     | [:]
    }

    def "every secret committed to the repository is refused: #name = #value"() {
        when:
        ProductionSecretsCheck.forTest([] as Set, ALL + [(name): value], "x")

        then:
        def e = thrown(IllegalStateException)
        e.message.contains(name)

        where:
        [name, value] << committedSecrets()
    }

    /**
     * The defaults in application.properties (`${APP_PASSWORD\:...}`) and the values of the local test stack
     * (docker-compose.yml): a default changed there must be refused as well, not only the ones listed by hand.
     */
    static List<List<String>> committedSecrets() {
        List<String> names = ["APP_PASSWORD", "APP_SECRET_KEY", "JWT_GENERATOR_SIGNATURE_SECRET", "DATASOURCES_DEFAULT_PASSWORD"]
        List<List<String>> found = []
        new File("src/main/resources/application.properties").eachLine { String line ->
            def m = line =~ /\$\{(\w+)\\:([^}]+)}/
            if (m.find() && m.group(1) in names) {
                found << [m.group(1), m.group(2)]
            }
        }
        new File("podman/docker-compose.yml").eachLine { String line ->
            def m = line =~ /^\s+(\w+):\s*"([^"]+)"/
            if (m.find() && m.group(1) in names) {
                found << [m.group(1), m.group(2)]
            }
        }
        assert found*.get(0).toSet() == names.toSet()
        found
    }
}
