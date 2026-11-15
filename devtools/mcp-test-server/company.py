"""
A company's own MCP server as it could look in production - the "Entwicklungsportal" of the invented Musterfirma: ten
tools of a development department (guidelines, repositories, source code, build logs, review reports, one tool that
changes something), resources, resource templates and prompts. Where server.py has one tool per way of answering,
this one is a whole server the way a company writes it: German descriptions over several paragraphs, a description
for every parameter, titles, output schemas, long instructions. For checking what of all that reaches an MCP client
through Zirmo's proxy - company_check.py compares both ends. Invented data only. See README.md.

Streamable HTTP on /mcp. Optional bearer key: MCP_TEST_KEY.
"""

import base64
import json
import os
import sys
from typing import Annotated

import uvicorn
from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.prompts import base
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import CallToolResult, ImageContent, ResourceLink, TextContent, ToolAnnotations
from pydantic import BaseModel, Field

# Started with python -I, which leaves the script's folder off the path - server.py lies next to this file.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from server import BearerKey, blob, log, pdf, png, project_zip  # noqa: E402

PORT = int(os.environ.get("MCP_TEST_PORT", "8000"))
KEY = os.environ.get("MCP_TEST_KEY", "")

READS = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False)

INSTRUCTIONS = """Das Entwicklungsportal der Musterfirma GmbH: Richtlinien, Repositories, Quellcode, Builds und Reviews \
der Softwareentwicklung.

Vorgehen:
1. Fragen zu Regeln ("Wie benennen wir Pakete?", "Was gehört in ein Review?") zuerst mit search_guidelines suchen und \
die Richtlinie mit get_guideline ganz lesen - nie aus dem Gedächtnis antworten. Die Nummer der Richtlinie (z. B. RL-012) \
immer nennen.
2. Fragen zu Code: mit list_repositories das Repository finden, mit search_code die Stelle, mit get_file die Datei. \
Dateipfade sind relativ zum Repository und beginnen ohne Schrägstrich.
3. Ein fehlgeschlagener Build: get_build_log mit der Build-Nummer; die Ursache steht meist in den letzten 200 Zeilen.
4. Ein Review-Bericht für Menschen: export_review_report_as_pdf (PDF und CSV). Für die Weiterarbeit am Code \
download_sources (ZIP).
5. create_review_ticket legt ein Ticket im Review-Board an - nur auf ausdrücklichen Wunsch der Person, nie von selbst, \
und nie zweimal für denselben Befund.

Hinweise:
- Repositories heißen wie ihr Projekt in Kleinbuchstaben mit Bindestrich (shop-backend, lager-app, abrechnung-core).
- Teams: "plattform", "vertrieb", "logistik". Ein unbekanntes Team ergibt eine leere Liste, keinen Fehler.
- Alle Zeiten sind in Europe/Berlin, Datumsangaben im Format JJJJ-MM-TT.
- Quellcode ist vertraulich: nie ganze Dateien in eine Antwort kopieren, wenn ein Ausschnitt reicht.
- Die Ressourcen dev://guidelines/... sind dieselben Richtlinien wie über get_guideline, als Markdown.
- Die Ressource dev://builds/{number}/log ist das Protokoll eines Builds; dev://repositories/{name}/files/{path} eine \
Datei eines Repositories.
- Bei Fehlern "Repository unbekannt" zuerst list_repositories aufrufen; Groß- und Kleinschreibung zählt.
- Dieser Server liefert erfundene Daten der Musterfirma - Rechnungsnummern, Namen und Adressen sind Beispiele \
(example.com).

Antworten bitte auf Deutsch, mit Sie, und mit der Quelle (Richtlinie, Datei mit Zeile oder Build-Nummer). \
Abkürzungen: RL = Richtlinie, MR = Merge Request, CI = Continuous Integration, ADR = Architecture Decision Record. \
Ende der Hinweise des Entwicklungsportals."""

mcp = FastMCP(
    name="entwicklungsportal",
    instructions=INSTRUCTIONS,
    host="0.0.0.0",
    port=PORT,
    transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
)

