package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class QqMusicCredentialService {
    private static final Set<String> FIELDS = Set.of("musicid", "musickey", "openid", "refresh_token", "access_token",
            "expired_at", "unionid", "str_musicid", "refresh_key");
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private volatile Snapshot current;

    public static final class Snapshot {
        private final long revision;
        private final Map<String, Object> credential;
        private final boolean valid;

        private Snapshot(long revision, Map<String, Object> credential, boolean valid) {
            this.revision = revision;
            this.credential = Map.copyOf(credential);
            this.valid = valid;
        }

        public void requireValid() {
            if (!valid) throw invalid();
        }

        public long revision() { return revision; }
        public boolean configured() { return !credential.isEmpty(); }
        Map<String, Object> credential() { return credential; }

        String cookieHeader() {
            requireValid();
            return credential.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + quoted(entry.getValue().toString()))
                    .collect(Collectors.joining("; "));
        }

        private String quoted(String value) {
            StringBuilder result = new StringBuilder("\"");
            for (char character : value.toCharArray()) {
                if (character == '"' || character == '\\') result.append('\\').append(character);
                else if (character == ';' || character == ',') result.append('\\').append(String.format("%03o", (int) character));
                else result.append(character);
            }
            return result.append('"').toString();
        }

        @Override public String toString() { return "QQ Music credential snapshot"; }
    }

    public QqMusicCredentialService(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try { current = new Snapshot(0, parse(properties.getQqmusic().getCookie()), true); }
        catch (ApiRequestException error) { current = new Snapshot(0, Map.of(), false); }
    }

    public Snapshot snapshot() { return current; }

    public void requireCurrent(Snapshot snapshot) {
        if (current != snapshot) throw new ApiRequestException("CREDENTIAL_CHANGED: QQ音乐凭据已更新，请重试");
    }

    public synchronized void updateCookie(String value) {
        Map<String, Object> parsed = parse(value);
        if (current.valid && parsed.equals(current.credential)) return;
        String normalized;
        try { normalized = parsed.isEmpty() ? "" : mapper.writeValueAsString(parsed); }
        catch (Exception error) { throw invalid(); }
        current = new Snapshot(current.revision + 1, parsed, true);
        properties.getQqmusic().setCookie(normalized);
    }

    private Map<String, Object> parse(String value) {
        if (value == null || value.isBlank()) return Map.of();
        if (value.length() > 32768) throw invalid();
        try {
            JsonNode root = mapper.readTree(value);
            if (!root.isObject()) throw invalid();
            if (root.isEmpty()) return Map.of();
            root.fieldNames().forEachRemaining(field -> { if (!FIELDS.contains(field)) throw invalid(); });
            String id = root.path("musicid").asText();
            if ((!root.path("musicid").isTextual() && !root.path("musicid").isIntegralNumber())
                    || !id.matches("[1-9][0-9]{0,18}") || new java.math.BigInteger(id).bitLength() > 63) throw invalid();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("musicid", id);
            result.put("musickey", token(root.path("musickey"), true));
            result.put("str_musicid", id);
            if (root.has("str_musicid")) result.put("str_musicid", token(root.get("str_musicid"), false));
            for (String field : FIELDS) {
                if (Set.of("musicid", "musickey", "str_musicid").contains(field) || !root.has(field)) continue;
                if (field.equals("expired_at")) {
                    JsonNode expiry = root.get(field);
                    if ((!expiry.isIntegralNumber() && !expiry.isTextual()) || !expiry.asText().matches("[0-9]{1,18}")) throw invalid();
                    result.put(field, Long.parseLong(expiry.asText()));
                } else result.put(field, token(root.get(field), false));
            }
            return Map.copyOf(result);
        } catch (Exception error) { throw invalid(); }
    }

    private String token(JsonNode value, boolean required) {
        if (!value.isTextual() || (required && value.textValue().isBlank()) || value.textValue().length() > 4096
                || !value.textValue().chars().allMatch(character -> character >= 32 && character <= 126)) throw invalid();
        return value.textValue();
    }

    private static ApiRequestException invalid() {
        return new ApiRequestException("INVALID_CREDENTIAL: QQ音乐凭据须为上游Web支持的Credential JSON");
    }
}
