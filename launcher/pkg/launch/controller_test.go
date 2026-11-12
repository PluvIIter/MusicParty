package launch

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"testing/fstest"
	"time"

	"launcher/pkg/config"
)

func TestLaunchHelper(t *testing.T) {
	if os.Getenv("QQ_LAUNCH_HELPER") != "1" {
		return
	}
	if os.Getenv("QQMUSIC_SERVER_PORT") == "" {
		if capture := os.Getenv("QQ_CAPTURE"); capture != "" {
			data, _ := json.Marshal(os.Args)
			os.WriteFile(capture, data, 0600)
		}
		time.Sleep(time.Hour)
		os.Exit(0)
	}
	if os.Getenv("QQ_HELPER_EXIT") == "1" {
		os.Exit(2)
	}
	mux := http.NewServeMux()
	if !strings.Contains(strings.Join(os.Args, " "), "-I -B") || filepath.Base(os.Args[len(os.Args)-1]) != "run_web.py" {
		os.Exit(8)
	}
	cwd, _ := os.Getwd()
	if filepath.Base(cwd) != "source" {
		os.Exit(9)
	}
	if os.Getenv("QQMUSIC_CREDENTIAL_ENABLED") != "false" || os.Getenv("QQMUSIC_SERVER_WORKERS") != "1" || os.Getenv("QQMUSIC_SERVER_HOST") != "127.0.0.1" || os.Getenv("QQMUSIC_DEFAULT_ACCOUNT") != "" {
		os.Exit(7)
	}
	mux.HandleFunc("/", nativeQqTestHandler)
	http.ListenAndServe("127.0.0.1:"+os.Getenv("QQMUSIC_SERVER_PORT"), mux)
	os.Exit(0)
}

func nativeQqTestHandler(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" {
		w.WriteHeader(403)
		return
	}
	if r.URL.Path == "/" {
		io.WriteString(w, `{"code":0,"msg":"ok","time":"fixture"}`)
		return
	}
	if r.URL.Path == "/openapi.json" {
		paths := make(map[string]any)
		for _, path := range qqWebPaths {
			paths[path] = map[string]any{}
		}
		json.NewEncoder(w).Encode(map[string]any{"info": map[string]string{"title": "QQMusic API"}, "paths": paths})
		return
	}
	w.WriteHeader(404)
}

func fixtureController(t *testing.T) (*Controller, *config.AppConfig, string) {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("POSIX executable fixture; native Windows bundle covered separately")
	}
	bin := t.TempDir()
	helper := "#!/bin/sh\nexec \"" + os.Args[0] + "\" -test.run=^TestLaunchHelper$ -- \"$@\"\n"
	for _, relative := range []string{"qqmusic/python/python", "jre/bin/java"} {
		path := filepath.Join(bin, relative)
		os.MkdirAll(filepath.Dir(path), 0755)
		if err := os.WriteFile(path, []byte(helper), 0755); err != nil {
			t.Fatal(err)
		}
	}
	os.WriteFile(filepath.Join(bin, "server.jar"), []byte("fixture"), 0644)
	for _, name := range []string{"run_web.py", "upstream/source/web/run.py", "upstream/source/web/src/app.py"} {
		path := filepath.Join(bin, "qqmusic", filepath.FromSlash(name))
		os.MkdirAll(filepath.Dir(path), 0755)
		os.WriteFile(path, []byte("fixture"), 0644)
	}
	t.Setenv("QQMUSIC_DEFAULT_ACCOUNT", "must-not-inherit")
	t.Setenv("QQ_LAUNCH_HELPER", "1")
	capture := filepath.Join(t.TempDir(), "java.json")
	t.Setenv("QQ_CAPTURE", capture)
	controller := NewController(bin, t.TempDir(), nil)
	controller.ReadyTimeout = 2 * time.Second
	cfg := config.DefaultConfig()
	cfg.Sources["netease"] = config.SourceConfig{Enabled: false, DeliveryMode: config.DirectURL}
	t.Cleanup(controller.Stop)
	return controller, cfg, capture
}

