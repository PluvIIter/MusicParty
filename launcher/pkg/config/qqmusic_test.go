package config

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestQqmusicDefaultsAreDisabledAndBundled(t *testing.T) {
	cfg := DefaultConfig()
	if cfg.Sources["qqmusic"] != (SourceConfig{false, ServerDownload}) {
		t.Fatal("QQ must default to disabled server download")
	}
	if cfg.QqmusicBaseURL != "http://localhost:8000" || cfg.QqmusicQuality != "MP3_128" {
		t.Fatal("unexpected QQ gateway defaults")
	}
	for _, descriptor := range SourceDescriptors() {
		if descriptor.ID != "qqmusic" {
			continue
		}
		if descriptor.LocalService != "QQMUSIC_API" || descriptor.DefaultEnabled || !strings.Contains(descriptor.Description, "一键") {
			t.Fatal("QQ must use an external gateway without an enabled local process")
		}
		for _, field := range descriptor.ConnectionFields {
			if (field.Key == "qqmusicCookie" || field.Key == "qqmusicGatewayToken") && field.Kind != "password" {
				t.Fatal("QQ credential fields must be masked")
			}
		}
	}
	if cfg.LocalServiceEnabled("QQMUSIC_API") {
		t.Fatal("launcher must not start a QQ process")
	}
}

func TestCurrentConfigMissingQqmusicAddsOnlySafeQqDefaults(t *testing.T) {
	cfg, err := DecodeConfig([]byte(`{"sources":{"netease":{"enabled":false,"deliveryMode":"SERVER_DOWNLOAD"},"bilibili":{"enabled":true,"deliveryMode":"DIRECT_URL"}},"serverPort":"9000","authRateLimitEnabled":false,"queueMaxSize":0}`))
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]SourceConfig{
		"netease": {false, ServerDownload}, "bilibili": {true, DirectURL}, "qqmusic": {false, ServerDownload},
	}
	if !reflect.DeepEqual(cfg.Sources, want) || cfg.ServerPort != "9000" || cfg.AuthRateLimitEnabled || cfg.QueueMaxSize != 0 {
		t.Fatal("QQ defaults changed existing settings")
	}
	if cfg.QqmusicBaseURL != "http://localhost:8000" || cfg.QqmusicQuality != "MP3_128" {
		t.Fatal("missing gateway fields did not receive safe defaults")
	}
	for _, unknown := range []string{"neteaseEnabled", "bilibiliEnabled"} {
		if _, err := DecodeConfig([]byte(`{"sources":{},"` + unknown + `":true}`)); err == nil {
			t.Fatal("obsolete protocol aliases must remain rejected")
		}
	}
}

func TestDisabledQqmusicPreservesExplicitBlankConnectionFields(t *testing.T) {
	cfg, err := DecodeConfig([]byte(`{"sources":{"qqmusic":{"enabled":false,"deliveryMode":"DIRECT_URL"}},"qqmusicBaseUrl":"","qqmusicQuality":"","qqmusicGatewayToken":"kept token","qqmusicCookie":"kept credential"}`))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.QqmusicBaseURL != "" || cfg.QqmusicQuality != "" || cfg.QqmusicGatewayToken != "kept token" || cfg.QqmusicCookie != "kept credential" || cfg.Sources["qqmusic"] != (SourceConfig{false, DirectURL}) {
		t.Fatal("explicit disabled QQ configuration was overwritten")
	}
}

func TestQqmusicConfigAndArgumentsPreserveExplicitSettings(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Sources["qqmusic"] = SourceConfig{true, DirectURL}
	cfg.QqmusicBaseURL = "http://gateway.test:8001"
	cfg.QqmusicGatewayMode = "external"
	cfg.QqmusicGatewayToken = "private-token=one-two"
	cfg.QqmusicCookie = `{"musicid":123,"musickey":"private value; 三"}`
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
		t.Fatal("explicit QQ settings changed during load")
	}
	args, err := restored.JavaArgs("server.jar")
	if err != nil {
		t.Fatal(err)
	}
	var qqArgs []string
	for _, arg := range args {
		if strings.HasPrefix(arg, "--app.music-api.qqmusic.") || strings.HasPrefix(arg, "--app.music-api.sources.qqmusic.") {
			qqArgs = append(qqArgs, arg)
		}
	}
	want := []string{
		"--app.music-api.qqmusic.base-url=" + cfg.QqmusicBaseURL,
		"--app.music-api.qqmusic.gateway-token=" + cfg.QqmusicGatewayToken,
		"--app.music-api.qqmusic.cookie=" + cfg.QqmusicCookie,
		"--app.music-api.qqmusic.quality=FLAC",
		"--app.music-api.sources.qqmusic.enabled=true",
		"--app.music-api.sources.qqmusic.delivery-mode=DIRECT_URL",
	}
	if !reflect.DeepEqual(qqArgs, want) {
		t.Fatal("QQ arguments did not preserve the exact gateway contract")
	}
}

