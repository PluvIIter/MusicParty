package org.thornex.musicparty.source;

public interface SourceCredentialManager {
    String credentialSourceId();
    String credentialType();
    String credentialLabel();
    boolean isCredentialConfigured();
    void updateCredential(String value);
    default void refreshCredential() {
        throw new org.thornex.musicparty.exception.ApiRequestException("CAPABILITY_UNAVAILABLE: 此音源不支持凭据刷新");
    }

    default void refreshCredentialIfNeeded() {
        refreshCredential();
    }
}