func TestBundledNativeRuntimeUsesEphemeralPortWithoutChangingStoredConfig(t *testing.T) {
	controller, cfg, capture := fixtureController(t)
	cfg.Sources["qqmusic"] = config.SourceConfig{Enabled: true, DeliveryMode: config.ServerDownload}
	before, _ := json.Marshal(cfg)
	if err := controller.Start(context.Background(), cfg); err != nil {
		t.Fatal(err)
	}
	if !controller.Statuses()["QQMUSIC_API"] {
		t.Fatal("gateway did not become ready")
	}
	deadline := time.Now().Add(2 * time.Second)
	var data []byte
	for time.Now().Before(deadline) {
		data, _ = os.ReadFile(capture)
		if len(data) > 0 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !strings.Contains(string(data), "--app.music-api.qqmusic.base-url=http://127.0.0.1:") {
		t.Fatal("Java did not receive runtime gateway connection")
	}
	after, _ := json.Marshal(cfg)
	if string(before) != string(after) {
		t.Fatal("runtime secrets changed persistent configuration")
	}
	controller.Stop()
	if controller.Statuses()["QQMUSIC_API"] || controller.Statuses()["JAVA_SERVER"] {
		t.Fatal("owned processes survived stop")
	}
}

func TestDisabledAndExternalGatewayDoNotStartLocalQqProcess(t *testing.T) {
	controller, cfg, _ := fixtureController(t)
	if err := controller.Start(context.Background(), cfg); err != nil {
		t.Fatal(err)
	}
	if controller.Statuses()["QQMUSIC_API"] {
		t.Fatal("disabled QQ started")
	}
	controller.Stop()
	server := httptest.NewServer(http.HandlerFunc(nativeQqTestHandler))
	defer server.Close()
	cfg.Sources["qqmusic"] = config.SourceConfig{Enabled: true, DeliveryMode: config.ServerDownload}
	cfg.QqmusicGatewayMode = "external"
	cfg.QqmusicBaseURL = server.URL
	if err := controller.Start(context.Background(), cfg); err != nil {
		t.Fatal(err)
	}
	controller.Stop()
	response, err := http.Get(server.URL + "/")
	if err != nil {
		t.Fatal("external gateway was stopped")
	}
	response.Body.Close()
}

func TestReadinessRejectsProtocolAuthAndExitedProcess(t *testing.T) {
	for _, mode := range []string{"protocol", "auth"} {
		t.Run(mode, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/" {
					if mode == "protocol" {
						io.WriteString(w, `{"code":1,"msg":"error"}`)
					} else {
						io.WriteString(w, `{"code":0,"msg":"ok"}`)
					}
					return
				}
				w.WriteHeader(401)
			}))
			defer server.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
			defer cancel()
			if waitGateway(ctx, &http.Client{Timeout: time.Second}, server.URL, nil) == nil {
				t.Fatal("invalid gateway accepted")
			}
		})
	}
	controller, cfg, _ := fixtureController(t)
	cfg.Sources["qqmusic"] = config.SourceConfig{Enabled: true, DeliveryMode: config.ServerDownload}
	t.Setenv("QQ_HELPER_EXIT", "1")
	if controller.Start(context.Background(), cfg) == nil {
		t.Fatal("exited gateway accepted")
	}
	if controller.Statuses()["JAVA_SERVER"] {
		t.Fatal("Java started before healthy gateway")
	}
}

func TestStopCancelsPendingReadinessAndMissingRuntimeFails(t *testing.T) {
	controller, cfg, _ := fixtureController(t)
	cfg.Sources["qqmusic"] = config.SourceConfig{Enabled: true, DeliveryMode: config.ServerDownload}
	controller.GatewayDir = t.TempDir()
	if controller.Start(context.Background(), cfg) == nil {
		t.Fatal("missing runtime accepted")
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(503) }))
	defer server.Close()
	cfg.QqmusicGatewayMode = "external"
	cfg.QqmusicBaseURL = server.URL
	controller.ReadyTimeout = time.Minute
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		if controller.Start(context.Background(), cfg) == nil {
			t.Error("canceled startup succeeded")
		}
	}()
	time.Sleep(50 * time.Millisecond)
	controller.Stop()
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("stop did not cancel readiness")
	}
}

