package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
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

	Sources            map[string]SourceConfig `json:"sources"`
	NeteaseCookie      string                  `json:"neteaseCookie"`
	NeteaseQuality     string                  `json:"neteaseQuality"`
	BiliCookie         string                  `json:"biliCookie"`

	QqmusicCookie      string                  `json:"qqmusicCookie"`
	QqmusicQuality     string                  `json:"qqmusicQuality"`

	QueueMaxSize            int    `json:"queueMaxSize"`
	QueueHistorySize        int    `json:"queueHistorySize"`
	QueueMaxUserSongs       int    `json:"queueMaxUserSongs"`
	MaxPlaylistImportSize   int    `json:"maxPlaylistImportSize"`
	ChatMaxHistorySize      int    `json:"chatMaxHistorySize"`
	ChatMinIntervalMs       int    `json:"chatMinIntervalMs"`
	ChatMaxMessageLength    int    `json:"chatMaxMessageLength"`
	CacheMaxSize            string `json:"cacheMaxSize"`
	AuthRateLimitEnabled    bool   `json:"authRateLimitEnabled"`
	AuthMaxAttempts         int    `json:"authMaxAttempts"`
	AuthWindowSeconds       int    `json:"authWindowSeconds"`
	AuthBlockDuration       int    `json:"authBlockDuration"`
	AutoStart               bool   `json:"autoStart"`
	CloudflareTunnelEnabled bool   `json:"cloudflareTunnelEnabled"`
}

func DefaultConfig() *AppConfig {
	return &AppConfig{
		ServerIP: "0.0.0.0", ServerPort: "8080", BaseURL: "http://localhost:8080",
		AuthorName: "ThorNex", BackWords: "THORNEX", NeteaseQuality: "exhigh",
		QqmusicQuality: "MP3_320",
		Sources: DefaultSources(), QueueMaxSize: 1000, QueueHistorySize: 50,
		QueueMaxUserSongs: 100, MaxPlaylistImportSize: 100, ChatMaxHistorySize: 1000,
		ChatMinIntervalMs: 1000, ChatMaxMessageLength: 200, CacheMaxSize: "1GB",
		AuthRateLimitEnabled: true, AuthMaxAttempts: 5, AuthWindowSeconds: 60,
		AuthBlockDuration: 300,
	}
}

// 旧版启动器允许把 QQ 上游切成外部 API（qqmusicGatewayMode / qqmusicBaseUrl）。
// 现在固定使用随包内置的那一份，读取时静默丢弃这两个键，避免老配置因严格解码而报错。
func dropLegacyQqGatewayKeys(data []byte) ([]byte, error) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}
	_, hasMode := raw["qqmusicGatewayMode"]
	_, hasURL := raw["qqmusicBaseUrl"]
	if !hasMode && !hasURL {
		return data, nil
	}
	delete(raw, "qqmusicGatewayMode")
	delete(raw, "qqmusicBaseUrl")
	return json.Marshal(raw)
}

func DecodeConfig(data []byte) (*AppConfig, error) {
	filtered, err := dropLegacyQqGatewayKeys(data)
	if err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(bytes.NewReader(filtered))
	decoder.DisallowUnknownFields()
	cfg := AppConfig{QqmusicQuality: "MP3_320"}
	if err := decoder.Decode(&cfg); err != nil {
		return nil, err
	}
	if err := decoder.Decode(new(any)); !errors.Is(err, io.EOF) {
		return nil, fmt.Errorf("configuration must contain one JSON object")
	}
	if cfg.Sources != nil {
		if _, exists := cfg.Sources["qqmusic"]; !exists {
			cfg.Sources["qqmusic"] = DefaultSources()["qqmusic"]
		}
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return &cfg, nil
}

var sourceIDPattern = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)

func (c *AppConfig) Validate() error {
	if c.Sources == nil {
		return fmt.Errorf("sources configuration is required")
	}
	for _, id := range c.SourceIDs() {
		source := c.Sources[id]
		if !sourceIDPattern.MatchString(id) {
			return fmt.Errorf("invalid source id: %q", id)
		}
		if source.DeliveryMode != DirectURL && source.DeliveryMode != ServerDownload {
			return fmt.Errorf("invalid delivery mode for %s: %q", id, source.DeliveryMode)
		}
	}
	if c.SourceEnabled("qqmusic") {
		if c.QqmusicQuality != "MP3_128" && c.QqmusicQuality != "MP3_320" && c.QqmusicQuality != "FLAC" {
			return fmt.Errorf("unsupported QQ audio quality")
		}
		if err := validateQqCredential(c.QqmusicCookie); err != nil {
			return err
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

func LoadConfig() (*AppConfig, error) {
	data, err := os.ReadFile(GetConfigPath())
	if errors.Is(err, os.ErrNotExist) {
		return DefaultConfig(), nil
	}
	if err != nil {
		return nil, err
	}
	return DecodeConfig(data)
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
