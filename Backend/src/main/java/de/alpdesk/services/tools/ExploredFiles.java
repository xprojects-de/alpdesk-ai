package de.alpdesk.services.tools;

import de.alpdesk.services.common.ServiceException;
import java.util.Map;

/** What search_file and read_file_lines share: finding the file by its name, cutting long lines. */
final class ExploredFiles {

    private ExploredFiles() {
    }

    /** The file of this name (case does not matter); a name the model got wrong is a text for it, with the names. */
    static ExploredFile find(ToolContext context, String name) {
        for (ExploredFile file : context.exploredFiles()) {
            if (file.name().equalsIgnoreCase(name.strip())) {
                return file;
            }
        }
        String files = String.join(", ", context.exploredFiles().stream().map(ExploredFile::name).toList());
        throw ServiceException.badRequest("tool.fileUnknown", "there is no file \"" + name + "\" - the files to read: "
                + files,
                Map.of("name", name, "files", files));
    }

    static String cut(String line, int max) {
        return line.length() <= max ? line : line.substring(0, max) + " [... " + (line.length() - max) + " more characters]";
    }
}
