package cn.qc.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

public final class OfficeService {
    private static final String USER = "用户名称";
    private static final String CAPACITY = "自备应急电源总容量";
    private static final String UNIT = "单位";
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(USER, CAPACITY, UNIT));
    private static final String UNITS = "(?:kVA|MVA|kW|MW|千伏安|兆伏安|千瓦|兆瓦)";
    private static final Pattern HEADER = Pattern.compile(CAPACITY + "(?:\\((" + UNITS + ")\\))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile(
            "^([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?)\\s*(" + UNITS + ")?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([^{}]+)\\}\\}");

    private OfficeService() { }

    private static String clean(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).trim();
    }

    private static String header(String text) {
        return clean(text).replaceAll("\\s+", "").replaceFirst(":$", "");
    }

    private static String canonicalUnit(String value) {
        String unit = clean(value);
        if (unit.equalsIgnoreCase("kVA") || unit.equals("千伏安")) return "kVA";
        if (unit.equalsIgnoreCase("MVA") || unit.equals("兆伏安")) return "MVA";
        if (unit.equalsIgnoreCase("kW") || unit.equals("千瓦")) return "kW";
        if (unit.equalsIgnoreCase("MW") || unit.equals("兆瓦")) return "MW";
        return "";
    }

    private static Map<String,String> reading(String raw, String source, String... unitHints) {
        Map<String,String> result = new LinkedHashMap<>();
        result.put("raw", raw);
        result.put("source", source);
        result.put("value", raw);
        result.put("unit", "");
        result.put("status", "REVIEW");
        Matcher amount = AMOUNT.matcher(clean(raw));
        if (!amount.matches()) {
            result.put("reason", "数值缺失、含限定词或含多个数值");
            return result;
        }
        Set<String> units = new HashSet<>();
        if (amount.group(2) != null) units.add(canonicalUnit(amount.group(2)));
        for (String hint : unitHints) {
            if (hint == null || clean(hint).isEmpty()) continue;
            String unit = canonicalUnit(hint);
            if (unit.isEmpty()) {
                result.put("reason", "无法识别单位：" + hint);
                return result;
            }
            units.add(unit);
        }
        if (units.size() != 1) {
            result.put("reason", units.isEmpty() ? "缺少容量单位" : "容量单位相互冲突");
            return result;
        }
        result.put("value", amount.group(1).replace(",", ""));
        result.put("unit", units.iterator().next());
        result.put("status", "OK");
        result.put("reason", "");
        return result;
    }

    public static List<Map<String,String>> readLedger(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file); XSSFWorkbook book = new XSSFWorkbook(input)) {
            if (book.getNumberOfSheets() == 0) throw new IOException("台账没有工作表");
            Sheet sheet = book.getSheetAt(0);
            DataFormatter format = new DataFormatter(Locale.CHINA);
            Map<String,Integer> columns = new LinkedHashMap<>();
            String labelUnit = "";
            int headerIndex = -1;
            for (Row row : sheet) {
                if (row.getRowNum() > 50) break;
                if (headerIndex >= 0) break;
                boolean containsUser = false;
                for (Cell cell : row) {
                    if (header(format.formatCellValue(cell)).equals(USER)) containsUser = true;
                }
                if (!containsUser) continue;
                headerIndex = row.getRowNum();
                for (Cell cell : row) {
                    String key = header(format.formatCellValue(cell));
                    Matcher m = HEADER.matcher(key);
                    if (m.matches()) {
                        key = CAPACITY;
                        labelUnit = m.group(1);
                    }
                    if (FIELDS.contains(key) && columns.put(key,cell.getColumnIndex()) != null)
                        throw new IOException("台账表头重复：" + key);
                }
            }
            if (headerIndex < 0) throw new IOException("首张工作表的前 51 行未找到“用户名称”表头");
            List<Map<String,String>> rows = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Row row : sheet) {
                if (row.getRowNum() <= headerIndex) continue;
                String name = cellText(row, columns.get(USER), format);
                String value = cellText(row, columns.get(CAPACITY), format);
                String unit = cellText(row, columns.get(UNIT), format);
                if (name.isEmpty() && value.isEmpty() && unit.isEmpty()) continue;
                if (name.isEmpty() || !names.add(name))
                    throw new IOException("台账第 " + (row.getRowNum()+1) + " 行用户名称为空或重复：" + name);
                String location = sheet.getSheetName() + " 第 " + (row.getRowNum()+1) + " 行";
                if (columns.containsKey(CAPACITY)) {
                    String col = org.apache.poi.ss.util.CellReference.convertNumToColString(columns.get(CAPACITY));
                    location += " · " + col + (row.getRowNum()+1);
                }
                Cell capacityCell = columns.containsKey(CAPACITY) ? row.getCell(columns.get(CAPACITY)) : null;
                Map<String,String> result = ledgerReading(capacityCell, value, location, labelUnit, unit);
                if (!columns.containsKey(CAPACITY)) result.put("reason", "台账没有同名容量字段");
                result.put("name",name);
                result.put(USER,name);
                result.put(CAPACITY,result.get("value"));
                result.put(UNIT,result.get("unit"));
                rows.add(result);
            }
            if (rows.isEmpty()) throw new IOException("台账没有用户记录");
            return rows;
        }
    }

    private static String cellText(Row row, Integer column, DataFormatter format) {
        if (column == null) return "";
        return format.formatCellValue(row.getCell(column)).trim();
    }

    private static Map<String,String> ledgerReading(Cell cell, String display, String source, String labelUnit, String unit) {
        if (cell != null && cell.getCellType() == CellType.FORMULA) {
            Map<String,String> result = reading("=" + display, source);
            result.put("reason", "容量为公式，请核实其结果及依据");
            return result;
        }
        Matcher shown = AMOUNT.matcher(clean(display));
        if (cell instanceof XSSFCell && cell.getCellType() == CellType.NUMERIC && shown.matches()) {
            // Number formats can round or scale the displayed value. Its implied precision
            // and unit must be confirmed before using it to decide equality.
            String raw = ((XSSFCell) cell).getRawValue();
            BigDecimal stored = new BigDecimal(raw);
            BigDecimal visible = new BigDecimal(shown.group(1).replace(",", ""));
            if (stored.compareTo(visible) != 0) {
                Map<String,String> result = reading(display, source, labelUnit, unit);
                result.put("status", "REVIEW");
                result.put("unit", "");
                result.put("reason", "单元格显示为 " + display + "，存储数值为 " + raw + "，请核实精度、格式及单位");
                return result;
            }
        }
        return reading(display, source, labelUnit, unit);
    }

    public static Map<String,String> readPlan(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(input)) {
            List<Map<String,String>> matches = new ArrayList<>();
            findValues(doc.getTables(), file.getFileName().toString(), matches);
            if (matches.size() == 1) return matches.get(0);
            Map<String,String> result = reading("", file.getFileName().toString());
            result.put("reason", matches.isEmpty() ? "正文表格未找到同名容量字段，请查看原件"
                    : "正文表格存在多个同名容量字段，请确认有效来源");
            if (!matches.isEmpty()) {
                List<String> origins = new ArrayList<>();
                for (Map<String,String> match : matches) origins.add(match.get("source") + " = " + match.get("raw"));
                result.put("source", String.join("；", origins));
            }
            return result;
        }
    }

    private static void findValues(List<XWPFTable> tables, String prefix, List<Map<String,String>> matches) {
        int tableNumber = 0;
        for (XWPFTable table : tables) {
            String tableSource = prefix + " / 表 " + (++tableNumber);
            int rowNumber = 0;
            for (XWPFTableRow row : table.getRows()) {
                rowNumber++;
                List<XWPFTableCell> cells = row.getTableCells();
                for (int i = 0; i < cells.size(); i++) {
                    XWPFTableCell cell = cells.get(i);
                    String source = tableSource + " 行 " + rowNumber + " 列 " + (i+1);
                    Matcher label = HEADER.matcher(header(wordCellText(cell)));
                    if (label.matches()) {
                        if (rowNumber == 1 && readColumnTable(table, i, label.group(1), tableSource, matches))
                            continue;
                        String raw = i+1 < cells.size() ? wordCellText(cells.get(i+1)) : "";
                        String valueSource = i+1 < cells.size()
                                ? tableSource + " 行 " + rowNumber + " 列 " + (i+2) : source;
                        String nextUnit = "";
                        if (i+2 < cells.size()) {
                            String text = clean(wordCellText(cells.get(i+2)));
                            if (!canonicalUnit(text).isEmpty()) nextUnit = text;
                            else if (text.equals(UNIT) && i+3 < cells.size()) nextUnit = wordCellText(cells.get(i+3));
                        }
                        matches.add(reading(raw, valueSource, label.group(1), nextUnit));
                    }
                    findValues(cell.getTables(), source, matches);
                }
            }
        }
    }

    private static String wordCellText(XWPFTableCell cell) {
        // XWPFTableCell.getText() joins paragraphs with no separator, so two separate
        // values such as "200" and "400 kW" would otherwise be interpreted as "200400 kW".
        List<String> lines = new ArrayList<>();
        for (XWPFParagraph p : cell.getParagraphs()) lines.add(p.getText());
        return String.join("\n", lines).trim();
    }

    private static boolean readColumnTable(XWPFTable table, int capacity, String labelUnit, String source,
                                           List<Map<String,String>> matches) {
        List<XWPFTableCell> headers = table.getRow(0).getTableCells();
        int user = -1, unit = -1;
        for (int i = 0; i < headers.size(); i++) {
            if (i == capacity) continue;
            String key = header(wordCellText(headers.get(i)));
            if (key.equals(USER) && user < 0) user = i;
            else if (key.equals(UNIT) && unit < 0) unit = i;
            else return false;
        }
        // Only the recognized user/capacity[/unit] grid defines a vertical field unambiguously.
        if (user < 0 || table.getRows().size() < 2) return false;
        for (int r = 1; r < table.getRows().size(); r++) {
            List<XWPFTableCell> cells = table.getRow(r).getTableCells();
            String location = source + " 行 " + (r+1) + " 列 " + (capacity+1);
            if (cells.size() != headers.size() || hasMergedCell(cells) || hasMergedCell(headers)
                    || wordCellText(cells.get(user)).isEmpty()) {
                Map<String,String> review = reading("", location);
                review.put("reason", "表格列布局不齐或用户不明，请核实原件");
                matches.add(review);
            } else {
                matches.add(reading(wordCellText(cells.get(capacity)), location, labelUnit,
                        unit < 0 ? "" : wordCellText(cells.get(unit))));
            }
        }
        return true;
    }

    private static boolean hasMergedCell(List<XWPFTableCell> cells) {
        for (XWPFTableCell cell : cells) {
            if (!cell.getCTTc().isSetTcPr()) continue;
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr props = cell.getCTTc().getTcPr();
            if (props.isSetHMerge() || props.isSetVMerge() || props.isSetGridSpan()) return true;
        }
        return false;
    }

    public static void generate(Path template, Path output, Map<String,String> fields) throws IOException {
        Path destination = output.toAbsolutePath();
        if (Files.exists(destination)) throw new IOException("报告输出已存在，请选择新文件名");
        try (InputStream input = Files.newInputStream(template); XWPFDocument doc = new XWPFDocument(input)) {
            int count = replaceBody(doc, fields);
            for (IBody h : doc.getHeaderList()) count += replaceBody(h, fields);
            for (IBody f : doc.getFooterList()) count += replaceBody(f, fields);
            if (count == 0) throw new IOException("模板中没有可填充字段");
            Path temp = Files.createTempFile(destination.getParent(), ".report-", ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(temp)) { doc.write(out); }
                Files.move(temp, destination);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static int replaceBody(IBody body, Map<String,String> fields) throws IOException {
        int count = 0;
        for (XWPFParagraph p : body.getParagraphs()) count += fillParagraph(p,fields);
        for (XWPFTable table : body.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) count += replaceBody(cell,fields);
            }
        }
        return count;
    }

    // Word may split a placeholder into several formatted runs. Record offsets in the original text,
    // then edit from the right so offsets of earlier placeholders stay valid.
    private static int fillParagraph(XWPFParagraph p, Map<String,String> fields) throws IOException {
        List<CTText> nodes = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        StringBuilder original = new StringBuilder();
        for (XWPFRun run : p.getRuns()) {
            for (CTText text : run.getCTR().getTList()) {
                starts.add(original.length());
                nodes.add(text);
                original.append(text.getStringValue());
            }
        }
        Matcher tokens = TOKEN.matcher(original);
        List<int[]> ranges = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        while (tokens.find()) {
            String field = tokens.group(1).trim();
            if (!FIELDS.contains(field)) throw new IOException("模板含未定义字段：" + field);
            String value = fields.get(field);
            if (value == null || value.trim().isEmpty()) throw new IOException("模板字段尚待填写：" + field);
            ranges.add(new int[]{tokens.start(), tokens.end()});
            replacements.add(value);
        }
        String rest = TOKEN.matcher(original).replaceAll("");
        if (rest.contains("{{") || rest.contains("}}")) throw new IOException("模板占位符不完整");
        for (int j = ranges.size()-1; j >= 0; j--) {
            int start = ranges.get(j)[0], end = ranges.get(j)[1];
            for (int i = 0; i < nodes.size(); i++) {
                int offset = starts.get(i);
                int limit = i+1 < nodes.size() ? starts.get(i+1) : original.length();
                if (limit <= start || offset >= end || offset == limit) continue;
                CTText node = nodes.get(i);
                String text = node.getStringValue();
                String left = text.substring(0, Math.max(0, start-offset));
                String right = text.substring(Math.min(end,limit)-offset);
                node.setStringValue(left + (offset <= start ? replacements.get(j) : "") + right);
            }
        }
        return ranges.size();
    }
}
