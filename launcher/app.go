package main

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"launcher/pkg/config"
	"launcher/pkg/process"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"

	wails_runtime "github.com/wailsapp/wails/v2/pkg/runtime"
)

//go:embed all:bin
var embeddedBin embed.FS

type App struct {
	ctx     context.Context
	cfg     *config.AppConfig
	manager *process.ServiceManager
}

func NewApp() *App {
	return &App{}
}

func (a *App) LoadConfig() (*config.AppConfig, error) {
	cfg, err := config.LoadConfig()
	if err != nil {
		return nil, err
	}
	a.cfg = cfg
	return cfg, nil
}

func (a *App) SaveConfig(newConfig config.AppConfig) error {
	if err := newConfig.Save(); err != nil {
		return err
	}
	a.cfg = &newConfig
	return nil
}

func (a *App) GetSourceDescriptors() []config.SourceDescriptor {
	return config.SourceDescriptors()
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	// 在后台运行提取，防止阻塞 UI 启动
	go a.extractAssets()
}

func (a *App) extractAssets() {
	baseDir := config.GetBaseDir()

	a.logToTerminal("[SYSTEM] Preparing environment assets...")

	// 递归提取嵌入的 bin 目录及其所有内容
	err := fs.WalkDir(embeddedBin, "bin", func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}

		dest := filepath.Join(baseDir, path)

		if d.IsDir() {
			return os.MkdirAll(dest, 0755)
		}

		// 读取文件内容
		data, err := embeddedBin.ReadFile(path)
		if err != nil {
			a.logToTerminal(fmt.Sprintf("[ERROR] Failed to read embedded %s: %v", path, err))
			return err
		}

		// 只有不存在或大小不一致时才写入
		info, err := os.Stat(dest)
		if err == nil && info.Size() == int64(len(data)) {
			return nil
		}

		err = os.WriteFile(dest, data, 0755)
		if err == nil {
			a.logToTerminal(fmt.Sprintf("[SYSTEM] Extracted %s", path))
		} else {
			a.logToTerminal(fmt.Sprintf("[ERROR] Failed to write %s: %v", path, err))
		}
		return err
	})

	if err != nil {
		a.logToTerminal(fmt.Sprintf("[ERROR] Asset extraction failed: %v", err))
	} else {
		a.logToTerminal("[SYSTEM] Environment ready.")
	}
}

func (a *App) logToTerminal(msg string) {
	if a.ctx != nil {
		wails_runtime.EventsEmit(a.ctx, "log", msg)
	} else {
		fmt.Println(msg)
	}
}

func (a *App) GetServiceStatuses() map[string]bool {
	if a.manager == nil {
		return map[string]bool{
			"NETEASE_API": false,
			"JAVA_SERVER": false,
		}
	}
	return a.manager.GetStatuses()
}

func (a *App) OpenBrowser(url string) {
	var err error
	switch runtime.GOOS {
	case "linux":
		err = exec.Command("xdg-open", url).Start()
	case "windows":
		err = exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
	case "darwin":
		err = exec.Command("open", url).Start()
	default:
		err = fmt.Errorf("unsupported platform")
	}
	if err != nil {
		fmt.Printf("Error opening browser: %v\n", err)
	}
}

func (a *App) StartServices() error {
	if a.cfg == nil {
		return fmt.Errorf("load or save configuration before starting services")
	}
	jarPath := filepath.Join(a.getBinDir(), "server.jar")
	args, err := a.cfg.JavaArgs(jarPath)
	if err != nil {
		return err
	}
	if a.manager != nil {
		a.StopServices()
	}

	a.manager = process.NewServiceManager()

	go func() {
		for logMsg := range a.manager.LogChannel {
			wails_runtime.EventsEmit(a.ctx, "log", logMsg)
		}
	}()

	binDir := a.getBinDir()

	if a.cfg.LocalServiceEnabled("NETEASE_API") {
		apiExe := filepath.Join(binDir, "netease-api.exe")
		if runtime.GOOS != "windows" {
			apiExe = filepath.Join(binDir, "netease-api")
		}
		a.manager.StartProcess("NETEASE_API", apiExe, "-p", "3000")
	}

	// 2. 启动 Java 后端
	javaExe := filepath.Join(binDir, "jre", "bin", "java.exe")
	if _, err := os.Stat(javaExe); err != nil {
		javaExe = "java"
	}

	os.Setenv("PATH", binDir+string(os.PathListSeparator)+os.Getenv("PATH"))
	a.manager.StartProcess("JAVA_SERVER", javaExe, args...)
	return nil
}

func (a *App) getBinDir() string {
	return filepath.Join(config.GetBaseDir(), "bin")
}

func (a *App) StopServices() {
	if a.manager != nil {
		a.manager.StopAll()
	}
}

func (a *App) beforeClose(ctx context.Context) (prevent bool) {
	a.StopServices()
	return false
}
