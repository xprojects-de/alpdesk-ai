package de.alpdesk.services.chat

import de.alpdesk.services.common.ServiceException
import spock.lang.Specification

/**
 * An upload to the chat is checked by the sizes its parts declare, before any part is read into memory: the server
 * takes requests up to a ZIP archive's size (512 MB) and keeps larger parts on disk. A part above 50 MB may only be a
 * video (checked by its first bytes later - ChatVideoSpec) up to the video size, and does not count in the total.
 */
class ChatUploadLimitsSpec extends Specification {

    static final long MB = 1024 * 1024
    static final long VIDEO_MAX = 500 * MB

    def "an upload is refused by its parts' sizes: #reason"() {
        when:
        ChatFileService.requireWithinLimits(sizes, videoMax)

        then:
        def e = thrown(ServiceException)
        e.message == message

        where:
        reason                                        | sizes                        | videoMax  || message
        "more than 10 files"                          | [1L] * 11                    | VIDEO_MAX || "At most 10 files at once"
        "one file larger than the video size"         | [501 * MB]                   | VIDEO_MAX || "A file may have at most 50 MB, a video 500 MB"
        "larger than 50 MB with a smaller video size" | [51 * MB]                    | 20 * MB   || "A file may have at most 50 MB, a video 20 MB"
        "more than 50 MB together"                    | [26 * MB, 26 * MB]           | VIDEO_MAX || "The files of one upload may have at most 50 MB together"
        "a large one beside 50 MB of small ones"      | [400 * MB, 26 * MB, 26 * MB] | VIDEO_MAX || "The files of one upload may have at most 50 MB together"
    }

    def "an upload within the limits passes: #sizes"() {
        when:
        ChatFileService.requireWithinLimits(sizes, videoMax)

        then:
        noExceptionThrown()

        where:
        sizes << [[1L] * 10, [50 * MB], [25 * MB, 25 * MB], [500 * MB], [400 * MB, 49 * MB], [300 * MB, 300 * MB]]
        videoMax = VIDEO_MAX
    }
}
