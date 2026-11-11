package de.alpdesk.services.ai;

import io.micronaut.core.annotation.Nullable;

/**
 * Tells whether a text streamed in pieces ends in a loop: the same block of lines, word for word, {@link #MIN_COPIES}
 * times in a row (a tandem repeat). A Qwen model on its own server repeated a cycle of two paragraphs, 575 characters,
 * in its reasoning for minutes - its answer already planned, never written (October 2026). The block may hold several
 * different paragraphs: what is found is the period after which the text repeats as a whole.
 * <p>
 * The text is cut into units at line breaks (and every {@link #MAX_UNIT} characters, so a loop without them is found
 * too, later); each unit is kept as a 64-bit hash with its length, the last {@link #CAPACITY} of them - no text. Word
 * for word on purpose: the replanning of a model thinking normally varies from one attempt to the next and is never a
 * loop here.
 * <p>
 * Code does not count: lines inside a fence (```) and lines starting with '<' (XML, HTML) or '|' (a table's row) are
 * left out. A model drafting the same configuration for five devices repeats a block word for word on purpose; the
 * loop seen is prose. One per stream and channel, used by one thread.
 */
final class CycleDetector {

    /** Copies in a row before it is a loop - a model that comes out of it again has had its chances. */
    static final int MIN_COPIES = 5;
    /**
     * The block's least size: a few short lines in a row ("Okay.") are no loop to stop for; repeated on and on they
     * become one, once their copies make blocks of this size.
     */
    static final int MIN_BLOCK_CHARS = 200;
    static final int MAX_UNIT = 400;
    static final int CAPACITY = 512;

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /** A loop found: the block's units and characters, and how many copies of it end the text. */
    record Cycle(int units, int chars, int copies) {
    }

    private final long[] hashes = new long[CAPACITY];
    private final int[] lengths = new int[CAPACITY];
    /** Units seen so far; the newest is at (seen - 1) % CAPACITY. */
    private int seen;
    private long hash = FNV_OFFSET;
    private int unitLength;
    /** The line's first characters, to tell code from prose - kept over a cut at MAX_UNIT, until the line ends. */
    private final StringBuilder lineStart = new StringBuilder();
    private boolean inFence;

    /** Takes the next piece of the text: the loop it ends in now, or null. */
    @Nullable
    Cycle add(String piece) {
        Cycle found = null;
        for (int i = 0; i < piece.length() && found == null; i++) {
            char c = piece.charAt(i);
            if (c == '\n') {
                found = endUnit(unitLength + 1, true);
                continue;
            }
            if (lineStart.length() < 3 && (!lineStart.isEmpty() || !Character.isWhitespace(c))) {
                lineStart.append(c);
            }
            hash = (hash ^ c) * FNV_PRIME;
            unitLength++;
            if (unitLength == MAX_UNIT) {
                found = endUnit(unitLength, false);
            }
        }
        return found;
    }

    /** Ends the unit, at the line's end or at a cut; code is dropped, prose kept and looked at for a loop. */
    private @Nullable Cycle endUnit(int chars, boolean lineEnds) {
        boolean code = isCode();
        long unitHash = hash;
        hash = FNV_OFFSET;
        unitLength = 0;
        if (lineEnds) {
            if (lineStart.indexOf("```") == 0) {
                inFence = !inFence;
            }
            lineStart.setLength(0);
        }
        if (code) {
            return null;
        }
        hashes[seen % CAPACITY] = unitHash;
        lengths[seen % CAPACITY] = chars;
        seen++;
        return cycleAtEnd();
    }

    private boolean isCode() {
        if (inFence || lineStart.indexOf("```") == 0) {
            return true;
        }
        return !lineStart.isEmpty() && (lineStart.charAt(0) == '<' || lineStart.charAt(0) == '|');
    }

    /** The shortest period whose block is large enough and ends the units MIN_COPIES times; null when there is none. */
    private @Nullable Cycle cycleAtEnd() {
        int known = Math.min(seen, CAPACITY);
        for (int period = 1; period * MIN_COPIES <= known; period++) {
            if (repeats(period) && blockChars(period) >= MIN_BLOCK_CHARS) {
                return new Cycle(period, blockChars(period), MIN_COPIES);
            }
        }
        return null;
    }

    /** Whether each of the last (MIN_COPIES - 1) * period units equals the one a period before it. */
    private boolean repeats(int period) {
        for (int back = 0; back < (MIN_COPIES - 1) * period; back++) {
            if (unit(back) != unit(back + period)) {
                return false;
            }
        }
        return true;
    }

    private int blockChars(int period) {
        int chars = 0;
        for (int back = 0; back < period; back++) {
            chars += lengths[index(back)];
        }
        return chars;
    }

    /** The hash of the unit this many before the newest. */
    private long unit(int back) {
        return hashes[index(back)];
    }

    private int index(int back) {
        return (seen - 1 - back) % CAPACITY;
    }
}
