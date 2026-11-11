#!/usr/bin/env python3
"""Makes the browser walk's files (walk.md) into walk/files - run again after a change:

    python3 -I devtools/general-chat-test/walk/make.py

Standard library, plus macOS `say` and `ffmpeg` for the spoken note and the clip (left as they are when one of the two
is missing). Everything is invented: Musterfirma, example.com, made-up numbers and markers. Each file carries a marker
the walk looks for in the answer - a file read wrongly or not at all shows at once.
"""
import io
import pathlib
import shutil
import struct
import subprocess
import zipfile
import zlib

HERE = pathlib.Path(__file__).parent
OUT = HERE / "files"
SAMPLES = HERE.parent.parent / "task-documents"
UPLOADS = HERE.parent / "uploads"


def text(name, content):
    (OUT / name).write_text(content, encoding="utf-8")


def png(width, height, colour):
    """A plain PNG of one colour - the logo."""

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    row = b"\x00" + bytes(colour) * width
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * height)) + chunk(b"IEND", b""))


def office(parts):
    """A minimal OOXML package: {path: xml}."""
    package = io.BytesIO()
    with zipfile.ZipFile(package, "w", zipfile.ZIP_DEFLATED) as archive:
        for path, xml in parts.items():
            archive.writestr(path, xml)
    return package.getvalue()


def docx(paragraphs):
    body = "".join(f"<w:p><w:r><w:t>{line}</w:t></w:r></w:p>" for line in paragraphs)
    return office({
        "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/'
                               'package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.'
                               'openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="'
                               'application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.'
                               'openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
        "_rels/.rels": '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/'
                       'package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/'
                       'officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
        "word/document.xml": '<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.'
                             f'org/wordprocessingml/2006/main"><w:body>{body}</w:body></w:document>',
    })


def xlsx(rows):
    def cell(value):
        if isinstance(value, (int, float)):
            return f"<c><v>{value}</v></c>"
        return f'<c t="inlineStr"><is><t>{value}</t></is></c>'

    data = "".join("<row>" + "".join(cell(value) for value in row) + "</row>" for row in rows)
    main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    relations = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    return office({
        "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/'
                               'package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.'
                               'openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="'
                               'application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.'
                               'openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/'
                               'worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.'
                               'spreadsheetml.worksheet+xml"/></Types>',
        "_rels/.rels": '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/'
                       f'package/2006/relationships"><Relationship Id="rId1" Type="{relations}/officeDocument" '
                       'Target="xl/workbook.xml"/></Relationships>',
        "xl/workbook.xml": f'<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="{main}" xmlns:r="{relations}">'
                           '<sheets><sheet name="Ersatzteile" sheetId="1" r:id="rId1"/></sheets></workbook>',
        "xl/_rels/workbook.xml.rels": '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.'
                                      'openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" '
                                      f'Type="{relations}/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
        "xl/worksheets/sheet1.xml": f'<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="{main}"><sheetData>'
                                    f'{data}</sheetData></worksheet>',
    })


def archive(name, files):
    with zipfile.ZipFile(OUT / name, "w", zipfile.ZIP_DEFLATED) as package:
        for path, content in files.items():
            package.writestr(path, content)


def copy(source, name=None):
    shutil.copyfile(source, OUT / (name or source.name))


def media():
    """The spoken note and the clip - only where `say` and `ffmpeg` are (macOS with ffmpeg installed)."""
    if not shutil.which("say") or not shutil.which("ffmpeg"):
        print("say or ffmpeg missing - sprachnotiz.m4a and clip.mp4 left as they are")
        return
    spoken = OUT / "sprachnotiz.aiff"
    subprocess.run(["say", "-v", "Anna", "-o", str(spoken),
                    "Notiz der Instandhaltung. Die Hebebühne Nummer sieben wird am zwölften März geprüft."], check=True)
    quiet = ["ffmpeg", "-y", "-loglevel", "error"]
    subprocess.run(quiet + ["-i", str(spoken), "-c:a", "aac", "-b:a", "48k", str(OUT / "sprachnotiz.m4a")], check=True)
    # Three seconds of moving test picture with a tone: enough for the upload, the stills and the player.
    subprocess.run(quiet + ["-f", "lavfi", "-i", "testsrc=duration=3:size=320x240:rate=10", "-f", "lavfi", "-i",
                            "sine=frequency=440:duration=3", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                            "-shortest", str(OUT / "clip.mp4")], check=True)
    spoken.unlink()


