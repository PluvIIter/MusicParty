package main

import (
	"context"
	"embed"
	"fmt"
	"launcher/pkg/config"
	"launcher/pkg/process"
	"os"
	"path/filepath"
	"runtime"

	wails_runtime "github.com/wailsapp/wails/v2/pkg/runtime"
)

//go:embed bin/*
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
	binDir := filepath.Join(home, ".musicparty", "bin")
	os.MkdirAll(binDir, 0755)

	// 提取嵌入的二进制文件 (server.jar, netease-api.exe, ffmpeg.exe)
	entries, _ := embeddedBin.ReadDir("bin")
	for _, entry := range entries {
		path := filepath.Join("bin", entry.Name())
		data, _ := embeddedBin.ReadFile(path)
		dest := filepath.Join(binDir, entry.Name())
		
		// 只有不存在或大小不一致时才覆盖，加快启动
		info, err := os.Stat(dest)
		if err != nil || info.Size() != int64(len(data)) {
			os.WriteFile(dest, data, 0755)
			fmt.Printf("Extracted %s to %s\n", entry.Name(), dest)
		}
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