GUIDELINES = {
    "RL-007": ("Paketnamen und Modulschnitt", "Pakete heißen `de.musterfirma.<produkt>.<schicht>.<fachbereich>`. "
               "Eine Schicht greift nur auf die darunter zu. Zyklen zwischen Modulen brechen den Build."),
    "RL-012": ("Code-Review: Ablauf und Checkliste", "Jeder Merge Request braucht zwei Freigaben, eine davon aus dem "
               "Team des Moduls. Geprüft wird: Tests für jede Regel, keine Geheimnisse im Code, Fehlermeldungen ohne "
               "Kundendaten, Migrationen nur vorwärts. Größe: höchstens 400 geänderte Zeilen."),
    "RL-019": ("Logging und Fehlermeldungen", "Logzeilen auf Englisch, ohne personenbezogene Daten. Jede "
               "Fehlermeldung an Benutzer hat einen Code der Form `<bereich>.<regel>`. Ümlaute und ß sind in Texten "
               "für Benutzer erlaubt - „so wie hier“."),
}
REPOSITORIES = {
    "shop-backend": ("vertrieb", "Java", "Bestellungen, Preise und Kundenkonto des Webshops"),
    "lager-app": ("logistik", "Kotlin", "Wareneingang und Kommissionierung auf dem Handscanner"),
    "abrechnung-core": ("plattform", "Java", "Rechnungen, Gutschriften und der Monatsabschluss"),
}
FILES = {
    "src/main/java/de/musterfirma/shop/service/preis/PreisService.java": """package de.musterfirma.shop.service.preis;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Berechnet den Verkaufspreis einer Position - Rabatt zuerst, dann die Umsatzsteuer. */
public final class PreisService {

    private static final BigDecimal UMSATZSTEUER = new BigDecimal("0.19");

    public BigDecimal brutto(BigDecimal netto, BigDecimal rabattProzent) {
        BigDecimal rabattiert = netto.subtract(netto.multiply(rabattProzent).movePointLeft(2));
        return rabattiert.add(rabattiert.multiply(UMSATZSTEUER)).setScale(2, RoundingMode.HALF_UP);
    }
}
""",
    "README.md": "# shop-backend\n\nBestellungen, Preise und Kundenkonto. Bauen: `./gradlew build`.\n",
    "scripts/pruefe_preise.py": "def pruefe(preise):\n    return [p for p in preise if p < 0]\n",
}


def repository_of(name: str) -> tuple:
    if name not in REPOSITORIES:
        raise ValueError(f"Repository unbekannt: {name} - list_repositories nennt die vorhandenen")
    return REPOSITORIES[name]


@mcp.tool(title="Richtlinien durchsuchen", annotations=READS)
def search_guidelines(
    query: Annotated[str, Field(description="Suchbegriffe, durch Leerzeichen getrennt - z. B. \"Review Freigaben\". "
                                            "Mindestens ein Wort mit drei Buchstaben.", min_length=3, max_length=200)],
    limit: Annotated[int, Field(description="Wie viele Treffer höchstens zurückkommen (1 bis 20).", ge=1, le=20)] = 5,
) -> str:
    """Durchsucht die Entwicklungsrichtlinien der Musterfirma (RL-001 bis RL-040) nach Stichworten.

    Verwenden, wenn jemand nach einer Regel der Softwareentwicklung fragt: Benennung, Reviews, Logging, Tests,
    Migrationen, Freigaben. Liefert Nummer, Titel und einen Ausschnitt je Treffer - den ganzen Text holt get_guideline.

    Nicht verwenden für Fragen zum Quellcode selbst (dafür search_code) oder zu Builds (get_build_log).
    """
    log(f"search_guidelines {query!r}")
    words = [word.lower() for word in query.split()]
    hits = [{"id": number, "title": title, "excerpt": text[:120]}
            for number, (title, text) in GUIDELINES.items()
            if any(word in (title + " " + text).lower() for word in words)]
    return json.dumps(hits[:limit], ensure_ascii=False, indent=2)


