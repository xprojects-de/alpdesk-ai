package de.alpdesk.services.chat;

import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.util.XRLog;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TableSeparator;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import de.alpdesk.services.common.ServiceException;
import jakarta.inject.Singleton;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Writes the files the chat's model creates (tool create_file). The model writes text: the text formats are stored as
 * written; a DOCX or PDF is made from Markdown (headings, paragraphs, bold/italic, lists, tables, code), an XLSX from
 * CSV. Layout of an uploaded original is not kept - the model only knows its text.
 */
@Singleton
public class FileRenderer {

    private static final MutableDataSet MARKDOWN_OPTIONS = new MutableDataSet()
            .set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
    private static final Parser MARKDOWN = Parser.builder(MARKDOWN_OPTIONS).build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder(MARKDOWN_OPTIONS).build();
    /**
     * A cell taken as a number: no leading zero (a postcode, an article or phone number stays text - "01067" would
     * become 1067) and at most 15 digits before the point, what Excel keeps exactly.
     */
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9]\\d{0,14})(\\.\\d+)?");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String PDF_STYLE = """
            body { font-family: Helvetica, sans-serif; font-size: 11pt; line-height: 1.4; margin: 0; }
            h1 { font-size: 20pt; } h2 { font-size: 16pt; } h3 { font-size: 13pt; }
            table { border-collapse: collapse; width: 100%; margin: 8pt 0; }
            th, td { border: 1px solid #999; padding: 4pt 6pt; text-align: left; vertical-align: top; }
            th { background: #eee; }
            code, pre { font-family: Courier, monospace; font-size: 9.5pt; background: #f2f2f2; }
            pre { padding: 6pt; white-space: pre-wrap; }
            blockquote { margin-left: 0; padding-left: 10pt; border-left: 3px solid #ccc; color: #444; }
            @page { size: A4; margin: 20mm; }""";

    static {
        // openhtmltopdf logs a refused resource with its address (java.util.logging, to stderr) - an address the model
        // wrote can carry the company's data.
        XRLog.setLoggingEnabled(false);
    }

    public Rendered render(OutputFormat format, String content) {
        String text = content == null ? "" : content;
        return switch (format) {
            case TXT, MD, JSON -> new Rendered(text.getBytes(StandardCharsets.UTF_8), format.contentType);
            // With a byte order mark: Excel opens a CSV without one in the system code page and garbles umlauts.
            case CSV -> new Rendered(concat(UTF8_BOM, text.getBytes(StandardCharsets.UTF_8)), format.contentType);
            case HTML -> new Rendered(htmlDocument(text).getBytes(StandardCharsets.UTF_8), format.contentType);
            case DOCX -> new Rendered(docx(text), format.contentType);
            case XLSX -> new Rendered(xlsx(text), format.contentType);
            case PDF -> new Rendered(pdf(text), format.contentType);
        };
    }

    private static String htmlDocument(String html) {
        return html.toLowerCase().contains("<html") ? html
                : "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"></head><body>\n" + html + "\n</body></html>";
    }

    // --- PDF: Markdown -> HTML -> PDF ---

    private static byte[] pdf(String markdown) {
        String html = "<html><head><meta charset=\"utf-8\"/><style>" + PDF_STYLE + "</style></head><body>"
                + HTML.render(MARKDOWN.parse(markdown)) + "</body></html>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // Nothing is loaded from outside the text: the model writes the Markdown, and a prompt injection in a
            // document or a tool result could make it reference an internal address or a local file (images, style
            // sheets) - fetched by the server and embedded in the PDF.
            new PdfRendererBuilder()
                    .useFastMode()
                    .useExternalResourceAccessControl((uri, type) -> false,
                            ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI)
                    .useExternalResourceAccessControl((uri, type) -> false,
                            ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI)
                    .withW3cDocument(new W3CDom().fromJsoup(Jsoup.parse(html)), "/")
                    .toStream(out)
                    .run();
        } catch (IOException | RuntimeException e) {
            throw ServiceException.badRequest("chatFile.pdfNotCreated", "The PDF could not be created");
        }
        return out.toByteArray();
    }

    // --- DOCX: the Markdown tree written with POI ---

    private static byte[] docx(String markdown) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Node block : MARKDOWN.parse(markdown).getChildren()) {
                writeBlock(document, block, 0);
            }
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the DOCX", e);
        }
    }

    private static void writeBlock(XWPFDocument document, Node block, int indent) {
        switch (block) {
            case Heading heading -> {
                XWPFParagraph paragraph = document.createParagraph();
                writeInline(paragraph, heading, true, false, Math.max(12, 22 - 3 * heading.getLevel()));
            }
            case Paragraph paragraph -> writeInline(indented(document, indent), paragraph, false, false, 11);
            case BulletList list -> writeList(document, list, indent, false);
            case OrderedList list -> writeList(document, list, indent, true);
            case TableBlock table -> writeTable(document, table);
            case FencedCodeBlock code -> writeCode(document, code.getContentChars().toString());
            case IndentedCodeBlock code -> writeCode(document, code.getContentChars().toString());
            case BlockQuote quote -> quote.getChildren().forEach(child -> writeBlock(document, child, indent + 1));
            default -> {
                String text = block.getChars().toString().strip();
                if (!text.isEmpty() && !text.matches("[-*_\\s]{3,}")) {
                    indented(document, indent).createRun().setText(text);
                }
            }
        }
    }

    private static void writeList(XWPFDocument document, Node list, int indent, boolean ordered) {
        int number = 1;
        for (Node item : list.getChildren()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            boolean first = true;
            for (Node child : item.getChildren()) {
                if (child instanceof Paragraph paragraph && first) {
                    XWPFParagraph line = indented(document, indent + 1);
                    line.createRun().setText(ordered ? (number++) + ". " : "• ");
                    writeInline(line, paragraph, false, false, 11);
                    first = false;
                } else {
                    writeBlock(document, child, indent + 1);
                }
            }
        }
    }

    private static void writeTable(XWPFDocument document, TableBlock block) {
        List<List<String>> rows = new ArrayList<>();
        List<Boolean> header = new ArrayList<>();
        collectRows(block, rows, header, false);
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        XWPFTable table = document.createTable(rows.size(), columns);
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = table.getRow(r);
            for (int c = 0; c < columns; c++) {
                XWPFParagraph cell = row.getCell(c).getParagraphs().getFirst();
                XWPFRun run = cell.createRun();
                run.setText(c < rows.get(r).size() ? rows.get(r).get(c) : "");
                run.setBold(header.get(r));
            }
        }
        document.createParagraph();
    }

    private static void collectRows(Node node, List<List<String>> rows, List<Boolean> header, boolean inHead) {
        for (Node child : node.getChildren()) {
            if (child instanceof TableSeparator) {
                // The |---|---| line: no row of the table.
                continue;
            }
            if (child instanceof TableRow row) {
                List<String> cells = new ArrayList<>();
                for (Node cell : row.getChildren()) {
                    if (cell instanceof TableCell tableCell) {
                        cells.add(tableCell.getText().toString().strip());
                    }
                }
                rows.add(cells);
                header.add(inHead);
            } else {
                collectRows(child, rows, header, inHead || child instanceof TableHead);
            }
        }
    }

    private static void writeCode(XWPFDocument document, String code) {
        XWPFParagraph paragraph = document.createParagraph();
        String[] lines = code.stripTrailing().split("\n", -1);
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Courier New");
        run.setFontSize(9);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                run.addBreak();
            }
            run.setText(lines[i], i);
        }
    }

    private static void writeInline(XWPFParagraph paragraph, Node parent, boolean bold, boolean italic, int size) {
        for (Node node : parent.getChildren()) {
            switch (node) {
                case Text text -> run(paragraph, text.getChars().toString(), bold, italic, size, false);
                case StrongEmphasis strong -> writeInline(paragraph, strong, true, italic, size);
                case Emphasis emphasis -> writeInline(paragraph, emphasis, bold, true, size);
                case Code code -> run(paragraph, code.getText().toString(), bold, italic, size, true);
                case SoftLineBreak _ -> run(paragraph, " ", bold, italic, size, false);
                case HardLineBreak _ -> paragraph.createRun().addBreak();
                default -> {
                    if (node.hasChildren()) {
                        writeInline(paragraph, node, bold, italic, size);
                    } else {
                        run(paragraph, node.getChars().toString(), bold, italic, size, false);
                    }
                }
            }
        }
    }

    private static void run(XWPFParagraph paragraph, String text, boolean bold, boolean italic, int size, boolean code) {
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setItalic(italic);
        run.setFontSize(size);
        if (code) {
            run.setFontFamily("Courier New");
        }
    }

    private static XWPFParagraph indented(XWPFDocument document, int indent) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(indent * 360);
        return paragraph;
    }

    // --- XLSX: CSV written with POI ---

    private static byte[] xlsx(String csv) {
        List<List<String>> rows = parseCsv(csv.strip());
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Tabelle");
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            int[] widths = new int[rows.stream().mapToInt(List::size).max().orElse(0)];
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows.get(r).size(); c++) {
                    String value = rows.get(r).get(c);
                    Cell cell = row.createCell(c);
                    if (r > 0 && NUMBER.matcher(value).matches()) {
                        cell.setCellValue(Double.parseDouble(value));
                    } else {
                        cell.setCellValue(value);
                    }
                    if (r == 0) {
                        cell.setCellStyle(headerStyle);
                    }
                    widths[c] = Math.max(widths[c], value.length());
                }
            }
            // Not autoSizeColumn: it measures with AWT fonts, which a server container may not have.
            for (int c = 0; c < widths.length; c++) {
                sheet.setColumnWidth(c, Math.min(255, Math.max(8, widths[c] + 2)) * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the XLSX", e);
        } catch (IllegalArgumentException e) {
            // POI refuses what Excel cannot hold - the model hears it and can split its table.
            throw ServiceException.badRequest("chatFile.xlsxNotCreated", "The XLSX could not be created - a cell holds "
                    + "at most 32,767 characters, a sheet at most 16,384 columns");
        }
    }

    /** CSV with ";" or "," or tab - whichever the first line has most of - and "quoted" fields. */
    static List<List<String>> parseCsv(String csv) {
        String firstLine = csv.lines().findFirst().orElse("");
        char delimiter = ';';
        long most = firstLine.chars().filter(c -> c == ';').count();
        for (char candidate : new char[]{',', '\t'}) {
            long count = firstLine.chars().filter(c -> c == candidate).count();
            if (count > most) {
                most = count;
                delimiter = candidate;
            }
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                row.add(field.toString().strip());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString().strip());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        row.add(field.toString().strip());
        rows.add(row);
        return rows;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** The bytes of a created file and its type. */
    public record Rendered(byte[] content, String contentType) {
    }
}
