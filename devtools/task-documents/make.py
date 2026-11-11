"""Makes the invented sample documents of devtools/task-documents (README.md) - run again after a change:

    python3 -m venv /tmp/samples && /tmp/samples/bin/pip install reportlab python-docx Pillow
    /tmp/samples/bin/python -I devtools/task-documents/make.py devtools/task-documents

Everything is invented: Musterfirma, example.com, made-up numbers. Nothing real goes into this folder.
"""
import sys
from pathlib import Path

from docx import Document
from PIL import Image, ImageDraw, ImageFilter
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")


def pdf(path: Path, lines: list[str], header: str | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    page = canvas.Canvas(str(path), pagesize=A4)
    y = 800
    if header:
        page.setFont("Helvetica-Bold", 14)
        page.drawString(50, y, header)
        y -= 30
    page.setFont("Helvetica", 11)
    for line in lines:
        page.drawString(50, y, line)
        y -= 18
    page.save()


def image(path: Path, lines: list[str], blur: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    picture = Image.new("RGB", (900, 60 + 34 * len(lines)), "white")
    draw = ImageDraw.Draw(picture)
    for i, line in enumerate(lines):
        draw.text((30, 30 + 34 * i), line, fill="black")
    if blur:
        picture = picture.rotate(1.5, fillcolor="white").filter(ImageFilter.GaussianBlur(0.6))
    picture.save(path)


def main() -> None:
    # An invoice of the company's own: its letterhead above the customer's address, two positions at one VAT rate.
    pdf(OUT / "ausgang" / "RE-2026-0042.pdf", [
        "Musterfirma GmbH, Musterstrasse 1, 12345 Musterstadt",
        "",
        "Beispiel AG",
        "Beispielweg 7",
        "54321 Beispielstadt",
        "",
        "Rechnungsnummer RE-2026-0042        Rechnungsdatum 02.11.2026",
        "",
        "Pos  Beschreibung                 Menge   Einzelpreis   Wert EUR",
        "1    Wartung Software              12      17,50        210,00",
        "2    Einrichtung                    1     325,00        325,00",
        "",
        "Summe netto 535,00 EUR   19 % USt 101,65 EUR   Summe brutto 636,65 EUR",
        "Bitte zahlen Sie innerhalb von 14 Tagen. IBAN: DE00 0000 0000 0000 0000 00",
    ], header="Musterfirma GmbH - Rechnung")
    # An English invoice of a foreign supplier with German VAT, a date written out, a service period of a year.
    pdf(OUT / "eingang" / "2026" / "supplier-subscription.pdf", [
        "Example Software Inc., 1 Example Street, Example City, United States",
        "Bill to: Musterfirma GmbH, Musterstrasse 1, 12345 Musterstadt, Germany",
        "",
        "Invoice number EX-0007-0001     Date of issue August 26, 2026",
        "",
        "Description                               Qty   Unit price   Tax   Amount",
        "Team plan, Aug 26, 2026 - Aug 26, 2027      1     EUR 180.00   19%   EUR 180.00",
        "",
        "Total excluding tax EUR 180.00",
        "VAT - Germany (19% on EUR 180.00) EUR 34.20",
        "Total EUR 214.20",
    ], header="Invoice")
    # An invoice with two VAT rates - the task's instructions make it unclear.
    pdf(OUT / "eingang" / "2026" / "zwei-steuersaetze.pdf", [
        "Beispiel Buero GmbH, Am Beispiel 3, 54321 Beispielstadt",
        "An: Musterfirma GmbH",
        "Rechnung B-118 vom 15.10.2026",
        "Kaffee 10 kg            7 %    89,72 EUR netto",
        "Druckerpapier           19 %   42,02 EUR netto",
        "Summe brutto 146,00 EUR",
    ], header="Rechnung")
    # A screenshot of an online order confirmation: only a total, the invoice follows by mail.
    image(OUT / "eingang" / "2026" / "bestellung.png", [
        "Bestellbestaetigung - Beispiel-Shop",
        "Bestellte Artikel: Monitor 27 Zoll, Menge 1      299,00 EUR",
        "Versand: kostenlos",
        "Gesamtbetrag 299,00 EUR",
        "Ihre Rechnung senden wir Ihnen per E-Mail, sobald die Ware versandt ist.",
    ])
    # A scanned receipt: an image the image model reads.
    image(OUT / "eingang" / "2026" / "scan-quittung.png", [
        "Beispiel Tankstelle - Quittung",
        "Datum 03.11.2026",
        "Diesel 42,10 l",
        "Brutto 71,57 EUR  darin 19 % USt 11,43 EUR",
    ], blur=True)
    # A Word document - a delivery note, no invoice.
    word = Document()
    word.add_heading("Lieferschein L-2026-310", level=1)
    word.add_paragraph("Beispiel Werkzeug KG an Musterfirma GmbH, geliefert am 28.10.2026")
    word.add_paragraph("1 Akkuschrauber, 2 Bitsaetze - die Rechnung folgt.")
    (OUT / "eingang" / "2026").mkdir(parents=True, exist_ok=True)
    word.save(OUT / "eingang" / "2026" / "lieferschein.docx")
    # A broken file with a document's name.
    (OUT / "eingang" / "2026" / "kaputt.pdf").write_bytes(bytes([0, 1, 2, 255, 254]) * 200)


if __name__ == "__main__":
    main()
