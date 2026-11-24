package cn.qc.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds downloads from a single saved batch revision. */
@Service
public class ExportService {
    private final BatchStore store;

    ExportService(BatchStore store) { this.store = store; }

    byte[] checklist(UUID id) throws IOException {
        StringBuilder csv=new StringBuilder("\uFEFF");
        row(csv,"用户","类别","档案项","出处","巡检提示","人工结论","核实依据","台账值","预案取值","预案取值位置");
        for (JsonNode user:store.get(id).path("users")) {
            String name=user.path("name").asText();
            JsonNode review=user.path("review");
            for (JsonNode issue:user.path("issues")) {
                String path=issue.path("path").asText();
                String code=review.path("associations").path(path).asText();
                row(csv,name,issue.path("type").asText(),code.isEmpty()?issue.path("item").asText():"人工认领 "+code,path,
                        issue.path("detail").asText(),review.path("decisions").path(issue.path("id").asText()).asText("待复核"),"","","","");
            }
            String value=review.path("value").asText();
            JsonNode reading = review.path("sourceReading");
            String planValue = (reading.path("value").asText()+" "+reading.path("unit").asText()).trim();
            row(csv,name,"字段核对","自备应急电源总容量",
                user.path("ledgerSource").asText()+" / "+review.path("plan").asText(),
                reading.path("reason").asText(review.path("sourceReadingNote").asText()),
                value.isEmpty()?"待核实":value+" "+review.path("unit").asText(),review.path("note").asText(),
                (user.path("ledgerValue").asText()+" "+user.path("ledgerUnit").asText()).trim(),
                planValue,reading.path("source").asText());
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void row(StringBuilder out,String... cells) {
        for (int i=0;i<cells.length;i++) {
            if(i>0) out.append(',');
            String text=cells[i];
            if(text.trim().matches("(?s)^[=+@-].*") || text.startsWith("\t") || text.startsWith("\r")) text="'"+text;
            out.append('"').append(text.replace("\"","\"\"")).append('"');
        }
        out.append("\r\n");
    }

    byte[] report(UUID id,int index) throws IOException {
        ObjectNode u=BatchService.user(store.get(id),index);
        return renderReport(id,u);
    }

    private byte[] renderReport(UUID id,ObjectNode u) throws IOException {
        JsonNode review=u.path("review");
        Map<String,String> fields=new HashMap<>();
        fields.put("用户名称",u.path("name").asText());
        fields.put("自备应急电源总容量",review.path("value").asText().isEmpty()?"待核实":review.path("value").asText());
        fields.put("单位",review.path("unit").asText().isEmpty()?"待核实":review.path("unit").asText());
        Path temp=Files.createTempDirectory("qc-report-");
        Path file=temp.resolve("draft.docx");
        try {
            OfficeService.generate(store.directory(id).resolve("template.docx"),file,fields);
            return Files.readAllBytes(file);
        } finally { Files.deleteIfExists(file);Files.delete(temp); }
    }

    byte[] allReports(UUID id) throws IOException {
        ObjectNode batch=store.get(id);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try (ZipOutputStream zip=new ZipOutputStream(buffer)) {
            int count=0;
            for (JsonNode user:batch.path("users")) {
                if (!user.path("onLedger").asBoolean()) continue;
                String name=user.path("name").asText().replaceAll("[^\\p{L}\\p{N}._-]","_");
                zip.putNextEntry(new ZipEntry(String.format("%03d_%s_报告初稿.docx",++count,name)));
                zip.write(renderReport(id,(ObjectNode)user));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }
}
