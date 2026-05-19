package config

import (
	"encoding/json"
	"os"
	"path/filepath"
)

type AppConfig struct {
	ServerIP       string `json:"serverIp"`
	ServerPort     string `json:"serverPort"`
	AdminPassword  string `json:"adminPassword"`
	NeteaseCookie  string `json:"neteaseCookie"`
	BiliSessData   string `json:"biliSessData"`
	AutoStart      bool   `json:"autoStart"`
	FFmpegPath     string `json:"ffmpegPath"`
}

func GetConfigPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".musicparty", "launcher_config.json")
}

func LoadConfig() *AppConfig {
	path := GetConfigPath()
	data, err := os.ReadFile(path)
	if err != nil {
		return &AppConfig{
			ServerIP:      "0.0.0.0",
			ServerPort:    "8848",
			AdminPassword: "admin",
			AutoStart:     false,
		}
	}
	var cfg AppConfig
	json.Unmarshal(data, &cfg)
	return &cfg
}

func (c *AppConfig) Save() error {
	path := GetConfigPath()
	os.MkdirAll(filepath.Dir(path), 0755)
	data, _ := json.MarshalIndent(c, "", "  ")
	return os.WriteFile(path, data, 0644)
}
