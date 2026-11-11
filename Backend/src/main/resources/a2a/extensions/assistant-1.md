# Extension urn:alpdesk-ai:a2a:assistant:1

The conventions of a company's AI assistant on this installation, on top of A2A 1.0. Never required: a client that
ignores them still gets correct A2A answers.

## Requests

Put the entries in `message.metadata["urn:alpdesk-ai:a2a:assistant:1"]` and name the URI in the header
`A2A-Extensions` (and in `message.extensions`):

- `template` - the name of one of the assistant's templates (listed in the extended card as skills `template:<id>`):
  the question is answered with that template's instructions and settings.
- `task` - the name of a scheduled task assigned to the key (listed as skills `task:<id>`): instead of answering, the
  agent starts the task. The answer is a Task that follows the run: `TASK_STATE_SUBMITTED` while it waits,
  `TASK_STATE_WORKING` while it runs, then `TASK_STATE_COMPLETED`, `TASK_STATE_FAILED` or `TASK_STATE_CANCELED`. The
  text parts of such a message are ignored.

## Answers

- A blocking `SendMessage` answers one Message: the answer with "Sources: ..." appended, the files as `raw` parts.
- `SendStreamingMessage` (or `returnImmediately`) answers a Task: the answer appended to the artifact `answer`, kept
  for the key for a day (by default) - `GetTask`, `SubscribeToTask`, `ListTasks`, `CancelTask` reach it.
- A task's run ends with two artifacts: `report` (a text: the answers handed on, the rows taken out of documents, the
  findings with their evidence) and `findings` (a data part with `findings`, `rows` and `handedOn`).

A task belongs to the key that started it; another key does not find it.
