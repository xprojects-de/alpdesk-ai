package de.alpdesk.services.task

import de.alpdesk.entities.task.ReadPosition
import spock.lang.Specification

/**
 * The read positions a run saves (TaskRunner.earliestPerFile): two read_files steps reading the same file share its
 * position, and the one that read less wins - the file may have grown between the two steps, and a position past what
 * a step read would lose those lines for it.
 */
class TaskReadPositionsSpec extends Specification {

    def "two steps on the same file: the position that read less is kept - #situation"() {
        given:
        def positions = [position(1, "errors.log", first), position(1, "errors.log", second)]

        expect:
        TaskRunner.earliestPerFile(positions) == [position(1, "errors.log", kept)]

        where:
        situation                          | first | second || kept
        "the second step read more"        | 1000  | 1200   || 1000
        "the first step read more"         | 1200  | 1000   || 1000
        "both read the same"               | 1000  | 1000   || 1000
    }

    def "different files and different stores keep their own positions, in their order"() {
        given:
        def positions = [position(1, "errors.log", 10), position(1, "info.log", 20), position(2, "errors.log", 30)]

        expect:
        TaskRunner.earliestPerFile(positions) == positions
    }

    def "no positions: nothing to save"() {
        expect:
        TaskRunner.earliestPerFile([]) == []
    }

    private static ReadPosition position(long storeId, String path, long byteOffset) {
        return new ReadPosition(storeId, path, byteOffset, byteOffset.intdiv(100), null)
    }
}
