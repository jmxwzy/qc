package cn.qc.archive;

import java.util.*;
import java.nio.file.NoSuchFileException;
import java.io.IOException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/batches")
public class BatchController {
    private final BatchStore store;
    private final BatchService service;
    private final ExportService exports;

    BatchController(BatchStore store, BatchService service, ExportService exports) {
        this.store = store;
        this.service = service;
        this.exports = exports;
    }

    @GetMapping public List<ObjectNode> list() throws IOException {return store.summaries();}
    @PostMapping public ObjectNode create(@RequestBody(required=false) Map<String,String> input) throws IOException {
        return service.create(input==null?Collections.emptyMap():input);
    }
    @GetMapping("/{id}") public ObjectNode get(@PathVariable UUID id) throws IOException {return store.get(id);}
    @PostMapping("/{id}/users/{i}/extract") public Map<String,String> extract(@PathVariable UUID id,@PathVariable int i,
        @RequestBody Map<String,String> request) throws IOException {
        return service.extract(id,i,request.getOrDefault("plan",""));
    }
    @PutMapping("/{id}/users/{i}/review") public ObjectNode review(@PathVariable UUID id,@PathVariable int i,
            @RequestBody ObjectNode review) throws IOException {return service.review(id,i,review);}
    @GetMapping("/{id}/users/{i}/report") public ResponseEntity<byte[]> report(@PathVariable UUID id,@PathVariable int i)
            throws IOException {
        return download(exports.report(id,i),"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "draft-"+id.toString().substring(0,8)+"-"+(i+1)+".docx");
    }

    @GetMapping("/{id}/reports.zip") public ResponseEntity<byte[]> reports(@PathVariable UUID id) throws IOException {
        return download(exports.allReports(id),"application/zip","drafts-"+id+".zip");
    }
    @GetMapping("/{id}/export") public ResponseEntity<byte[]> export(@PathVariable UUID id) throws IOException {
        return download(exports.checklist(id),"text/csv;charset=UTF-8","review-"+id+".csv");
    }

    private static ResponseEntity<byte[]> download(byte[] bytes,String type,String name) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"")
            .contentType(MediaType.parseMediaType(type)).body(bytes);
    }
    @ExceptionHandler({IOException.class,IllegalArgumentException.class,ResponseStatusException.class})
    public ResponseEntity<Map<String,String>> error(Exception e) {
        HttpStatus status= e instanceof NoSuchFileException?HttpStatus.NOT_FOUND:HttpStatus.BAD_REQUEST;
        String message=e.getMessage();
        if (e instanceof ResponseStatusException) {
            ResponseStatusException re=(ResponseStatusException)e;status=re.getStatus();message=re.getReason();
        }
        return ResponseEntity.status(status).body(Collections.singletonMap("message",message));
    }
}
