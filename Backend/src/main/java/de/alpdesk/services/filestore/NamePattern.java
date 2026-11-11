package de.alpdesk.services.filestore;

/** A file name pattern of a store's reader - a task's read_files step, a knowledge source: * any text, ? one character. */
public final class NamePattern {

    private NamePattern() {
    }

    /**
     * Whether the name matches the pattern, * any text and ? one character, everything else literally. Not as a
     * regular expression: one of many .* backtracks for minutes on a name it does not match and holds a task thread;
     * going back only to the last * takes at most name times pattern steps.
     */
    public static boolean matches(String pattern, String name) {
        int at = 0;
        int star = -1;
        int resume = 0;
        int index = 0;
        while (index < name.length()) {
            if (at < pattern.length() && pattern.charAt(at) == '*') {
                star = at++;
                resume = index;
            } else if (at < pattern.length() && (pattern.charAt(at) == '?' || pattern.charAt(at) == name.charAt(index))) {
                at++;
                index++;
            } else if (star >= 0) {
                at = star + 1;
                index = ++resume;
            } else {
                return false;
            }
        }
        while (at < pattern.length() && pattern.charAt(at) == '*') {
            at++;
        }
        return at == pattern.length();
    }
}
