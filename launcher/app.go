package main

import (
	"context"
	"embed"
	"fmt"
	"launcher/pkg/config"
	"launcher/pkg/launch"
	"os/exec"
	"path/filepath"
	"runtime"
	"sync"

	wails_runtime "github.com/wailsapp/wails/v2/pkg/runtime"
)

//go:embed all:bin
var embeddedBin embed.FS

type App struct {
	ctx         context.Context
	mu          sync.Mutex
	cfg         *config.AppConfig
	controller  *launch.Controller
	assetsDone  chan struct{}
	assetsErr   error
	startCancel context.CancelFunc
}

func NewApp() *App { return &App{} }

func (a *App) LoadConfig() (*config.AppConfig, error) {
	cfg, err := config.LoadConfig()
	if err != nil {
		return nil, err
	}
	a.mu.Lock()
	a.cfg = cfg
	a.mu.Unlock()
	return cfg, nil
}

func (a *App) SaveConfig(cfg config.AppConfig) error {
	if err := cfg.Save(); err != nil {
		return err
	}
	a.mu.Lock()
	a.cfg = &cfg
	a.mu.Unlock()
	return nil
}

func (a *App) GetSourceDescriptors() []config.SourceDescriptor { return config.SourceDescriptors() }

func (a *App) startup(ctx context.Context) {
	a.mu.Lock()
	a.ctx = ctx
	a.assetsDone = make(chan struct{})
	a.controller = launch.NewController(filepath.Join(config.GetBaseDir(), "bin"), filepath.Join(config.GetBaseDir(), "data"), a.logToTerminal)
	a.mu.Unlock()
	go func() {
		err := launch.ExtractAssets(embeddedBin, config.GetBaseDir())
		a.mu.Lock()
		a.assetsErr = err
		close(a.assetsDone)
		a.mu.Unlock()
		if err != nil {
			a.logToTerminal("[ERROR] Bundled assets could not be prepared")
		} else {
			a.logToTerminal("[SYSTEM] Environment ready")
		}
	}()
}

func (a *App) logToTerminal(message string) {
	a.mu.Lock()
	ctx := a.ctx
	a.mu.Unlock()
	if ctx != nil {
		wails_runtime.EventsEmit(ctx, "log", message)
	}
}

func (a *App) GetServiceStatuses() map[string]bool {
	a.mu.Lock()
	controller := a.controller
	a.mu.Unlock()
	if controller == nil {
		return map[string]bool{"NETEASE_API": false, "JAVA_SERVER": false, "QQMUSIC_API": false}
	}
	return controller.Statuses()
}

func (a *App) GetTunnelStatus() launch.TunnelStatus {
	a.mu.Lock()
	controller := a.controller
	a.mu.Unlock()
	if controller == nil {
		return launch.TunnelStatus{State: "off"}
	}
	return controller.GetTunnelStatus()
}

func (a *App) OpenBrowser(url string) {
	var command *exec.Cmd
	switch runtime.GOOS {
	case "linux":
		command = exec.Command("xdg-open", url)
	case "windows":
		command = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		command = exec.Command("open", url)
	default:
		return
	}
	if command.Start() != nil {
		a.logToTerminal("[ERROR] Cannot open browser")
	}
}

func (a *App) StartServices() error {
	a.mu.Lock()
	if a.cfg == nil || a.controller == nil || a.assetsDone == nil {
		a.mu.Unlock()
		return fmt.Errorf("load configuration before starting services")
	}
	if a.startCancel != nil {
		a.mu.Unlock()
		return fmt.Errorf("service startup is already in progress")
	}
	ctx, cancel := context.WithCancel(a.ctx)
	a.startCancel = cancel
	controller, done, cfg := a.controller, a.assetsDone, *a.cfg
	a.mu.Unlock()
	defer func() { a.mu.Lock(); a.startCancel = nil; a.mu.Unlock() }()
	select {
	case <-ctx.Done():
		return fmt.Errorf("service startup canceled")
	case <-done:
	}
	a.mu.Lock()
	err := a.assetsErr
	a.mu.Unlock()
	if err != nil {
		return fmt.Errorf("bundled assets could not be prepared")
	}
	return controller.Start(ctx, &cfg)
}

func (a *App) StopServices() {
	a.mu.Lock()
	if a.startCancel != nil {
		a.startCancel()
	}
	controller := a.controller
	a.mu.Unlock()
	if controller != nil {
		controller.Stop()
	}
}

func (a *App) beforeClose(context.Context) bool { a.StopServices(); return false }
