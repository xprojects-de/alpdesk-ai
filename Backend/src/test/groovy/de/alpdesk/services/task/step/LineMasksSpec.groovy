package de.alpdesk.services.task.step

import de.alpdesk.services.common.ServiceException
import de.alpdesk.services.task.TaskDefinition.Mask
import spock.lang.Specification

/** A read_files step's masks (LineMasks): the value after a text, every match of a regular expression, a time limit. */
class LineMasksSpec extends Specification {

    def "after masks the value up to the next separator - #situation"() {
        expect:
        new LineMasks([new Mask(after, null)]).apply(line) == masked

        where:
        situation                     | after                    | line                                               || masked
        "in quotes"                   | 'ApiPassword="'      | '<RequestorID ApiPassword="secret1">'          || '<RequestorID ApiPassword="***">'
        "in an HTML-escaped log"      | 'ApiPassword=&quot;' | 'ApiPassword=&quot;secret1&quot;&gt;'          || 'ApiPassword=&quot;***&quot;&gt;'
        "in a URL, up to its end"     | 'companyToken='          | 'GET "https://x.example.com/open?companyToken=abc".' || 'GET "https://x.example.com/open?companyToken=***".'
        "at the line's end"           | 'token='                 | 'token=abc'                                        || 'token=***'
        "every time it comes"         | 'pw='                    | 'pw=a, pw=b'                                       || 'pw=***, pw=***'
        "after a colon and a space"   | 'password:'              | 'login failed password: s3cret user=x'             || 'login failed password: *** user=x'
        "after a colon and a tab"     | 'password:'              | 'password:\ts3cret'                                || 'password:\t***'
        "after a colon, no space"     | 'password:'              | 'password:s3cret'                                  || 'password:***'
        "an empty value stays empty"  | 'pw="'                   | 'pw=""'                                            || 'pw=""'
        "only spaces: nothing to mask"| 'password:'              | 'password:   '                                     || 'password:   '
        "not there: the line as it is"| 'pw='                    | 'nothing to hide'                                  || 'nothing to hide'
    }

    def "a regular expression masks every match, after the plain masks"() {
        given:
        def masks = new LineMasks([new Mask(null, 'TerminalID="[A-Z]{3}\\d+"'), new Mask('pw=', null)])

        expect:
        masks.apply('TerminalID="GER0001" pw=x TerminalID="AUT0002"') == '*** pw=*** ***'
    }

    def "a regular expression taking too long on a line fails the step - with its code, not after minutes"() {
        given:
        def masks = new LineMasks([new Mask(null, '(a+)+\\1$')])

        when:
        long started = System.currentTimeMillis()
        masks.apply("a" * 30 + "b")

        then:
        def e = thrown(ServiceException)
        e.code() == "task.maskTooSlow"
        System.currentTimeMillis() - started < 5_000
    }
}