@mcp.tool(title="Richtlinie lesen", annotations=READS)
def get_guideline(
    id: Annotated[str, Field(description="Die Nummer der Richtlinie, z. B. RL-012.", pattern=r"^RL-\d{3}$")],
) -> list:
    """Liefert eine Richtlinie im Wortlaut (Markdown) und einen Verweis auf sie als Ressource.

    Die Nummer kommt aus search_guidelines. Eine unbekannte Nummer ist ein Fehler.
    """
    log(f"get_guideline {id}")
    if id not in GUIDELINES:
        raise ValueError(f"Richtlinie unbekannt: {id}")
    title, text = GUIDELINES[id]
    return [TextContent(type="text", text=f"# {id} {title}\n\n{text}"),
            ResourceLink(type="resource_link", uri=f"dev://guidelines/{id}", name=f"{id}.md", mimeType="text/markdown")]


class Repository(BaseModel):
    name: str = Field(description="Der Name des Repositories, wie ihn die anderen Werkzeuge erwarten")
    team: str = Field(description="Das Team, dem es gehört")
    language: str = Field(description="Die Hauptsprache des Codes")
    summary: str = Field(description="Wozu es dient, in einem Satz")


class Repositories(BaseModel):
    repositories: list[Repository] = Field(description="Die Repositories, nach Namen sortiert")


@mcp.tool(title="Repositories auflisten", annotations=READS)
def list_repositories(
    team: Annotated[str, Field(description="Nur die Repositories dieses Teams (plattform, vertrieb, logistik). "
                                           "Leer: alle.")] = "",
) -> Repositories:
    """Listet die Repositories der Musterfirma mit Team, Sprache und Zweck - als strukturiertes Ergebnis.

    Der erste Schritt bei jeder Frage zu Code: die Namen hier gelten für get_file, search_code, download_sources und
    export_review_report_as_pdf.
    """
    log(f"list_repositories {team!r}")
    return Repositories(repositories=[Repository(name=name, team=owner, language=language, summary=summary)
                                      for name, (owner, language, summary) in sorted(REPOSITORIES.items())
                                      if not team or owner == team])


@mcp.tool(title="Datei lesen", annotations=READS)
def get_file(
    repository: Annotated[str, Field(description="Der Name des Repositories aus list_repositories.")],
    path: Annotated[str, Field(description="Der Pfad der Datei relativ zum Repository, ohne Schrägstrich am Anfang - "
                                           "z. B. src/main/java/de/musterfirma/shop/service/preis/PreisService.java.")],
) -> list:
    """Liefert eine Datei eines Repositories als eingebettete Textdatei (mit ihrem Namen und Typ).

    Für Quellcode, Konfiguration und Dokumentation. Binärdateien und Dateien über 1 MB gibt es nur über
    download_sources. Eine unbekannte Datei ist ein Fehler - search_code findet den richtigen Pfad.
    """
    log(f"get_file {repository} {path}")
    repository_of(repository)
    if path not in FILES:
        raise ValueError(f"Datei unbekannt: {path}")
    mime_type = "text/x-java" if path.endswith(".java") else "text/x-python" if path.endswith(".py") else "text/markdown"
    return [blob(f"dev://repositories/{repository}/files/{path}", mime_type, FILES[path].encode())]


SEARCH_SYNTAX = "\n".join(
    f"    - Beispiel {number:02d}: `{pattern}` findet {meaning}."
    for number, (pattern, meaning) in enumerate([
        ("PreisService", "jede Zeile mit diesem Wort"),
        ("class \\w+Service", "Klassen, deren Name auf Service endet"),
        ("TODO|FIXME", "offene Punkte"),
        ("BigDecimal\\(\"0\\.\\d+\"\\)", "feste Sätze als Zahl im Code"),
        ("^import java\\.util\\.Date", "die veraltete Datumsklasse"),
        ("@Transactional", "Methoden mit Transaktion"),
        ("catch \\(Exception", "zu breite Fehlerbehandlung"),
        ("System\\.out", "Ausgaben an der Logbibliothek vorbei"),
        ("password|secret|token", "mögliche Geheimnisse - das Ergebnis vertraulich behandeln"),
        ("Thread\\.sleep", "Warten im Code"),
    ] * 6, start=1))


