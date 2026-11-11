package de.alpdesk.entities.task;

import java.util.List;

/** The lines a read_files step kept of one file, each with its number in the file - tenant database. */
public record RunFile(Long id, String name, List<Line> lines) {

    public record Line(long number, String text) {
    }
}