func TestQqmusicTokenBoundaries(t *testing.T) {
	for _, token := range []string{strings.Repeat("a", 15), strings.Repeat("a", 513), "valid-length with-space", "valid-length-with-三", "valid-length-with-\n", "valid-length-with-\u007f"} {
		cfg := DefaultConfig()
		cfg.Sources["qqmusic"] = SourceConfig{true, ServerDownload}
		cfg.QqmusicGatewayMode = "external"
		cfg.QqmusicGatewayToken = token
		if err := cfg.Validate(); err == nil {
			t.Fatal("invalid QQ gateway token was accepted")
		}
	}
	for _, size := range []int{16, 512} {
		cfg := DefaultConfig()
		cfg.Sources["qqmusic"] = SourceConfig{true, ServerDownload}
		cfg.QqmusicGatewayMode = "external"
		cfg.QqmusicGatewayToken = strings.Repeat("~", size)
		if err := cfg.Validate(); err != nil {
			t.Fatal("valid QQ gateway token boundary was rejected")
		}
	}
}

func TestQqmusicCredentialBoundaries(t *testing.T) {
	for _, value := range []string{
		`{"musicid":0,"musickey":"private"}`, `{"musicid":9223372036854775808,"musickey":"private"}`,
		`{"musicid":true,"musickey":"private"}`, `{"musicid":"1e3","musickey":"private"}`,
		`{"musicid":1,"musickey":"private","unknown":"private"}`,
		`{"musicid":1,"musickey":"private","encryptuin":"private"}`,
		`{"musicid":1,"musickey":"private","loginType":true}`,
		`{"musicid":1,"musickey":"private","loginType":3}`,
		`{"musicid":1,"musickey":"private","loginType":null}`,
		`{"musicid":"01","musickey":"private"}`,
		`{"musicid":1,"musickey":"private","musickey":"other"}`,
		`{"musicid":1,"musicid":2,"musickey":"private"}`,
		`{"musicid":1,"musickey":"private","music\u0069d":2}`,
		`{"musicid":1,"musickey":"private\n"}`,
		`{"musicid":1,"musickey":"` + strings.Repeat("x", 4097) + `"}`,
		`{"musicid":1,"musickey":"private","encrypt_uin":"` + strings.Repeat("x", 257) + `"}`,
	} {
		if err := validateQqCredential(value); err == nil || strings.Contains(err.Error(), "private") {
			t.Fatal("invalid QQ Credential was accepted or exposed")
		}
	}
	for _, value := range []string{"", `{}`, `{"musicid":"9223372036854775807","musickey":"` + strings.Repeat("x", 4096) + `","encrypt_uin":"` + strings.Repeat("x", 256) + `","loginType":2}`} {
		if err := validateQqCredential(value); err != nil {
			t.Fatal("valid QQ Credential boundary was rejected")
		}
	}
}

func TestEnabledQqmusicValidatesGatewayWithoutLeakingSecrets(t *testing.T) {
	for _, update := range []func(*AppConfig){
		func(c *AppConfig) { c.QqmusicGatewayToken = "" },
		func(c *AppConfig) { c.QqmusicBaseURL = "http://user:private@gateway.test" },
		func(c *AppConfig) { c.QqmusicBaseURL = "http://gateway.test?token=private" },
		func(c *AppConfig) { c.QqmusicBaseURL = "http://gateway.test#private" },
		func(c *AppConfig) { c.QqmusicQuality = "private" },
		func(c *AppConfig) { c.QqmusicCookie = "private raw cookie" },
		func(c *AppConfig) { c.QqmusicCookie = "null" },
		func(c *AppConfig) { c.QqmusicCookie = "[]" },
	} {
		cfg := DefaultConfig()
		cfg.Sources["qqmusic"] = SourceConfig{true, ServerDownload}
		cfg.QqmusicGatewayMode = "external"
		cfg.QqmusicGatewayToken = "private-token-valid-123"
		update(cfg)
		_, err := cfg.JavaArgs("server.jar")
		if err == nil {
			t.Fatal("invalid QQ config was accepted")
		}
		if strings.Contains(err.Error(), "private") {
			t.Fatal("validation error exposed private settings")
		}
	}
	cfg := DefaultConfig()
	cfg.Sources["qqmusic"] = SourceConfig{true, ServerDownload}
	cfg.QqmusicGatewayMode = "external"
	cfg.QqmusicGatewayToken = "private-token-valid-123"
	if err := cfg.Validate(); err != nil {
		t.Fatal("valid anonymous audio configuration was rejected")
	}
}
