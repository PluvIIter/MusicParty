package config

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestDecodeCanonicalSourceMapWithoutImplicitDefaults(t *testing.T) {
	stored := `{"sources":{"test-source":{"enabled":false,"deliveryMode":"SERVER_DOWNLOAD"}}}`
	cfg, err := DecodeConfig([]byte(stored))
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]SourceConfig{"test-source": {false, ServerDownload}, "qqmusic": {false, DirectURL}}
	if !reflect.DeepEqual(cfg.Sources, want) {
		t.Fatalf("sources = %#v, want %#v", cfg.Sources, want)
	}
	if cfg.ServerPort != "" || cfg.NeteaseQuality != "" {
		t.Fatal("stored settings must not be merged with defaults")
	}
}

func TestCanonicalConfigRoundTrip(t *testing.T) {
	cfg := DefaultConfig()
	cfg.Sources["netease"] = SourceConfig{false, ServerDownload}
	cfg.NeteaseCookie = "private netease"
	cfg.BiliCookie = "private bili"
	cfg.ServerPort = "9000"
	cfg.AuthRateLimitEnabled = false
	cfg.QueueMaxSize = 0
	encoded, err := json.Marshal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	restored, err := DecodeConfig(encoded)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(cfg, restored) {
		t.Fatal("round trip changed canonical configuration")
	}
}

func TestDecodeRejectsUnknownFieldsAndIncompleteSourceConfig(t *testing.T) {
	for _, stored := range []string{
		`{}`, `null`, `{"sources":null}`, `{"sources":{},"unknown":true}`,
		`{"sources":{"test":{"enabled":true}}}`,
		`{"sources":{"test":{"enabled":true,"deliveryMode":"DIRECT_URL","unknown":1}}}`,
		`{"sources":{}} {"sources":{}}`,
	} {
		if _, err := DecodeConfig([]byte(stored)); err == nil {
			t.Fatalf("invalid configuration accepted: %s", stored)
		}
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
		"--app.music-api.sources.qqmusic.enabled=false",
		"--app.music-api.sources.qqmusic.delivery-mode=DIRECT_URL",
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
	sourceArgs := 0
	for _, arg := range args {
		if strings.HasPrefix(arg, "--app.music-api.sources.") {
			sourceArgs++
		}
	}
	if sourceArgs != 2*len(cfg.Sources) {
		t.Fatalf("source argument count = %d", sourceArgs)
	}
}

func TestJavaArgsUseCanonicalAuthProperties(t *testing.T) {
	cfg := DefaultConfig()
	cfg.AuthRateLimitEnabled = false
	cfg.AuthMaxAttempts = 7
	cfg.AuthWindowSeconds = 90
	cfg.AuthBlockDuration = 600
	args, err := cfg.JavaArgs("server.jar")
	if err != nil {
		t.Fatal(err)
	}
	var authArgs []string
	for _, arg := range args {
		if strings.HasPrefix(arg, "--app.music-api.auth.") {
			authArgs = append(authArgs, arg)
		}
	}
	want := []string{
		"--app.music-api.auth.rate-limit-enabled=false",
		"--app.music-api.auth.max-attempts=7",
		"--app.music-api.auth.window-seconds=90",
		"--app.music-api.auth.block-duration-seconds=600",
	}
	if !reflect.DeepEqual(authArgs, want) {
		t.Fatalf("auth args = %#v, want %#v", authArgs, want)
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
	defaults["netease"] = SourceConfig{true, ServerDownload}
	if DefaultSources()["netease"].Enabled {
		t.Fatal("default configurations share mutable state")
	}
	for _, descriptor := range SourceDescriptors() {
		if descriptor.DefaultEnabled {
			t.Fatalf("sources must start disabled: %s", descriptor.ID)
		}
	}
}
