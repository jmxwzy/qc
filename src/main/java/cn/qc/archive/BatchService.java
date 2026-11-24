package cn.qc.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.*;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class BatchService {
    private final BatchStore store;
    BatchService(BatchStore store) { this.store = store; }

    public ObjectNode create(Map<String,String> input) throws IOException {
        boolean demo = input.values().stream().allMatch(s -> s == null || s.trim().isEmpty());
        Path sample = Paths.get(System.getProperty("qc.demo","demo")).toAbsolutePath();
        Path root = select(input,"root",demo,sample.resolve("档案")).toRealPath();
        Path rules = select(input,"rules",demo,sample.resolve("清单.tsv"));
        Path ledger = select(input,"ledger",demo,sample.resolve("台账.xlsx"));
        Path template = select(input,"template",demo,sample.resolve("报告模板.docx"));
        if (store.home.toRealPath().startsWith(root)) throw new IOException("档案总目录不能包含工作台数据目录");
        UUID id = UUID.randomUUID();
        Path dir = Files.createDirectory(store.directory(id));
        try {
            Files.copy(rules,dir.resolve("rules.tsv"));
            Files.copy(template,dir.resolve("template.docx"));
            List<Map<String,String>> rows = OfficeService.readLedger(ledger);
            if (rows.isEmpty()) throw new IOException("台账没有用户记录");
            List<Map<String,Object>> users = AuditService.inspect(root,dir.resolve("rules.tsv"),rows);
            Map<String,Map<String,String>> byName = new HashMap<>();
            for (Map<String,String> row : rows) byName.put(Normalizer.normalize(row.get("用户名称").trim(),Normalizer.Form.NFC),row);
            for (Map<String,Object> user : users) {
                Map<String,String> row = byName.getOrDefault(user.get("name"), Collections.emptyMap());
                user.put("ledgerValue",row.getOrDefault("自备应急电源总容量",""));
                user.put("ledgerUnit",row.getOrDefault("单位",""));
                user.put("ledgerSource",row.isEmpty() ? "" : ledger.getFileName()+" · "+row.getOrDefault("source",""));
                user.put("ledgerReason", row.getOrDefault("reason", ""));
                user.put("onLedger", !row.isEmpty());
                ObjectNode fingerprints = store.json.createObjectNode();
                for (Object path : (List<?>)user.get("files")) {
                    String relative = path.toString();
                    if (relative.toLowerCase(Locale.ROOT).endsWith(".docx"))
                        fingerprints.put(relative, fingerprint(root.resolve(relative)));
                }
                user.put("planHashes", fingerprints);
                user.put("review",emptyReview());
            }
            ObjectNode batch = store.json.createObjectNode();
            batch.put("id",id.toString()).put("created",OffsetDateTime.now().toString()).put("root",root.toString()).put("demo",demo);
            batch.set("users",store.json.valueToTree(users));
            store.save(id,batch);
            return batch;
        } finally {store.discardUncommitted(id);}
    }

    private ObjectNode emptyReview() {
        ObjectNode review = store.json.createObjectNode();
        review.put("version",0).put("plan","").put("value","").put("unit","").put("note","");
        review.putObject("decisions");
        review.putObject("associations");
        return review;
    }

    private static Path select(Map<String,String> input,String key,boolean demo,Path example) throws IOException {
        if (demo) return example;
        String value = input.get(key);
        if (value==null || value.trim().isEmpty() || !Paths.get(value.trim()).isAbsolute())
            throw new IOException("自定义批次需要填写四个完整路径，缺少或无效："+key);
        return Paths.get(value.trim());
    }

    static ObjectNode user(ObjectNode batch,int index) {
        JsonNode u = batch.path("users").path(index);
        if (!u.isObject()) throw new IllegalArgumentException("用户序号无效");
        return (ObjectNode)u;
    }

    private static String fingerprint(Path file) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(file)) { return org.apache.commons.codec.digest.DigestUtils.sha256Hex(in); }
    }

    private static Path resolvePlan(ObjectNode batch,ObjectNode user,String relative) throws IOException {
        boolean known = false;
        for (JsonNode f : user.path("files")) if (relative.equals(f.asText())) known=true;
        if (!known || !relative.toLowerCase(Locale.ROOT).endsWith(".docx")) throw new IOException("请选择本户文件清单中的 DOCX 预案");
        Path root = Paths.get(batch.path("root").asText());
        Path source = root.resolve(relative).normalize();
        if (!source.equals(source.toRealPath()) || !source.startsWith(root)) throw new IOException("档案路径发生变化，请重新核查");
        if (!fingerprint(source).equals(user.path("planHashes").path(relative).asText()))
            throw new IOException("该文档自批次创建后已修改。请新建批次，使用同一版本的资料核查。");
        return source;
    }

    Map<String,String> extract(UUID id,int index,String relative) throws IOException {
        ObjectNode batch = store.get(id);
        ObjectNode user = user(batch,index);
        Map<String,String> out = new LinkedHashMap<>(OfficeService.readPlan(resolvePlan(batch,user,relative)));
        String value = user.path("ledgerValue").asText(), unit = user.path("ledgerUnit").asText();
        out.put("ledgerValue",value); out.put("ledgerUnit",unit);
        out.put("source",relative+" · "+out.getOrDefault("source",""));
        String comparison = "待人工复核";
        if ("OK".equals(out.get("status")) && !unit.isEmpty() && unit.equals(out.get("unit"))) {
            try {
                boolean same = new BigDecimal(value).compareTo(new BigDecimal(out.get("value")))==0;
                comparison = same ? "同单位数值一致，待确认" : "同单位数值不同，待核对依据";
            } catch(NumberFormatException e) {comparison="数值口径待核实";}
        }
        out.put("comparison",comparison);
        return out;
    }

    synchronized ObjectNode review(UUID id,int index,ObjectNode input) throws IOException {
        ObjectNode batch = store.get(id), user = user(batch,index);
        int version = user.path("review").path("version").asInt();
        if (!input.path("version").isIntegralNumber() || input.path("version").asInt()!=version)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"其他窗口已保存此户，请重新载入后核对");
        ObjectNode next = emptyReview();
        for (String field : Arrays.asList("plan","value","unit","note"))
            next.put(field,input.path(field).asText("").trim());
        String plan = next.path("plan").asText(), value = next.path("value").asText(), unit=next.path("unit").asText();
        if (!plan.isEmpty()) {
            Path source = resolvePlan(batch,user,plan);
            next.put("sourceHash", user.path("planHashes").path(plan).asText());
            try { next.set("sourceReading", store.json.valueToTree(OfficeService.readPlan(source))); }
            catch (IOException | RuntimeException e) { next.put("sourceReadingNote","此文件未能自动取值，以人工记录为准"); }
        }
        if (value.isEmpty()!=unit.isEmpty()) throw new IOException("确认值和单位应一并填写或一并留空");
        if (!value.isEmpty()) {
            if (next.path("note").asText().isEmpty()) throw new IOException("请记录该确认值的核实依据");
            try {if (new BigDecimal(value).signum()<0) throw new NumberFormatException();}
            catch(NumberFormatException e){throw new IOException("容量确认值应为非负数字，范围值或约数请记录在备注中");}
        }
        ObjectNode decisions = next.with("decisions");
        for (JsonNode issue : user.path("issues")) {
            if ("文件未归类".equals(issue.path("type").asText())) {
                String path = issue.path("path").asText();
                String code = input.path("associations").path(path).asText("");
                if (!code.isEmpty()) {
                    boolean valid = false;
                    for (JsonNode item : user.path("items")) if (code.equals(item.path("code").asText())) valid = true;
                    if (!valid) throw new IOException("认领的档案项不存在");
                    next.with("associations").put(path,code);
                }
            }
            String key=issue.path("id").asText(), decision=input.path("decisions").path(key).asText("").trim();
            if (!decision.isEmpty()) decisions.put(key,decision);
        }
        next.put("version",version+1).put("updated",OffsetDateTime.now().toString());
        if (version > 0) user.withArray("reviewHistory").add(user.path("review").deepCopy());
        user.set("review",next);
        store.save(id,batch);
        return next;
    }
}
