package config

const (
	DirectURL      = "DIRECT_URL"
	ServerDownload = "SERVER_DOWNLOAD"
)

type ConnectionOption struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

type ConnectionField struct {
	Key     string             `json:"key"`
	Label   string             `json:"label"`
	Kind    string             `json:"kind"`
	Options []ConnectionOption `json:"options,omitempty"`
}

type SourceDescriptor struct {
	ID               string            `json:"id"`
	Label            string            `json:"label"`
	SupportedModes   []string          `json:"supportedModes"`
	DefaultMode      string            `json:"defaultMode"`
	DefaultEnabled   bool              `json:"defaultEnabled"`
	ConnectionFields []ConnectionField `json:"connectionFields"`
	LocalService     string            `json:"localService,omitempty"`
}

// The launcher needs adapter defaults before the backend is running.
// 每个音源只暴露「Cookie + 参数」这类配置；QQ 音乐与网易云保持一致，
// 上游服务固定使用随包内置的那一份，不再提供外部 API 选项。
func SourceDescriptors() []SourceDescriptor {
	return []SourceDescriptor{
		{
			ID: "netease", Label: "网易云音乐", DefaultMode: DirectURL, DefaultEnabled: false,
			SupportedModes: []string{DirectURL, ServerDownload}, LocalService: "NETEASE_API",
			ConnectionFields: []ConnectionField{
				{Key: "neteaseCookie", Label: "网易云账号 Cookie", Kind: "password"},
				{Key: "neteaseQuality", Label: "解析音质上限", Kind: "select", Options: []ConnectionOption{
					{Value: "standard", Label: "标准"}, {Value: "higher", Label: "较高"},
					{Value: "exhigh", Label: "极高"}, {Value: "lossless", Label: "无损"},
					{Value: "hires", Label: "高解析度"},
				}},
			},
		},
		{
			ID: "qqmusic", Label: "QQ 音乐", DefaultMode: DirectURL, DefaultEnabled: false,
			SupportedModes: []string{DirectURL, ServerDownload}, LocalService: "QQMUSIC_API",
			ConnectionFields: []ConnectionField{
				{Key: "qqmusicCookie", Label: "QQ 音乐 Cookie", Kind: "password"},
				{Key: "qqmusicQuality", Label: "音频音质", Kind: "select", Options: []ConnectionOption{
					{Value: "MP3_128", Label: "MP3 128 kbps"}, {Value: "MP3_320", Label: "MP3 320 kbps"},
					{Value: "FLAC", Label: "FLAC 无损"},
				}},
			},
		},
		{
			ID: "bilibili", Label: "Bilibili", DefaultMode: ServerDownload, DefaultEnabled: false,
			SupportedModes: []string{DirectURL, ServerDownload},
			ConnectionFields: []ConnectionField{
				{Key: "biliCookie", Label: "Bilibili 账号 Cookie", Kind: "password"},
			},
		},
	}
}

func DefaultSources() map[string]SourceConfig {
	sources := make(map[string]SourceConfig)
	for _, descriptor := range SourceDescriptors() {
		sources[descriptor.ID] = SourceConfig{Enabled: descriptor.DefaultEnabled, DeliveryMode: descriptor.DefaultMode}
	}
	return sources
}

func (c *AppConfig) LocalServiceEnabled(name string) bool {
	if name == "" {
		return false
	}
	for _, descriptor := range SourceDescriptors() {
		if descriptor.LocalService == name && c.SourceEnabled(descriptor.ID) {
			return true
		}
	}
	return false
}
