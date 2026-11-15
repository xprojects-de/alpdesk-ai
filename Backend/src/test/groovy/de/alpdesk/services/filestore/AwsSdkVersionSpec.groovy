package de.alpdesk.services.filestore

import spock.lang.Specification

/**
 * The AWS SDK's modules come in over two ways - S3 through the Micronaut platform, Bedrock through langchain4j - and
 * must be one version: a mix starts and works, and fails only on the SDK's error path (IncompatibleClassChangeError
 * instead of an S3 store's refusal). The specs that would see it need real servers and do not run in CI; this one reads
 * the classpath. build.gradle pins them with the SDK's BOM - raise it there when this fails.
 */
class AwsSdkVersionSpec extends Specification {

    def "every module of the AWS SDK on the classpath has the same version"() {
        when: "the jars as Gradle's cache holds them: .../software.amazon.awssdk/<module>/<version>/<hash>/<jar>"
        Map<String, String> versions = [:]
        System.getProperty("java.class.path").split(File.pathSeparator).each { entry ->
            def jar = entry.replace(File.separator, "/") =~ /\/software\.amazon\.awssdk\/([^\/]+)\/([^\/]+)\//
            if (jar.find()) {
                versions[jar.group(1)] = jar.group(2)
            }
        }

        then: "the SDK is there - else this spec no longer reads the classpath as it is laid out"
        versions.keySet().containsAll(["s3", "bedrockruntime", "sdk-core"])

        and:
        versions.groupBy { it.value }.collectEntries { [it.key, it.value.keySet().sort()] }.size() == 1
    }
}
