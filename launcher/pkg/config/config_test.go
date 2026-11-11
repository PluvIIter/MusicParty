package config

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestStoredConfigMigration(t *testing.T) {
	tests := []struct {
		name string
		json string
		want map[string]SourceConfig
	}{
		{"missing legacy flags retain defaults", `{}`, DefaultSources()},
		{"legacy disabled flags", `{"neteaseEnabled":false,"bilibiliEnabled":false}`, map[string]SourceConfig{
			"netease": {false, DirectURL}, "bilibili": {false, ServerDownload},
		}},
		{"source overrides take precedence", `{"neteaseEnabled":false,"sources":{"netease":{"enabled":true,"deliveryMode":"SERVER_DOWNLOAD"},"bilibili":{"enabled":false,"deliveryMode":"DIRECT_URL"}}}`, map[string]SourceConfig{
			"netease": {true, ServerDownload}, "bilibili": {false, DirectURL},
		}},
		{"partial source overrides inherit adapter defaults", `{"neteaseEnabled":false,"sources":{"netease":{"deliveryMode":"SERVER_DOWNLOAD"},"bilibili":{"enabled":false}}}`, map[string]SourceConfig{
			"netease": {false, ServerDownload}, "bilibili": {false, ServerDownload},
		}},
		{"future source remains generic", `{"sources":{"test-source":{"enabled":true,"deliveryMode":"SERVER_DOWNLOAD"}}}`, map[string]SourceConfig{
			"netease": {true, DirectURL}, "bilibili": {true, ServerDownload}, "test-source": {true, ServerDownload},
		}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var cfg AppConfig
			if err := json.Unmarshal([]byte(tt.json), &cfg); err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(cfg.Sources, tt.want) {
				t.Fatalf("sources = %#v, want %#v", cfg.Sources, tt.want)
			}
			if cfg.ServerPort != "8080" || cfg.NeteaseQuality != "exhigh" {
				t.Fatal("missing settings did not retain defaults")
			}
		})
	}
}

func TestMigrationRoundTripPreservesSettingsWithoutLegacyFlags(t *testing.T) {
	var cfg AppConfig
	stored := `{"neteaseEnabled":false,"bilibiliEnabled":true,"neteaseCookie":"private netease","biliCookie":"private bili","neteaseQuality":"lossless","serverPort":"9000","authRateLimitEnabled":false,"queueMaxSize":0}`
	if err := json.Unmarshal([]byte(stored), &cfg); err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), "neteaseEnabled") || strings.Contains(string(encoded), "bilibiliEnabled") {
		t.Fatal("migrated configuration still writes legacy enabled flags")
	}
	var restored AppConfig
	if err := json.Unmarshal(encoded, &restored); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(cfg, restored) {
		t.Fatalf("round trip changed configuration: %#v", restored)
	}
	if restored.NeteaseCookie != "private netease" || restored.BiliCookie != "private bili" || restored.ServerPort != "9000" || restored.AuthRateLimitEnabled || restored.QueueMaxSize != 0 {
		t.Fatal("migration changed explicit values or credentials")
	}
}

func TestInvalidConfigIsRejected(t *testing.T) {
	for _, source := range []struct{ id, mode string }{
		{"bad.source", DirectURL}, {"UPPER", DirectURL}, {"", DirectURL}, {"test", "AUTOMATIC"},
	} {
		cfg := DefaultConfig()
		cfg.Sources[source.id] = SourceConfig{true, source.mode}
		if _, err := cfg.JavaArgs("server.jar"); err == nil {
			t.Fatalf("invalid source accepted: %#v", source)
		}
	}
}

func TestJavaArgsUseGenericSourceProperties(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Sources["netease"] = SourceConfig{false, ServerDownload}
	cfg.Sources["bilibili"] = SourceConfig{true, DirectURL}
	cfg.Sources["test-source"] = SourceConfig{true, ServerDownload}
	cfg.NeteaseCookie = "token=one two; value=三"
	cfg.BiliCookie = "SESSDATA=private"
	args, err := cfg.JavaArgs("path with spaces/server.jar")
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"--app.music-api.sources.bilibili.enabled=true",
		"--app.music-api.sources.bilibili.delivery-mode=DIRECT_URL",
		"--app.music-api.sources.netease.enabled=false",
		"--app.music-api.sources.netease.delivery-mode=SERVER_DOWNLOAD",
		"--app.music-api.sources.test-source.enabled=true",
		"--app.music-api.sources.test-source.delivery-mode=SERVER_DOWNLOAD",
	}
	if !reflect.DeepEqual(args[len(args)-len(want):], want) {
		t.Fatalf("source args = %#v, want %#v", args, want)
	}
	if args[0] != "-jar" || args[1] != "path with spaces/server.jar" {
		t.Fatal("jar path was split or changed")
	}
	for _, expected := range []string{"--app.music-api.netease.cookie=" + cfg.NeteaseCookie, "--app.music-api.bilibili.cookie=" + cfg.BiliCookie} {
		found := false
		for _, arg := range args {
			found = found || arg == expected
		}
		if !found {
			t.Fatal("adapter credential was split or changed")
		}
	}
	for _, arg := range args {
		if strings.HasPrefix(arg, "--app.music-api.netease.enabled=") || strings.HasPrefix(arg, "--app.music-api.bilibili.enabled=") {
			t.Fatal("legacy source args still emitted")
		}
	}
}

func TestLocalSidecarRequiresEnabledAdapter(t *testing.T) {
	cfg := DefaultConfig()
	if !cfg.LocalServiceEnabled("NETEASE_API") {
		t.Fatal("enabled Netease should start its sidecar")
	}
	cfg.Sources["netease"] = SourceConfig{false, ServerDownload}
	if cfg.LocalServiceEnabled("NETEASE_API") {
		t.Fatal("disabled Netease must not start its sidecar")
	}
	if cfg.LocalServiceEnabled("BILIBILI_API") || cfg.LocalServiceEnabled("") {
		t.Fatal("unsupported sidecar should not be started")
	}
	delete(cfg.Sources, "netease")
	if cfg.LocalServiceEnabled("NETEASE_API") {
		t.Fatal("absent adapter must not start its sidecar")
	}
}

func TestDescriptorManifestDeclaresDefaultsAndFieldsOnly(t *testing.T) {
	encoded, err := json.Marshal(SourceDescriptors())
	if err != nil {
		t.Fatal(err)
	}
	var descriptors []map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &descriptors); err != nil {
		t.Fatal(err)
	}
	for _, descriptor := range descriptors {
		for _, forbidden := range []string{"cookie", "neteaseCookie", "biliCookie", "credentials", "enabled", "capabilities"} {
			if _, exists := descriptor[forbidden]; exists {
				t.Fatalf("manifest includes configuration state: %s", forbidden)
			}
		}
	}
	for _, descriptor := range SourceDescriptors() {
		found := false
		for _, mode := range descriptor.SupportedModes {
			found = found || mode == descriptor.DefaultMode
		}
		if !found {
			t.Fatalf("default mode unsupported: %s", descriptor.ID)
		}
	}
	defaults := DefaultSources()
	defaults["netease"] = SourceConfig{false, ServerDownload}
	if !DefaultSources()["netease"].Enabled {
		t.Fatal("default configurations share mutable state")
	}
}
