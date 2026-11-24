package cn.qc.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
import java.util.stream.Stream;
import javax.annotation.PreDestroy;

/** One local process owns the batch store. Files are committed by same-directory rename. */
@Component
public class BatchStore {
    final ObjectMapper json;
    final Path home;
    private final FileChannel channel;
    private final FileLock lock;

    public BatchStore(ObjectMapper json) throws IOException {
        this.json = json;
        home = Paths.get(System.getProperty("qc.data", "data")).toAbsolutePath().normalize();
        Files.createDirectories(home);
        channel = FileChannel.open(home.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = channel.tryLock();
        if (lock == null) throw new IOException("已有工作台使用此数据目录");
    }
    @PreDestroy public void close() throws IOException { lock.release(); channel.close(); }

    Path directory(UUID id) { return home.resolve(id.toString()); }

    ObjectNode get(UUID id) throws IOException {
        Path file = directory(id).resolve("batch.json");
        if (!Files.isRegularFile(file)) throw new NoSuchFileException("找不到该批次");
        return (ObjectNode) json.readTree(file.toFile());
    }

    List<ObjectNode> summaries() throws IOException {
        List<ObjectNode> result = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(home)) {
            for (Path dir : paths) {
                if (!Files.isRegularFile(dir.resolve("batch.json"))) continue;
                ObjectNode batch = get(UUID.fromString(dir.getFileName().toString()));
                ObjectNode summary = json.createObjectNode();
                for (String key : Arrays.asList("id","created","demo")) summary.set(key,batch.get(key));
                result.add(summary);
            }
        }
        result.sort(Comparator.comparing((ObjectNode b) -> b.path("created").asText()).reversed());
        return result;
    }

    void save(UUID id, ObjectNode batch) throws IOException {
        Path dir = directory(id);
        Path temp = Files.createTempFile(dir,"state-", ".tmp");
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),batch);
            Files.move(temp,dir.resolve("batch.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    void discardUncommitted(UUID id) throws IOException {
        Path dir = directory(id);
        if (!Files.exists(dir) || Files.exists(dir.resolve("batch.json"))) return;
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path file : (Iterable<Path>)paths::iterator) Files.delete(file);
        }
        Files.delete(dir);
    }
}
