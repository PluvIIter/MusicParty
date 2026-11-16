package config

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestNativeQqDefaultsAndMissingCurrentConfig(t *testing.T) {
	cfg := DefaultConfig()
	if cfg.Sources["qqmusic"] != (SourceConfig{false, DirectURL}) || cfg.QqmusicQuality != "MP3_320" {
		t.Fatal("unsafe QQ defaults")
	}
	cfg, err := DecodeConfig([]byte(`{"sources":{"netease":{"enabled":false,"deliveryMode":"DIRECT_URL"}},"serverPort":"9000"}`))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Sources["qqmusic"] != (SourceConfig{false, DirectURL}) || cfg.QqmusicQuality != "MP3_320" || cfg.ServerPort != "9000" || cfg.Sources["netease"].Enabled {
		t.Fatal("missing QQ defaults changed configured values")
	}
	for _, field := range SourceDescriptors() {
		if field.ID != "qqmusic" {
			continue
		}
		if field.LocalService != "QQMUSIC_API" {
			t.Fatal("native Web API descriptor missing")
		}
		// 与网易云一致：只有 Cookie 与音质两个字段，没有「运行方式 / 外部 API 地址」。
		var keys []string
		for _, connection := range field.ConnectionFields {
			keys = append(keys, connection.Key)
			if connection.Key == "qqmusicCookie" && connection.Kind != "password" {
				t.Fatal("credential is not masked")
			}
		}
		if !reflect.DeepEqual(keys, []string{"qqmusicCookie", "qqmusicQuality"}) {
			t.Fatalf("unexpected QQ connection fields: %v", keys)
		}
	}
}

func TestLegacyQqGatewayKeysAreIgnored(t *testing.T) {
	cfg, err := DecodeConfig([]byte(`{"sources":{"qqmusic":{"enabled":false,"deliveryMode":"DIRECT_URL"}},"qqmusicBaseUrl":"http://external.test:8001","qqmusicGatewayMode":"external","qqmusicCookie":"kept credential"}`))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.QqmusicCookie != "kept credential" {
		t.Fatal("cookie lost while dropping legacy gateway keys")
	}
	data, err := json.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	for _, legacy := range []string{"qqmusicGatewayMode", "qqmusicBaseUrl"} {
		if strings.Contains(string(data), legacy) {
			t.Fatalf("legacy key %s was written back", legacy)
		}
	}
}

func TestNativeQqConfigAndArgumentsPreserveSettings(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Sources["qqmusic"] = SourceConfig{true, DirectURL}
	cfg.QqmusicCookie = "musicid=123; musickey=private value; ascii; openid=id; refresh_token=refresh"
	cfg.QqmusicQuality = "FLAC"
	data, err := json.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	restored, err := DecodeConfig(data)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(cfg, restored) {
		t.Fatal("native config changed")
	}
	args, err := cfg.JavaArgs("server.jar", "http://127.0.0.1:43210")
	if err != nil {
		t.Fatal(err)
	}
	var actual []string
	for _, arg := range args {
		if strings.HasPrefix(arg, "--app.music-api.qqmusic.") {
			actual = append(actual, arg)
		}
	}
	want := []string{
		"--app.music-api.qqmusic.base-url=http://127.0.0.1:43210",
		"--app.music-api.qqmusic.cookie=" + cfg.QqmusicCookie,
		"--app.music-api.qqmusic.quality=FLAC",
	}
	if !reflect.DeepEqual(actual, want) {
		t.Fatal("native QQ Java arguments do not match contract")
	}
}

func TestNativeQqCookieCredentials(t *testing.T) {
	for _, value := range []string{
		"musicid=1; music\nkey=private", "musicid=1\r; musickey=private", "musicid=secret\tvalue",
		strings.Repeat("x", qqCookieMaximum+1), `{"musicid":1,"musickey":"secret"}`,
	} {
		if err := validateQqCredential(value); err == nil || strings.Contains(err.Error(), "private") {
			t.Fatal("invalid cookie accepted or exposed")
		}
	}
	for _, value := range []string{"", "musicid=1; musickey=private", "musicid=123; musickey=a; openid=x; refresh_key=k"} {
		if err := validateQqCredential(value); err != nil {
			t.Fatal("valid cookie rejected")
		}
	}
}

func TestNativeQqValidationWithoutSecretEcho(t *testing.T) {
	for _, update := range []func(*AppConfig){
		func(c *AppConfig) { c.QqmusicQuality = "private" },
		func(c *AppConfig) { c.QqmusicCookie = "private\ncookie" },
	} {
		cfg := DefaultConfig()
		cfg.Sources["qqmusic"] = SourceConfig{true, ServerDownload}
		update(cfg)
		if err := cfg.Validate(); err == nil || strings.Contains(err.Error(), "private") {
			t.Fatal("invalid native settings accepted or exposed")
		}
	}
}
