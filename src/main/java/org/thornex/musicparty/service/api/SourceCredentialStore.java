package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class SourceCredentialStore {
    private final ObjectMapper mapper;
    private final Path file;
    private final Map<String, String> values;

    public SourceCredentialStore(ObjectMapper mapper, AppProperties properties) {
        this.mapper = mapper;
        this.file = Path.of(properties.getCredentialFile());
        this.values = load();
    }

    public synchronized String get(String source) {
        return values.getOrDefault(source, "");
    }

    public synchronized void put(String source, String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) values.remove(source);
        else values.put(source, normalized);
        save();
    }

    private Map<String, String> load() {
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(mapper.readValue(Files.readString(file), new TypeReference<>() {}));
        } catch (IOException | RuntimeException error) {
            log.warn("Ignoring unreadable source credential file {}", file, error);
            return new LinkedHashMap<>();
        }
    }

    private void save() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, mapper.writeValueAsString(values), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(file);
        } catch (IOException | RuntimeException error) {
            log.error("Could not persist source credentials to {}", file, error);
        }
    }

    private void restrictToOwner(Path target) {
        try {
            Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }
}
