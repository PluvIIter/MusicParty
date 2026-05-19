package config

import (
	"encoding/json"
	"os"
	"path/filepath"
)

type AppConfig struct {
	// Server Settings
	ServerIP   string `json:"serverIp"`
	ServerPort string `json:"serverPort"`

	// App General
	AdminPassword string `json:"adminPassword"`
	AuthorName    string `json:"authorName"`
	BackWords     string `json:"backWords"`

	// Netease
	NeteaseCookie string `json:"neteaseCookie"`
	NeteaseQuality string `json:"neteaseQuality"`

	// Bilibili
	BiliSessData string `json:"biliSessData"`

	// Queue Settings
	QueueMaxSize      int `json:"queueMaxSize"`
	QueueHistorySize  int `json:"queueHistorySize"`
	QueueMaxUserSongs int `json:"queueMaxUserSongs"`

	// Player Settings
	MaxPlaylistImportSize int `json:"maxPlaylistImportSize"`

	// Chat Settings
	ChatMaxHistorySize int `json:"chatMaxHistorySize"`
	ChatMinIntervalMs  int `json:"chatMinIntervalMs"`
	ChatMaxMessageLength int `json:"chatMaxMessageLength"`

	// Cache Settings
	CacheMaxSize string `json:"cacheMaxSize"`

	// Auth Settings
	AuthRateLimitEnabled bool `json:"authRateLimitEnabled"`
	AuthMaxAttempts      int  `json:"authMaxAttempts"`
	AuthWindowSeconds    int  `json:"authWindowSeconds"`
	AuthBlockDuration    int  `json:"authBlockDuration"`

	// Launcher Settings
	AutoStart  bool   `json:"autoStart"`
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
			ServerPort:    "8080",
			AdminPassword: "",
			AuthorName:    "ThorNex",
			BackWords:     "THORNEX",
			NeteaseQuality: "exhigh",
			QueueMaxSize:      1000,
			QueueHistorySize:  50,
			QueueMaxUserSongs: 100,
			MaxPlaylistImportSize: 100,
			ChatMaxHistorySize: 1000,
			ChatMinIntervalMs:  1000,
			ChatMaxMessageLength: 200,
			CacheMaxSize: "1GB",
			AuthRateLimitEnabled: true,
			AuthMaxAttempts:      5,
			AuthWindowSeconds:    60,
			AuthBlockDuration:    300,
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