@mcp.tool(title="Quellcode durchsuchen", annotations=READS)
def search_code(
    repository: Annotated[str, Field(description="Der Name des Repositories aus list_repositories.")],
    pattern: Annotated[str, Field(description="Ein regulärer Ausdruck (Java-Syntax) oder ein einfaches Wort.",
                                  min_length=2, max_length=300)],
) -> str:
    """Durchsucht den Quellcode eines Repositories und liefert die Treffer als `Pfad:Zeile: Code`.

    Verwenden, um eine Klasse, eine Methode oder eine Stelle zu finden, bevor get_file die Datei holt.
    Höchstens 50 Treffer; die Suche unterscheidet Groß- und Kleinschreibung.

    Die Syntax an Beispielen (diese Beschreibung ist mit Absicht sehr lang - über 4.000 Zeichen):
{syntax}

    ENDE DER BESCHREIBUNG VON search_code.
    """
    log(f"search_code {repository} {pattern!r}")
    repository_of(repository)
    hits = [f"{path}:{number}: {line.strip()}"
            for path, text in FILES.items()
            for number, line in enumerate(text.splitlines(), start=1)
            if pattern.lower() in line.lower()]
    return "\n".join(hits[:50]) or "Keine Treffer."


search_code.__doc__ = search_code.__doc__.format(syntax=SEARCH_SYNTAX)
# FastMCP read the docstring when the tool was registered - the long one replaces it.
mcp._tool_manager._tools["search_code"].description = search_code.__doc__.strip()


@mcp.tool(title="Build-Protokoll lesen", annotations=READS)
def get_build_log(
    number: Annotated[int, Field(description="Die Nummer des Builds, wie sie in der CI steht (z. B. 4711).", ge=1)],
) -> str:
    """Liefert das Protokoll eines Builds der CI als Text - mehrere tausend Zeilen, die Ursache meist am Ende."""
    log(f"get_build_log {number}")
    return build_log(number)


def build_log(number: int) -> str:
    lines = [f"[{line:05d}] INFO  compile de.musterfirma.shop.Modul{line % 40}" for line in range(1, 2600)]
    lines.append(f"[02600] ERROR PreisServiceTest > brutto() FAILED: expected 119.00 but was 118.99 (Build {number})")
    lines.append("BUILD END")
    return "\n".join(lines)


@mcp.tool(title="Review-Bericht exportieren (PDF und CSV)", annotations=READS)
def export_review_report_as_pdf(
    repository: Annotated[str, Field(description="Der Name des Repositories aus list_repositories.")],
) -> list:
    """Erzeugt den Review-Bericht eines Repositories als PDF (zum Lesen) und die Befunde als CSV (zum Weiterverarbeiten).

    Zwei Dateien in einer Antwort. Der Bericht fasst die offenen Befunde der letzten 30 Tage zusammen.
    """
    log(f"export_review_report_as_pdf {repository}")
    repository_of(repository)
    findings = "Datei;Zeile;Befund;Richtlinie\nPreisService.java;14;Rundung vor der Steuer prüfen;RL-012\n"
    return [TextContent(type="text", text=f"Review-Bericht für {repository}: 1 offener Befund."),
            blob(f"dev://reports/{repository}/review.pdf", "application/pdf",
                 pdf([f"Review-Bericht {repository}", "1 offener Befund: Rundung vor der Steuer (RL-012)"])),
            blob(f"dev://reports/{repository}/befunde.csv", "text/csv", findings.encode())]


@mcp.tool(title="Quellen herunterladen (ZIP)", annotations=READS)
def download_sources(
    repository: Annotated[str, Field(description="Der Name des Repositories aus list_repositories.")],
) -> list:
    """Liefert den Quellcode eines Repositories als ZIP-Archiv - für die Arbeit am Code außerhalb des Portals."""
    log(f"download_sources {repository}")
    repository_of(repository)
    return [blob(f"dev://repositories/{repository}/sources.zip", "application/zip",
                 project_zip("de.musterfirma." + repository.replace("-", ""), repository))]


@mcp.tool(title="Review-Ticket anlegen",
          annotations=ToolAnnotations(destructiveHint=False, idempotentHint=False, openWorldHint=False))
def create_review_ticket(
    repository: Annotated[str, Field(description="Der Name des Repositories aus list_repositories.")],
    title: Annotated[str, Field(description="Der Titel des Tickets, höchstens 120 Zeichen.", max_length=120)],
    findings: Annotated[list[str], Field(description="Die Befunde, je einer pro Eintrag - mit Datei und Zeile.")],
) -> str:
    """Legt ein Ticket im Review-Board an - dieses Werkzeug ÄNDERT etwas.

    Nur auf ausdrücklichen Wunsch der Person aufrufen. Das Ticket ist danach für das Team des Repositories sichtbar
    und lässt sich hier nicht wieder löschen.
    """
    log(f"create_review_ticket {repository} {title!r} ({len(findings)} findings)")
    repository_of(repository)
    return f"Ticket REV-1042 für {repository} angelegt: {title} ({len(findings)} Befunde)"


