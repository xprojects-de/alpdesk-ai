package de.alpdesk.services.chat;

/** The formats the chat's model can create a file in (tool create_file), with what it writes for each. */
public enum OutputFormat {
    TXT("text/plain; charset=utf-8", "txt"),
    MD("text/markdown; charset=utf-8", "md"),
    CSV("text/csv; charset=utf-8", "csv"),
    HTML("text/html; charset=utf-8", "html"),
    JSON("application/json", "json"),
    /** Written by the model as Markdown. */
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    /** Written by the model as CSV. */
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    /** Written by the model as Markdown. */
    PDF("application/pdf", "pdf");

    final String contentType;
    final String extension;

    OutputFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }
}
