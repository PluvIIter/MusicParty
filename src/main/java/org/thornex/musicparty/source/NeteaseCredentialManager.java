package org.thornex.musicparty.source;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;

@Service
public class NeteaseCredentialManager implements SourceCredentialManager {
    private final NeteaseMusicApiService api;
    public NeteaseCredentialManager(NeteaseMusicApiService api) { this.api = api; }
    public String credentialSourceId() { return "netease"; }
    public String credentialType() { return "cookie"; }
    public String credentialLabel() { return "网易云音乐 Cookie"; }
    public boolean isCredentialConfigured() { return api.isCookieConfigured(); }
    public void updateCredential(String value) { api.updateCookie(value); }
}
