package cn.qc.archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Read-only directory inspection; every file shown in the result is relative to the selected root. */
public final class AuditService {
    private static final LinkOption NO_LINK = LinkOption.NOFOLLOW_LINKS;
    private static final Set<String> EXTENSIONS = new HashSet<>(Arrays.asList(
            "doc", "docx", "xls", "xlsx", "pdf", "jpg", "jpeg", "png", "txt", "tif", "tiff",
            "bmp", "wps", "ofd", "dwg", "zip"));
    private static final String HEADER = "编号\t名称\t适用\t份数";

    private AuditService() {}

    public static List<Map<String,Object>> inspect(Path root, Path rulesFile, List<Map<String,String>> ledger)
            throws IOException {
        if (!Files.isDirectory(root, NO_LINK)) throw new IOException("请选择档案总目录。");
        List<Rule> rules = readRules(rulesFile);
        Map<String,Path> folders = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) {
                if (!Files.isDirectory(path, NO_LINK) || ignored(path)) continue;
                String key = normalized(path.getFileName().toString());
                if (folders.put(key, path) != null) throw new IOException("同名目录无法区分：" + key);
            }
        }
        Set<String> names = new LinkedHashSet<>();
        for (Map<String,String> row : ledger) {
            String name = row.get("用户名称");
            if (name == null || name.trim().isEmpty()) throw new IOException("台账存在空用户名称。");
            if (!names.add(normalized(name.trim()))) throw new IOException("台账有重名用户，请先明确匹配关系：" + name);
        }
        List<Map<String,Object>> reports = new ArrayList<>();
        for (String name : names) {
            reports.add(inspectUser(root, name, folders.remove(name), rules, true));
        }
        for (Map.Entry<String,Path> entry : folders.entrySet()) {
            reports.add(inspectUser(root, entry.getKey(), entry.getValue(), rules, false));
        }
        return reports;
    }

    private static Map<String,Object> inspectUser(Path root, String name, Path folder, List<Rule> rules,
                                                   boolean onLedger) throws IOException {
        List<String> files = new ArrayList<>();
        List<Map<String,String>> issues = new ArrayList<>();
        Map<String,List<String>> matches = new LinkedHashMap<>();
        for (Rule rule : rules) matches.put(rule.code, new ArrayList<>());
        String directory = folder == null ? "" : relative(root, folder);
        if (folder == null) {
            issues.add(issue(name, "未匹配到户目录", "", "核实用户是否使用简称，或尚未建立档案目录。", ""));
        } else {
            if (!onLedger) issues.add(issue(name, "目录未匹配台账", "", "请核对文件夹名称和台账用户名称。", directory));
            collectFiles(root, folder, files, issues, name);
            Collections.sort(files);
            for (String path : files) {
                String filename = path.substring(path.lastIndexOf('/') + 1);
                String ext = extension(filename);
                if (!EXTENSIONS.contains(ext)) {
                    issues.add(issue(name,"格式待核实","","该扩展名未列入可识别格式，需人工确认文件内容。",path));
                    continue;
                }
                String stem = normalized(filename.substring(0,filename.lastIndexOf('.')));
                if (EXTENSIONS.contains(extension(stem))) {
                    issues.add(issue(name,"双扩展名","","请核实真实格式及可读性。",path));
                    stem = stem.substring(0,stem.lastIndexOf('.'));
                }
                Rule rule = match(stem, rules);
                if (rule == null) {
                    issues.add(issue(name, "文件未归类", "", "文件名未唯一匹配清单。请查看原件并核定档案项。", path));
                } else {
                    matches.get(rule.code).add(path);
                }
            }
        }
        List<Map<String,Object>> items = new ArrayList<>();
        for (Rule rule : rules) {
            List<String> paths = matches.get(rule.code);
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("code", rule.code); item.put("name", rule.name);
            item.put("applicability", rule.applicability); item.put("multiplicity", rule.multiplicity);
            item.put("paths", paths); items.add(item);
            if (folder == null) continue; // Resolve the user directory before drawing item-level conclusions.
            if (paths.isEmpty()) {
                boolean required = "必备".equals(rule.applicability);
                issues.add(issue(name, required ? "未匹配到必备项" : "适用性待核实", rule.name,
                        required ? "请确认实际缺件或命名未匹配。" : "请核实该户是否适用此项要求。", directory));
            } else if (paths.size() > 1 && "单份".equals(rule.multiplicity)) {
                issues.add(issue(name, "多个候选版本", rule.name, "请核实本次采用的有效版本。", String.join(" ／ ",paths)));
            }
        }
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("name",name); report.put("directory",directory);
        report.put("issues",issues); report.put("files",files); report.put("items",items);
        return report;
    }

    private static void collectFiles(Path root, Path userFolder, List<String> files,
                                     List<Map<String,String>> issues, String name) throws IOException {
        Files.walkFileTree(userFolder, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return !dir.equals(userFolder) && ignored(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (ignored(file)) return FileVisitResult.CONTINUE;
                String path = relative(root,file);
                if (attrs.isRegularFile()) files.add(path);
                else issues.add(issue(name, "链接或特殊文件", "", "未读取该条目，请人工核对。", path));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean ignored(Path path) throws IOException {
        String name = path.getFileName().toString();
        return name.startsWith(".") || name.startsWith("~$") || name.endsWith("~")
                || name.equalsIgnoreCase("Thumbs.db") || name.equalsIgnoreCase("desktop.ini")
                || (!Files.isSymbolicLink(path) && Files.isHidden(path));
    }

    private static String relative(Path root,Path path) {
        String result = root.relativize(path).toString();
        return result.replace(java.io.File.separatorChar, '/');
    }

    private static String normalized(String text) { return Normalizer.normalize(text, Normalizer.Form.NFC); }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot+1).toLowerCase(Locale.ROOT) : "";
    }

    private static Rule match(String stem, List<Rule> rules) {
        // Prefer the complete, more specific material name over a shorter name followed by an underscore.
        Rule best = null;
        int bestLength = -1;
        boolean ambiguous = false;
        for (Rule rule : rules) {
            for (String label : Arrays.asList(rule.code + "_" + rule.name, rule.name)) {
                if (!(stem.equals(label) || (stem.startsWith(label + "_") && stem.length() > label.length()+1))) continue;
                if (label.length() > bestLength) {
                    best = rule; bestLength = label.length(); ambiguous = false;
                } else if (label.length() == bestLength && best != rule) {
                    ambiguous = true;
                }
            }
        }
        return ambiguous ? null : best;
    }

    private static Map<String,String> issue(String user,String type,String item,String detail,String path) {
        Map<String,String> row = new LinkedHashMap<>();
        String key = String.join("\u0000",user,type,item,path);
        row.put("id",UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString());
        row.put("type",type); row.put("item",item); row.put("detail",detail); row.put("path",path);
        return row;
    }

    private static List<Rule> readRules(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file,StandardCharsets.UTF_8);
        boolean hasHeader = false;
        Set<String> codes = new HashSet<>(), names = new HashSet<>();
        List<Rule> rules = new ArrayList<>();
        for (int i=0;i<lines.size();i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
            if (!hasHeader) {
                if (!HEADER.equals(line.trim())) throw new IOException("规则表首行应为以制表符分隔的：编号、名称、适用、份数。");
                hasHeader = true;
                continue;
            }
            String[] data = line.split("\t",-1);
            for (int col=0;col<data.length;col++) data[col] = normalized(data[col].trim());
            if (data.length != 4 || data[0].isEmpty() || data[1].isEmpty()
                    || !("必备".equals(data[2]) || "条件".equals(data[2]))
                    || !("单份".equals(data[3]) || "多份".equals(data[3])))
                throw new IOException("规则表第 " + (i+1) + " 行无效，请检查编号、名称、适用和份数。");
            Rule rule = new Rule(data);
            if (!codes.add(rule.code) || !names.add(rule.name))
                throw new IOException("规则表第 " + (i+1) + " 行与其他项重名或重号。");
            rules.add(rule);
        }
        if (rules.isEmpty()) throw new IOException("规则表没有档案项。");
        return rules;
    }

    private static final class Rule {
        final String code, name, applicability, multiplicity;
        Rule(String[] row) { code=row[0]; name=row[1]; applicability=row[2]; multiplicity=row[3]; }
    }
}
