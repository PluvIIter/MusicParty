package org.thornex.musicparty.source;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.service.api.BilibiliCookieService;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;

@Service
public class BilibiliCredentialManager implements SourceCredentialManager {
    private final BilibiliMusicApiService api;
    private final BilibiliCookieService cookies;
    public BilibiliCredentialManager(BilibiliMusicApiService api, BilibiliCookieService cookies) {
        this.api = api;
        this.cookies = cookies;
    }
    public String credentialSourceId() { return "bilibili"; }
    public String credentialType() { return "cookie"; }
    public String credentialLabel() { return "Bilibili Cookie"; }
    public boolean isCredentialConfigured() { return cookies.hasCookie(); }
    public void updateCredential(String value) { api.updateCookie(value); }
}