@mcp.tool(title="Architekturbild", annotations=READS)
def architecture_diagram(
    repository: Annotated[str, Field(description="Der Name des Repositories aus list_repositories.")],
) -> list:
    """Liefert das Architekturbild eines Repositories als Bild (PNG) mit einer Zeile Erklärung."""
    log(f"architecture_diagram {repository}")
    repository_of(repository)
    return [TextContent(type="text", text=f"Architektur von {repository}: Web - Service - Datenbank."),
            ImageContent(type="image", data=base64.b64encode(png(160, 96)).decode(), mimeType="image/png")]


# Resources ------------------------------------------------------------------------------------------------------------


def guideline_resource(number: str):
    title, text = GUIDELINES[number]

    @mcp.resource(f"dev://guidelines/{number}", name=f"{number}.md", title=f"{number} {title}",
                  description=f"Die Richtlinie {number} im Wortlaut: {title}", mime_type="text/markdown")
    def read() -> str:
        log(f"resource guideline {number}")
        return f"# {number} {title}\n\n{text}\n"


for guideline in GUIDELINES:
    guideline_resource(guideline)


@mcp.resource("dev://diagrams/shop-backend.png", name="shop-backend.png", title="Architekturbild shop-backend",
              description="Das Architekturbild des Webshops als Bild", mime_type="image/png")
def diagram() -> bytes:
    log("resource diagram")
    return png(160, 96)


@mcp.resource("dev://builds/{number}/log", name="Build-Protokoll", mime_type="text/plain",
              description="Das Protokoll eines Builds der CI - die Nummer in die Adresse einsetzen")
def build_log_resource(number: str) -> str:
    log(f"resource build log {number}")
    return build_log(int(number))


@mcp.resource("dev://repositories/{name}/readme", name="README eines Repositories", mime_type="text/markdown",
              description="Die README-Datei eines Repositories - den Namen aus list_repositories einsetzen")
def readme(name: str) -> str:
    log(f"resource readme {name}")
    owner, language, summary = repository_of(name)
    return f"# {name}\n\n{summary}.\n\nTeam: {owner}, Sprache: {language}.\n"


# Prompts --------------------------------------------------------------------------------------------------------------


@mcp.prompt(title="Merge Request prüfen",
            description="Prüft einen Merge Request gegen die Review-Checkliste (RL-012) - mit Repository und Nummer.")
def review_merge_request(
    repository: Annotated[str, Field(description="Der Name des Repositories")],
    number: Annotated[str, Field(description="Die Nummer des Merge Requests")] = "1",
) -> list[base.Message]:
    return [base.UserMessage(f"Prüfen Sie den Merge Request {number} im Repository {repository} gegen die "
                             "Review-Checkliste RL-012. Lesen Sie die Richtlinie zuerst mit get_guideline."),
            base.AssistantMessage("Ich lese zuerst RL-012 und danach die geänderten Dateien."),
            base.UserMessage("Nennen Sie jeden Befund mit Datei und Zeile, und fragen Sie, bevor Sie ein Ticket anlegen.")]


@mcp.prompt(title="Richtlinie erklären", description="Erklärt eine Richtlinie in einfachen Worten, mit einem Beispiel.")
def explain_guideline(id: Annotated[str, Field(description="Die Nummer der Richtlinie, z. B. RL-019")]) -> str:
    if id not in GUIDELINES:
        raise ValueError(f"Richtlinie unbekannt: {id}")
    return f"Erklären Sie die Richtlinie {id} in einfachen Worten und geben Sie ein Beispiel aus dem Alltag."


if __name__ == "__main__":
    log(f"Entwicklungsportal (company MCP server) on :{PORT}/mcp" + (" (bearer key required)" if KEY else ""))
    uvicorn.run(BearerKey(mcp.streamable_http_app(), KEY), host="0.0.0.0", port=PORT, log_level="warning")
