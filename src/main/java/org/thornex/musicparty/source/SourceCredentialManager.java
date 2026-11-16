package org.thornex.musicparty.source;

public interface SourceCredentialManager {
    String credentialSourceId();
    String credentialType();
    String credentialLabel();
    boolean isCredentialConfigured();
    void updateCredential(String value);
}
