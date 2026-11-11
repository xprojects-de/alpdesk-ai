# Sample documents for tasks

Invented documents and two tasks to try roadmap point 50 by hand: documents read by a task (`read_documents`), their
fields taken out (`extract`), written into a CSV per year (`write_file`). Everything is invented - Musterfirma,
example.com, made-up numbers. Not part of the product: outside `Backend/` and `Frontend/`, never in a release.

| File | What it tries |
|------|---------------|
| `ausgang/RE-2026-0042.pdf` | an invoice of the company's own: its letterhead above the customer - the partner must be the customer |
| `eingang/2026/supplier-subscription.pdf` | an English invoice of a foreign supplier, German VAT, a date written out, a service period of a year |
| `eingang/2026/zwei-steuersaetze.pdf` | 7 % and 19 % on one invoice - the instructions make it unclear |
| `eingang/2026/bestellung.png` | a screenshot of an order confirmation: only a total, the invoice follows - `kind: other` or unclear, no amounts computed |
| `eingang/2026/scan-quittung.png` | a scanned receipt - read by the tenant's image model |
| `eingang/2026/lieferschein.docx` | a Word document, a delivery note - no invoice |
| `eingang/2026/kaputt.pdf` | a broken file - kept with its reason, the run goes on |
| `tasks/invoices-received.yaml`, `tasks/invoices-sent.yaml` | the two tasks |

`make.py` makes the documents again (its head says how; reportlab, python-docx and Pillow in a venv of its own).

## Try it

On a backend of your own - never the developer's dev database (CLAUDE.md "The developer's dev database"):

1. A tenant that may have tasks, a chat model it may use with a key, an image model (`readsScans`) for the scans, the
   mail settings (a local MailHog).
2. A file store "Belege" (SFTP, S3, ...) whose folder holds `ausgang/`, `eingang/2026/` and `export/2026/` - copy this
   folder's documents there. Check it, and turn on "Aufgaben dürfen hier schreiben" in its dialog.
3. Save the two tasks (the model's name as the catalog shows it), start them by hand.
4. Look at the run: the documents read, the rows, the CSV in `export/2026/`, the mail for the unclear rows. Start
   again: nothing new is read, nothing written twice.

A mailbox instead of a folder: a store of the type IMAP, every message's attachments read with `subfolders: true` (the
messages are its folders) - send the documents to the mailbox as attachments.
