package config

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestNativeQqDefaultsAndMissingCurrentConfig(t *testing.T) {
	cfg := DefaultConfig()
	if cfg.Sources["qqmusic"] != (SourceConfig{false, ServerDownload}) || cfg.QqmusicGatewayMode != "bundled" || cfg.QqmusicBaseURL != "http://127.0.0.1:8000" {
		t.Fatal("unsafe QQ defaults")
	}
	cfg, err := DecodeConfig([]byte(`{"sources":{"netease":{"enabled":false,"deliveryMode":"DIRECT_URL"}},"serverPort":"9000"}`))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Sources["qqmusic"] != (SourceConfig{false, ServerDownload}) || cfg.ServerPort != "9000" || cfg.Sources["netease"].Enabled {
		t.Fatal("missing QQ defaults changed configured values")
	}
	cfg, err = DecodeConfig([]byte(`{"sources":{"qqmusic":{"enabled":false,"deliveryMode":"DIRECT_URL"}},"qqmusicBaseUrl":"http://external.test:8001","qqmusicCookie":"kept credential"}`))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.QqmusicGatewayMode != "external" || cfg.QqmusicCookie != "kept credential" {
		t.Fatal("custom external API configuration changed")
	}
	for _, field := range SourceDescriptors() {
		if field.ID == "qqmusic" {
			if field.LocalService != "QQMUSIC_API" || !strings.Contains(field.Description, "1124") {
				t.Fatal("native Web API descriptor missing")
			}
			for _, connection := range field.ConnectionFields {
				if connection.Key == "qqmusicCookie" && connection.Kind != "password" {
					t.Fatal("credential is not masked")
				}
			}
		}
	}
}

func TestNativeQqConfigAndArgumentsPreserveSettings(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Sources["qqmusic"] = SourceConfig{true, DirectURL}
	cfg.QqmusicGatewayMode = "external"
	cfg.QqmusicBaseURL = "http://api.test:8001"
	cfg.QqmusicCookie = `{"musicid":123,"musickey":"private value; ascii","str_musicid":"123","openid":"id","refresh_token":"refresh","access_token":"access","expired_at":1234567890,"unionid":"union","refresh_key":"key"}`
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
	args, err := cfg.JavaArgs("server.jar")
	if err != nil {
		t.Fatal(err)
	}
	var actual []string
	for _, arg := range args {
		if strings.HasPrefix(arg, "--app.music-api.qqmusic.") {
			actual = append(actual, arg)
		}
	}
	want := []string{"--app.music-api.qqmusic.base-url=" + cfg.QqmusicBaseURL, "--app.music-api.qqmusic.cookie=" + cfg.QqmusicCookie, "--app.music-api.qqmusic.quality=FLAC"}
	if !reflect.DeepEqual(actual, want) {
		t.Fatal("native QQ Java arguments do not match contract")
	}
}

func TestNativeQqCredentialFields(t *testing.T) {
	for _, value := range []string{
		`{"musicid":0,"musickey":"private"}`, `{"musicid":9223372036854775808,"musickey":"private"}`, `{"musicid":"01","musickey":"private"}`,
		`{"musicid":1,"musickey":"private","loginType":2}`, `{"musicid":1,"musickey":"private","encrypt_uin":"private"}`,
		`{"musicid":1,"musickey":"private","musicid":2}`, `{"musicid":1,"musickey":"private","expired_at":-1}`,
		`{"musicid":1,"musickey":"private","refresh_token":null}`, `{"musicid":1,"musickey":"private\n"}`,
		`{"musicid":1,"musickey":"` + strings.Repeat("x", 4097) + `"}`,
	} {
		if err := validateQqCredential(value); err == nil || strings.Contains(err.Error(), "private") {
			t.Fatal("invalid native cookie accepted or exposed")
		}
	}
	for _, value := range []string{"", `{}`, `{"musicid":"9223372036854775807","musickey":"private","refresh_token":"","expired_at":0}`} {
		if err := validateQqCredential(value); err != nil {
			t.Fatal("valid native cookie rejected")
		}
	}
}

func TestNativeExternalQqValidationWithoutSecretEcho(t *testing.T) {
	for _, update := range []func(*AppConfig){func(c *AppConfig) { c.QqmusicBaseURL = "http://user:private@api.test" }, func(c *AppConfig) { c.QqmusicBaseURL = "http://api.test?private=1" }, func(c *AppConfig) { c.QqmusicQuality = "private" }, func(c *AppConfig) { c.QqmusicCookie = "private raw cookie" }} {
		cfg := DefaultConfig()
		cfg.Sources["qqmusic"] = SourceConfig{true, ServerDownload}
		cfg.QqmusicGatewayMode = "external"
		update(cfg)
		if err := cfg.Validate(); err == nil || strings.Contains(err.Error(), "private") {
			t.Fatal("invalid native settings accepted or exposed")
		}
	}
}
