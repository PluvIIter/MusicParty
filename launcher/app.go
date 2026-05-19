package main

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"launcher/pkg/config"
	"launcher/pkg/process"
	"os"
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
	return &App{
		cfg: config.LoadConfig(),
	}
}

func (a *App) LoadConfig() *config.AppConfig {
	a.cfg = config.LoadConfig()
	return a.cfg
}

func (a *App) SaveConfig(newConfig config.AppConfig) error {
	a.cfg = &newConfig
	return a.cfg.Save()
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	a.extractAssets()
}

func (a *App) extractAssets() {
	home, _ := os.UserHomeDir()
	baseDir := filepath.Join(home, ".musicparty")

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
			return err
		}

		// 只有不存在或大小不一致时才写入
		info, err := os.Stat(dest)
		if err == nil && info.Size() == int64(len(data)) {
			return nil
		}

		err = os.WriteFile(dest, data, 0755)
		if err == nil {
			fmt.Printf("Extracted %s\n", path)
		}
		return err
	})

	if err != nil {
		fmt.Printf("Error extracting assets: %v\n", err)
	}
}

func (a *App) StartServices() {
	a.manager = process.NewServiceManager()

	go func() {
		for logMsg := range a.manager.LogChannel {
			wails_runtime.EventsEmit(a.ctx, "log", logMsg)
		}
	}()

	binDir := a.getBinDir()

	// 1. 启动 Netease API
	apiExe := filepath.Join(binDir, "netease-api.exe")
	if runtime.GOOS != "windows" {
		apiExe = filepath.Join(binDir, "netease-api")
	}
	a.manager.StartProcess("NETEASE_API", apiExe, "-p", "3000")

	// 2. 启动 Java 后端
	// 优先使用内置 JRE
	javaExe := filepath.Join(binDir, "jre", "bin", "java.exe")
	if _, err := os.Stat(javaExe); err != nil {
		javaExe = "java" // Fallback to system java
	}
	
	jarPath := filepath.Join(binDir, "server.jar")
	
	// 设置 FFmpeg 路径环境变量
	os.Setenv("PATH", binDir+string(os.PathListSeparator)+os.Getenv("PATH"))

	args := []string{
		"-jar", jarPath,
		fmt.Sprintf("--server.address=%s", a.cfg.ServerIP),
		fmt.Sprintf("--server.port=%s", a.cfg.ServerPort),
		fmt.Sprintf("--app.admin-password=%s", a.cfg.AdminPassword),
		fmt.Sprintf("--app.api.netease-url=http://127.0.0.1:3000"),
		fmt.Sprintf("--app.netease-cookie=%s", a.cfg.NeteaseCookie),
		fmt.Sprintf("--app.bilibili-sessdata=%s", a.cfg.BiliSessData),
	}

	a.manager.StartProcess("JAVA_SERVER", javaExe, args...)
}

func (a *App) getBinDir() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".musicparty", "bin")
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
