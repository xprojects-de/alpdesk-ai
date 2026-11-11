package de.alpdesk.services.task

import spock.lang.Specification

/** The task format's reference (resources/tasks/reference) names every field and step type in every language. */
class TaskReferenceSpec extends Specification {

    static final List<String> FORMAT = [
            "version", "name", "notify", "triggers", "steps", "schedule", "every", "cron",
            "id", "type", "input", "if", "next", "to", "maxRounds",
            "read_files", "store", "folder", "pattern", "days", "onlyNew", "firstRun", "include", "exclude",
            "mask", "after", "regex", "minLines", "read_documents", "subfolders",
            "extract", "fields", "knowledge", "required", "description", "values", "amount", "percent", "choice", "rows",
            "write_file", "file", "mode", "append", "replace", "separator", "decimal", "dateFormat", "skipped",
            "call_tool", "connection", "tool", "each", "with", "sent", "failed", "key", "{lastRun",
            "agent", "model", "instructions", "checks", "severity", "instruction",
            "verify", "of", "prompt", "choices", "send_mail", "subject",
            "files", "lines", "errors", "warnings", "reports", "unclear", "ok", "chars", "report"]

    def "names every field and step type of the format - #language"() {
        expect:
        FORMAT.findAll { !new TaskReference().markdown(language).contains("`" + it) } == []

        where:
        language << ["en", "de"]
    }
}