func TestAssetsReplaceSameLengthFiles(t *testing.T) {
	dir := t.TempDir()
	os.MkdirAll(filepath.Join(dir, "bin"), 0755)
	os.WriteFile(filepath.Join(dir, "bin", "asset"), []byte("old"), 0755)
	if err := ExtractAssets(fstest.MapFS{"bin/asset": &fstest.MapFile{Data: []byte("new")}}, dir); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(filepath.Join(dir, "bin", "asset"))
	if string(data) != "new" {
		t.Fatal("same-size stale asset retained")
	}
}

func TestQqAssetsReplaceWholeOwnedTreeWithoutTouchingDeviceData(t *testing.T) {
	dir := t.TempDir()
	oldFiles := map[string]string{
		"bin/qqmusic/app.py": "old",
		"bin/qqmusic/python/Lib/site-packages/removed_module.py": "obsolete",
		"bin/qqmusic/python/Lib/site-packages/obsolete.pth":      "import removed_module",
		"data/qqmusic-device.json":                               "keep-device-state",
	}
	for relative, value := range oldFiles {
		path := filepath.Join(dir, filepath.FromSlash(relative))
		if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(value), 0600); err != nil {
			t.Fatal(err)
		}
	}
	assets := fstest.MapFS{
		"bin/qqmusic/upstream/source/web/run.py":                 &fstest.MapFile{Data: []byte("new")},
		"bin/qqmusic/upstream/source/web/src/app.py":             &fstest.MapFile{Data: []byte("native")},
		"bin/qqmusic/python/python313._pth":                      &fstest.MapFile{Data: []byte("../upstream/source\nLib/site-packages\n")},
		"bin/qqmusic/python/Lib/site-packages/current_module.py": &fstest.MapFile{Data: []byte("current")},
	}
	if err := ExtractAssets(assets, dir); err != nil {
		t.Fatal(err)
	}
	for _, relative := range []string{"bin/qqmusic/app.py", "bin/qqmusic/python/Lib/site-packages/removed_module.py", "bin/qqmusic/python/Lib/site-packages/obsolete.pth"} {
		if _, err := os.Stat(filepath.Join(dir, filepath.FromSlash(relative))); !os.IsNotExist(err) {
			t.Fatalf("obsolete runtime asset retained: %s", relative)
		}
	}
	for relative, want := range map[string]string{"bin/qqmusic/upstream/source/web/run.py": "new", "bin/qqmusic/python/Lib/site-packages/current_module.py": "current", "data/qqmusic-device.json": "keep-device-state"} {
		data, err := os.ReadFile(filepath.Join(dir, filepath.FromSlash(relative)))
		if err != nil || string(data) != want {
			t.Fatalf("unexpected asset or device data: %s", relative)
		}
	}
	entries, err := os.ReadDir(filepath.Join(dir, "bin"))
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".qqmusic-") {
			t.Fatal("temporary runtime replacement tree remains")
		}
	}
}

func TestWindowsPackagedGatewayReadiness(t *testing.T) {
	bundle := os.Getenv("QQMUSIC_TEST_BUNDLE")
	if bundle == "" {
		t.Skip("native embedded runtime supplied only in Windows CI")
	}
	if runtime.GOOS != "windows" {
		t.Skip("Windows embedded runtime")
	}
	controller := NewController(t.TempDir(), t.TempDir(), nil)
	controller.GatewayDir = bundle
	cfg := config.DefaultConfig()
	cfg.Sources["netease"] = config.SourceConfig{Enabled: false, DeliveryMode: config.DirectURL}
	cfg.Sources["qqmusic"] = config.SourceConfig{Enabled: true, DeliveryMode: config.ServerDownload}
	// Missing Java assets must not hide failure to start the real native Web API.
	err := controller.Start(context.Background(), cfg)
	if err == nil || !strings.Contains(err.Error(), "server.jar is missing") {
		t.Fatalf("native gateway failed before Java: %v", err)
	}
	controller.Stop()
}
