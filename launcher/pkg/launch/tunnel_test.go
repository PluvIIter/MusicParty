package launch

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"launcher/pkg/config"
)

func TestMain(m *testing.M) {
	if os.Getenv("LAUNCHER_FAKE_TUNNEL") == "1" && (strings.HasPrefix(filepath.Base(os.Args[0]), "cloudflared") || strings.HasPrefix(filepath.Base(os.Args[0]), "java")) {
		runTunnelHelper()
		return
	}
	os.Exit(m.Run())
}

func runTunnelHelper() {
	var origin, configuration string
	for index, arg := range os.Args {
		if index+1 < len(os.Args) {
			if arg == "--url" {
				origin = os.Args[index+1]
			}
			if arg == "--config" {
				configuration = os.Args[index+1]
			}
		}
	}
	if origin == "" {
		if path := os.Getenv("LAUNCHER_FAKE_TUNNEL_JAVA"); path != "" {
			data, _ := json.Marshal(os.Args)
			os.WriteFile(path, data, 0600)
		}
		port := ""
		for _, arg := range os.Args {
			if strings.HasPrefix(arg, "--server.port=") {
				port = strings.TrimPrefix(arg, "--server.port=")
			}
		}
		mux := http.NewServeMux()
		mux.HandleFunc("/api/v2/sources", func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, `{"schemaVersion":2,"sources":[]}`) })
		http.ListenAndServe("127.0.0.1:"+port, mux)
		os.Exit(0)
	}
	outputJSON := false
	for index, arg := range os.Args {
		if arg == "--output" && index+1 < len(os.Args) && os.Args[index+1] == "json" {
			outputJSON = true
		}
		if arg == "--logformat" {
			os.Exit(11)
		}
	}
	if !outputJSON {
		os.Exit(12)
	}
	data, err := os.ReadFile(configuration)
	if err != nil || strings.TrimSpace(string(data)) != "{}" {
		os.Exit(9)
	}
	if os.Getenv("TUNNEL_TOKEN") != "" || os.Getenv("TUNNEL_NAME") != "" {
		os.Exit(10)
	}
	if capture := os.Getenv("LAUNCHER_FAKE_TUNNEL_CAPTURE"); capture != "" {
		data, _ := json.Marshal(map[string]string{"origin": origin, "config": configuration})
		os.WriteFile(capture, data, 0600)
	}
	mode := os.Getenv("LAUNCHER_FAKE_TUNNEL_MODE")
	if mode == "exit" {
		os.Exit(3)
	}
	if mode != "nourl" {
		url := "https://fixture-room.trycloudflare.com"
		if mode == "malformed" {
			url = "https://fixture-room.trycloudflare.com.evil.test"
		}
		message, _ := json.Marshal(map[string]string{"message": "|  " + url + "  |"})
		fmt.Fprintln(os.Stderr, string(message))
	}
	if mode != "unconnected" {
		fmt.Fprintln(os.Stderr, `{"message":"Registered tunnel connection","connIndex":0}`)
	}
	if mode == "lateexit" {
		time.Sleep(300 * time.Millisecond)
		os.Exit(4)
	}
	time.Sleep(time.Hour)
	os.Exit(0)
}

func tunnelFixture(t *testing.T) (*Controller, *config.AppConfig, string, string) {
	t.Helper()
	bin := t.TempDir()
	binary, err := os.ReadFile(os.Args[0])
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(bin, executable("cloudflared")), binary, 0755); err != nil {
		t.Fatal(err)
	}
	java := filepath.Join(bin, "jre", "bin", executable("java"))
	os.MkdirAll(filepath.Dir(java), 0755)
	if err := os.WriteFile(java, binary, 0755); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(bin, "server.jar"), []byte("fixture"), 0600)
	controller := NewController(bin, t.TempDir(), nil)
	controller.TunnelTimeout = 600 * time.Millisecond
	t.Setenv("LAUNCHER_FAKE_TUNNEL", "1")
	t.Setenv("TUNNEL_TOKEN", "private-parent-token")
	t.Setenv("TUNNEL_NAME", "unrelated-named-tunnel")
	javaCapture := filepath.Join(t.TempDir(), "java.json")
	capture := filepath.Join(t.TempDir(), "cloud.json")
	t.Setenv("LAUNCHER_FAKE_TUNNEL_JAVA", javaCapture)
	t.Setenv("LAUNCHER_FAKE_TUNNEL_CAPTURE", capture)
	cfg := config.DefaultConfig()
	cfg.Sources["netease"] = config.SourceConfig{Enabled: false, DeliveryMode: config.DirectURL}
	cfg.CloudflareTunnelEnabled = true
	cfg.AdminPassword = "fixture-admin-secure"
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	cfg.ServerPort = strconv.Itoa(listener.Addr().(*net.TCPAddr).Port)
	listener.Close()
	t.Cleanup(controller.Stop)
	return controller, cfg, javaCapture, capture
}

