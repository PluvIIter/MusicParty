package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class QqMusicCredentialService {
    private static final Set<String> FIELDS = Set.of("musicid", "musickey", "loginType", "encrypt_uin");
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private volatile Snapshot current;

    public static final class Snapshot {
        private final long revision;
        private final Map<String, Object> credential;
        private final boolean valid;

        private Snapshot(long revision, Map<String, Object> credential) {
            this(revision, credential, true);
        }

        private Snapshot(long revision, Map<String, Object> credential, boolean valid) {
            this.revision = revision;
            this.credential = Map.copyOf(credential);
            this.valid = valid;
        }

        public void requireValid() {
            if (!valid) throw new ApiRequestException("INVALID_CREDENTIAL: QQ音乐配置凭据无效，请重新配置");
        }

        public long revision() { return revision; }
        public boolean configured() { return !credential.isEmpty(); }
        Map<String, Object> credential() { return credential; }
        @Override public String toString() { return "QQ Music credential snapshot"; }
    }

    public QqMusicCredentialService(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try { this.current = new Snapshot(0, parse(properties.getQqmusic().getCookie())); }
        catch (ApiRequestException error) { this.current = new Snapshot(0, Map.of(), false); }
    }

    public Snapshot snapshot() { return current; }

    public void requireCurrent(Snapshot snapshot) {
        if (current != snapshot) throw new ApiRequestException("CREDENTIAL_CHANGED: QQ音乐凭据已更新，请重试");
    }

    public synchronized void updateCookie(String value) {
        Map<String, Object> parsed = parse(value);
        if (current.valid && parsed.equals(current.credential())) return;
        String normalized;
        try { normalized = parsed.isEmpty() ? "" : mapper.writeValueAsString(parsed); }
        catch (Exception error) { throw invalid(); }
        current = new Snapshot(current.revision() + 1, parsed);
        properties.getQqmusic().setCookie(normalized);
    }

    private Map<String, Object> parse(String value) {
        if (value == null || value.isBlank()) return Map.of();
        if (value.length() > 16384) throw invalid();
        try {
            JsonNode root = mapper.readTree(value);
            if (!root.isObject()) throw invalid();
            if (root.isEmpty()) return Map.of();
            Iterator<String> names = root.fieldNames();
            while (names.hasNext()) if (!FIELDS.contains(names.next())) throw invalid();
            JsonNode idNode = root.path("musicid");
            if (!idNode.isTextual() && !idNode.isIntegralNumber()) throw invalid();
            String id = idNode.asText();
            if (!id.matches("[1-9][0-9]{0,18}") || new java.math.BigInteger(id).compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) throw invalid();
            String key = boundedText(root.path("musickey"), 4096);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("musicid", id);
            result.put("musickey", key);
            if (root.has("loginType")) {
                JsonNode login = root.get("loginType");
                if (!login.isIntegralNumber() || !login.canConvertToInt() || login.intValue() < 0 || login.intValue() > 2) throw invalid();
                result.put("loginType", login.intValue());
            }
            if (root.has("encrypt_uin")) result.put("encrypt_uin", boundedText(root.get("encrypt_uin"), 256));
            return Map.copyOf(result);
        } catch (Exception error) { throw invalid(); }
    }

    private String boundedText(JsonNode value, int maximum) {
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maximum
                || value.textValue().chars().anyMatch(Character::isISOControl)) throw invalid();
        return value.textValue();
    }

    private ApiRequestException invalid() {
        return new ApiRequestException("INVALID_CREDENTIAL: QQ音乐凭据须为规范化Credential JSON");
    }
}
