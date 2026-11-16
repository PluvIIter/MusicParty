package main

import (
	"embed"
	"os"
	"path/filepath"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

//go:embed all:frontend/dist
var assets embed.FS

func main() {
	// WebView2 在包含中文等非 ASCII 字符的目录下创建窗口会失败（0x800700AA），
	// 表现为「双击没反应」。这里提前拦下并给出可操作的提示。
	if directory, err := executableDirectory(); err == nil && !isASCII(directory) {
		notifyDialog(
			"程序所在路径包含中文或其它非英文字符，WebView2 无法创建窗口，所以启动后没有任何反应。\r\n\r\n"+
				"请把 MusicParty.exe 放到纯英文路径（例如 D:\\MusicParty\\）后重新运行。",
			"MusicParty")
		return
	}

	// Create an instance of the app structure
	app := NewApp()

	// Create application with options
	err := wails.Run(&options.App{
		Title:  "MusicParty",
		Width:  1024,
		Height: 720,
		AssetServer: &assetserver.Options{
			Assets: assets,
		},
		BackgroundColour: &options.RGBA{R: 248, G: 250, B: 252, A: 1},
		OnStartup:        app.startup,
		OnBeforeClose:    app.beforeClose,
		Bind: []interface{}{
			app,
		},
	})

	if err != nil {
		println("Error:", err.Error())
	}
}

func executableDirectory() (string, error) {
	path, err := os.Executable()
	if err != nil {
		return "", err
	}
	return filepath.Dir(path), nil
}

func isASCII(value string) bool {
	for _, character := range value {
		if character > 127 {
			return false
		}
	}
	return true
}