func TestTunnelControllerSuccessRuntimeURLAndStop(t *testing.T) {
	controller, cfg, javaCapture, _ := tunnelFixture(t)
	before, _ := json.Marshal(cfg)
	if err := controller.Start(context.Background(), cfg); err != nil {
		t.Fatal(err)
	}
	status := controller.GetTunnelStatus()
	if status.State != "ready" || status.URL != "https://fixture-room.trycloudflare.com" {
		t.Fatal("tunnel did not report authenticated process connection")
	}
	deadline := time.Now().Add(time.Second)
	var data []byte
	for time.Now().Before(deadline) {
		data, _ = os.ReadFile(javaCapture)
		if len(data) > 0 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !strings.Contains(string(data), "--app.music-api.base-url=https://fixture-room.trycloudflare.com") {
		t.Fatal("runtime public BaseURL not passed to Java")
	}
	after, _ := json.Marshal(cfg)
	if string(before) != string(after) {
		t.Fatal("runtime URL persisted into config")
	}
	controller.Stop()
	if status := controller.GetTunnelStatus(); status.URL != "" || status.State != "stopped" {
		t.Fatal("stop retained public URL")
	}
}

func TestTunnelFailureTimeoutAndCancellation(t *testing.T) {
	for _, mode := range []string{"nourl", "malformed", "unconnected", "exit"} {
		t.Run(mode, func(t *testing.T) {
			controller, cfg, _, _ := tunnelFixture(t)
			t.Setenv("LAUNCHER_FAKE_TUNNEL_MODE", mode)
			if controller.Start(context.Background(), cfg) == nil {
				t.Fatal("invalid tunnel became ready")
			}
			if status := controller.GetTunnelStatus(); status.URL != "" || status.State != "failed" {
				t.Fatal("failed startup retained URL")
			}
		})
	}
	controller, cfg, _, _ := tunnelFixture(t)
	t.Setenv("LAUNCHER_FAKE_TUNNEL_MODE", "nourl")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if controller.Start(ctx, cfg) == nil {
		t.Fatal("canceled parent started services")
	}
	if controller.GetTunnelStatus().State == "starting" {
		t.Fatal("canceled startup remained starting")
	}
	ctx, cancel = context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- controller.Start(ctx, cfg) }()
	time.Sleep(30 * time.Millisecond)
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancellation accepted")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("cancel blocked")
	}
}

func TestGateRejectsOccupiedPortAndDoesNotForwardUntilReady(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	cfg := config.DefaultConfig()
	cfg.ServerIP = "127.0.0.1"
	cfg.ServerPort = strconv.Itoa(listener.Addr().(*net.TCPAddr).Port)
	if _, err := newTunnelGate(cfg); err == nil {
		t.Fatal("occupied origin accepted")
	}
	listener.Close()
	gate, err := newTunnelGate(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer gate.close()
	response, err := http.Get(gate.origin)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 503 {
		t.Fatal("unready gate forwarded")
	}
	cfg.ServerIP = "203.0.113.7"
	if _, err := tunnelOrigin(cfg); err == nil {
		t.Fatal("remote origin accepted")
	}
}

func TestTunnelParserNeedsStrictURLAndRegistration(t *testing.T) {
	for _, bad := range []string{"http://room.trycloudflare.com", "https://room.trycloudflare.com.evil.test", "https://evil.test", "https://room.trycloudflare.com/path", "https://user@room.trycloudflare.com", "https://Room.trycloudflare.com"} {
		events := &tunnelEvents{changed: make(chan struct{}, 1)}
		line, _ := json.Marshal(map[string]string{"message": bad})
		events.line(string(line))
		url, _ := events.snapshot()
		if url != "" {
			t.Fatalf("unsafe URL accepted: %s", bad)
		}
	}
	events := &tunnelEvents{changed: make(chan struct{}, 1)}
	events.line(`{"message":"| https://room.trycloudflare.com |"}`)
	if _, ready := events.snapshot(); ready {
		t.Fatal("allocated URL was mistaken for connection")
	}
	events.line(`{"message":"Registered tunnel connection"}`)
	if url, ready := events.snapshot(); !ready || url != "https://room.trycloudflare.com" {
		t.Fatal("valid connection not recognized")
	}
}

func TestTunnelOriginUsesBindAddressNotPublicBaseURL(t *testing.T) {
	for _, entry := range []struct{ bind, want string }{{"0.0.0.0", "http://127.0.0.1:8080"}, {"::", "http://[::1]:8080"}, {"127.0.0.1", "http://127.0.0.1:8080"}} {
		cfg := config.DefaultConfig()
		cfg.ServerIP = entry.bind
		cfg.BaseURL = "https://public.test"
		origin, err := tunnelOrigin(cfg)
		if err != nil || origin != entry.want {
			t.Fatal("incorrect private origin")
		}
	}
}

func TestTunnelConfigDefaultsOffAndRoundTrips(t *testing.T) {
	cfg, err := config.DecodeConfig([]byte(`{"sources":{}}`))
	if err != nil || cfg.CloudflareTunnelEnabled {
		t.Fatal("tunnel must default off")
	}
	cfg.CloudflareTunnelEnabled = true
	cfg.AdminPassword = "fixture-admin-secure"
	data, _ := json.Marshal(cfg)
	restored, err := config.DecodeConfig(data)
	if err != nil || !restored.CloudflareTunnelEnabled {
		t.Fatal("tunnel option did not round trip")
	}
}
