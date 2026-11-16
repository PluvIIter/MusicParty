package org.thornex.musicparty.resource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.exception.ApiRequestException;

@Component
public class ResourceCursor {
    private final byte[] secret = new byte[32];

    public ResourceCursor() {
        new SecureRandom().nextBytes(secret);
    }

    public String issue(String binding, int offset) {
        if (offset < 0 || offset > 100000) throw invalid();
        String payload = offset + ":" + (System.currentTimeMillis() + 900000) + ":" + digest(binding);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + signature(encoded);
    }

    public int read(String cursor, String binding) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            if (cursor.length() > 512) throw invalid();
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(signature(parts[0]).getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8))) throw invalid();
            String[] data = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split(":", -1);
            if (data.length != 3 || !data[2].equals(digest(binding))
                    || Long.parseLong(data[1]) < System.currentTimeMillis()) throw invalid();
            int offset = Integer.parseInt(data[0]);
            if (offset < 1 || offset > 100000) throw invalid();
            return offset;
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
    }

    private String signature(String encoded) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private String digest(String binding) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(binding.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private ApiRequestException invalid() {
        return new ApiRequestException("INVALID_CURSOR: 分页位置已失效或不属于当前查询");
    }
}
