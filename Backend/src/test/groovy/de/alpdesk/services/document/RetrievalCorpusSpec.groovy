package de.alpdesk.services.document

import de.alpdesk.services.chat.FileRenderer
import de.alpdesk.services.chat.OutputFormat
import io.micronaut.serde.ObjectMapper
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Shared
import spock.lang.Specification

/**
 * The test company of the retrieval measurement (src/test/resources/retrieval) is sound without any provider: every
 * document renders in its format and its text is extracted, and every expected answer is in the text of its file -
 * so a miss in RetrievalEvaluationSpec is the search's, not the test data's.
 */
@MicronautTest
class RetrievalCorpusSpec extends Specification {

    @Inject
    FileRenderer renderer

    @Inject
    DocumentTextExtractor extractor

    @Shared
    Map<String, String> texts

    def setup() {
        texts = texts ?: json("corpus.json").collectEntries { document ->
            String source = getClass().getResource("/retrieval/corpus/${document.source}").getText("UTF-8")
            byte[] content = renderer.render(OutputFormat.valueOf(document.format as String), source).content()
            [(document.file): extractor.extract(document.file as String, content).text()]
        }
    }

    def "every document of the test company renders and has text"() {
        expect:
        texts.size() == 16
        texts.every { file, text -> text.length() > 300 }
    }

    def "every expected answer is in the extracted text of its file: #id"() {
        expect:
        expected.every { texts.containsKey(it.file) }
        expected.any { normalize(texts[it.file]).contains(normalize(it.contains as String)) }

        where:
        question << json("questions.json").findAll { it.type != "NONE" }
        id = question.id
        expected = question.expected as List<Map>
    }

    private static String normalize(String text) {
        text.replace(' ', ' ').replaceAll(/\s+/, " ").toLowerCase(Locale.ROOT)
    }

    private static Object json(String name) {
        ObjectMapper.getDefault().readValue(RetrievalCorpusSpec.getResource("/retrieval/${name}").getText("UTF-8"), Object)
    }
}
