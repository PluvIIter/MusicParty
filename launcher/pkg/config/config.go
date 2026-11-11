package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
)

type SourceConfig struct {
	Enabled      bool   `json:"enabled"`
	DeliveryMode string `json:"deliveryMode"`
}

type AppConfig struct {
	ServerIP      string `json:"serverIp"`
	ServerPort    string `json:"serverPort"`
	BaseURL       string `json:"baseUrl"`
	AdminPassword string `json:"adminPassword"`
	AuthorName    string `json:"authorName"`
	BackWords     string `json:"backWords"`

	Sources        map[string]SourceConfig `json:"sources"`
	NeteaseCookie  string                  `json:"neteaseCookie"`
	NeteaseQuality string                  `json:"neteaseQuality"`
	BiliCookie     string                  `json:"biliCookie"`

	QueueMaxSize          int    `json:"queueMaxSize"`
	QueueHistorySize      int    `json:"queueHistorySize"`
	QueueMaxUserSongs     int    `json:"queueMaxUserSongs"`
	MaxPlaylistImportSize int    `json:"maxPlaylistImportSize"`
	ChatMaxHistorySize    int    `json:"chatMaxHistorySize"`
	ChatMinIntervalMs     int    `json:"chatMinIntervalMs"`
	ChatMaxMessageLength  int    `json:"chatMaxMessageLength"`
	CacheMaxSize          string `json:"cacheMaxSize"`
	AuthRateLimitEnabled  bool   `json:"authRateLimitEnabled"`
	AuthMaxAttempts       int    `json:"authMaxAttempts"`
	AuthWindowSeconds     int    `json:"authWindowSeconds"`
	AuthBlockDuration     int    `json:"authBlockDuration"`
	AutoStart             bool   `json:"autoStart"`
}

func DefaultConfig() *AppConfig {
	return &AppConfig{
		ServerIP: "0.0.0.0", ServerPort: "8080", BaseURL: "http://localhost:8080",
		AuthorName: "ThorNex", BackWords: "THORNEX", NeteaseQuality: "exhigh",
		Sources: DefaultSources(), QueueMaxSize: 1000, QueueHistorySize: 50,
		QueueMaxUserSongs: 100, MaxPlaylistImportSize: 100, ChatMaxHistorySize: 1000,
		ChatMinIntervalMs: 1000, ChatMaxMessageLength: 200, CacheMaxSize: "1GB",
		AuthRateLimitEnabled: true, AuthMaxAttempts: 5, AuthWindowSeconds: 60,
		AuthBlockDuration: 300,
	}
}

func (c *AppConfig) UnmarshalJSON(data []byte) error {
	type plain AppConfig
	decoded := plain(*DefaultConfig())
	var stored struct {
		*plain
		NeteaseEnabled  *bool `json:"neteaseEnabled"`
		BilibiliEnabled *bool `json:"bilibiliEnabled"`
		Sources         map[string]struct {
			Enabled      *bool  `json:"enabled"`
			DeliveryMode string `json:"deliveryMode"`
		} `json:"sources"`
	}
	stored.plain = &decoded
	if err := json.Unmarshal(data, &stored); err != nil {
		return err
	}
	if stored.NeteaseEnabled != nil {
		source := decoded.Sources["netease"]
		source.Enabled = *stored.NeteaseEnabled
		decoded.Sources["netease"] = source
	}
	if stored.BilibiliEnabled != nil {
		source := decoded.Sources["bilibili"]
		source.Enabled = *stored.BilibiliEnabled
		decoded.Sources["bilibili"] = source
	}
	for id, override := range stored.Sources {
		source, exists := decoded.Sources[id]
		if !exists {
			source.DeliveryMode = DirectURL
		}
		if override.Enabled != nil {
			source.Enabled = *override.Enabled
		}
		if override.DeliveryMode != "" {
			source.DeliveryMode = override.DeliveryMode
		}
		decoded.Sources[id] = source
	}
	*c = AppConfig(decoded)
	return nil
}

var sourceIDPattern = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)

func (c *AppConfig) Validate() error {
	for _, id := range c.SourceIDs() {
		source := c.Sources[id]
		if !sourceIDPattern.MatchString(id) {
			return fmt.Errorf("invalid source id: %q", id)
		}
		if source.DeliveryMode != DirectURL && source.DeliveryMode != ServerDownload {
			return fmt.Errorf("invalid delivery mode for %s: %q", id, source.DeliveryMode)
		}
	}
	return nil
}

func (c *AppConfig) SourceIDs() []string {
	ids := make([]string, 0, len(c.Sources))
	for id := range c.Sources {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return ids
}

func (c *AppConfig) SourceEnabled(id string) bool {
	return c.Sources[id].Enabled
}

func GetBaseDir() string {
	exePath, err := os.Executable()
	if err == nil {
		return filepath.Dir(exePath)
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".musicparty")
}

func GetConfigPath() string {
	return filepath.Join(GetBaseDir(), "launcher_config.json")
}

func LoadConfig() *AppConfig {
	data, err := os.ReadFile(GetConfigPath())
	if err != nil {
		return DefaultConfig()
	}
	var cfg AppConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
		return DefaultConfig()
	}
	return &cfg
}

func (c *AppConfig) Save() error {
	if err := c.Validate(); err != nil {
		return err
	}
	path := GetConfigPath()
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(path, data, 0600); err != nil {
		return err
	}
	return os.Chmod(path, 0600)
}