def main():
    OUT.mkdir(exist_ok=True)

    # The company knowledge, one file per type the walk uploads.
    text("betriebsanweisung-hebebuehne.md",
         "# Betriebsanweisung Hebebühne H7\n\nDie Hebebühne H7 der Musterfirma trägt höchstens 3200 kg. Vor jedem "
         "Einsatz wird die Verriegelung geprüft. Prüfmarke RUNDGANG-4711.\n")
    text("betriebsanweisung-hebebuehne-v2.md",
         "# Betriebsanweisung Hebebühne H7\n\nDie Hebebühne H7 der Musterfirma trägt höchstens 3500 kg (seit dem Umbau "
         "2026). Vor jedem Einsatz wird die Verriegelung geprüft. Prüfmarke RUNDGANG-4712.\n")
    (OUT / "urlaubsregelung.docx").write_bytes(docx([
        "Urlaubsregelung der Musterfirma",
        "Jede Vollzeitkraft hat 31 Urlaubstage im Jahr. Anträge gehen bis zum 15. des Vormonats an die Teamleitung.",
        "Prüfmarke RUNDGANG-DOCX-31."]))
    (OUT / "ersatzteile.xlsx").write_bytes(xlsx([
        ["Teilenummer", "Bezeichnung", "Bestand", "Lagerplatz"],
        ["ET-1001", "Dichtung H7", 14, "R3-07"],
        ["ET-1002", "Hydraulikschlauch H7", 3, "R3-09"],
        ["ET-1003", "Sicherungsbolzen RUNDGANG-XLSX", 52, "R1-02"]]))
    text("lieferzeiten.csv", "Lieferant;Artikel;Tage\nMuster Stahl GmbH;Blech 2 mm;9\nBeispiel Hydraulik AG;"
                             "Schlauch H7;21\nProbe Normteile KG;Bolzen;4\n")
    text("geheim-gehaltsband.md",
         "# Gehaltsbänder (nur Leitung)\n\nDas Gehaltsband der Schichtleitung liegt in Stufe L4. Kennwort "
         "GEHEIM-9090.\n")
    copy(SAMPLES / "eingang" / "2026" / "scan-quittung.png")
    copy(UPLOADS / "pruefprotokoll-bt220.pdf")

    # A ZIP with folders for the company knowledge.
    archive("wissen-archiv.zip", {
        "halle-1/brandschutz.md": "# Brandschutz Halle 1\n\nDer Sammelplatz der Halle 1 ist Tor C. Prüfmarke ZIP-HALLE1.\n",
        "halle-2/laerm.md": "# Lärmschutz Halle 2\n\nIn Halle 2 gilt ab 85 dB Gehörschutzpflicht. Prüfmarke ZIP-HALLE2.\n",
        "halle-2/pruefung/uvv.txt": "Die UVV-Prüfung der Krane in Halle 2 ist jedes Jahr im November. Prüfmarke ZIP-UVV.\n",
        # The app recognises a type by its content: a program's header, not just its name, makes it refused.
        "nicht-lesbar.exe": b"MZ\x90\x00\x03\x00\x00\x00\x04\x00\x00\x00\xff\xff" + bytes(50) + b"PE\x00\x00" + bytes(64),
    })

    # Files sent with a question in the chat.
    text("stoermeldung.txt",
         "Störmeldung vom 3. November 2026\nMaschine: Hebebühne H7\nLast beim Ausfall: 3400 kg\nMelder: Schicht B\n")
    text("messwerte.csv", "Tag;Temperatur\nMo;21.5\nDi;22.0\nMi;23.5\nDo;19.0\nFr;24.0\n")

    # The handbook: a small static website - and its next version.
    page = ("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><title>{title}</title>"
            "<link rel=\"stylesheet\" href=\"style.css\"></head><body><h1>{title}</h1><p>{body}</p>{link}</body></html>")
    archive("handbuch.zip", {
        "index.html": page.format(title="Handbuch Musterfirma", body="Willkommen im Handbuch. Marke HANDBUCH-EINS.",
                                  link='<a href="kapitel/sicherheit.html">Sicherheit</a>'),
        "kapitel/sicherheit.html": page.format(title="Sicherheit", body="Helm und Schuhe in jeder Halle. Marke "
                                               "HANDBUCH-KAPITEL.", link='<a href="../index.html">Start</a>'),
        "style.css": "body { font-family: sans-serif; margin: 2rem; } h1 { color: #1f6f43; }",
    })
    archive("handbuch-v2.zip", {
        "index.html": page.format(title="Handbuch Musterfirma", body="Willkommen im neuen Handbuch. Marke "
                                  "HANDBUCH-ZWEI.", link=""),
        "style.css": "body { font-family: sans-serif; margin: 2rem; }",
    })

    # Branding.
    (OUT / "logo.png").write_bytes(png(96, 96, (31, 111, 67)))

    media()
    for path in sorted(OUT.iterdir()):
        print(f"{path.stat().st_size:>8}  {path.name}")


if __name__ == "__main__":
    main()
