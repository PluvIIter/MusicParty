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
	Key         string             `json:"key"`
	Label       string             `json:"label"`
	Placeholder string             `json:"placeholder"`
	Kind        string             `json:"kind"`
	Options     []ConnectionOption `json:"options,omitempty"`
}

type SourceDescriptor struct {
	ID               string            `json:"id"`
	Label            string            `json:"label"`
	SupportedModes   []string          `json:"supportedModes"`
	DefaultMode      string            `json:"defaultMode"`
	ConnectionFields []ConnectionField `json:"connectionFields"`
	LocalService     string            `json:"localService,omitempty"`
}

// The launcher needs adapter defaults before the backend is running.
func SourceDescriptors() []SourceDescriptor {
	return []SourceDescriptor{
		{
			ID: "netease", Label: "网易云音乐", DefaultMode: DirectURL,
			SupportedModes: []string{DirectURL, ServerDownload}, LocalService: "NETEASE_API",
			ConnectionFields: []ConnectionField{
				{Key: "neteaseCookie", Label: "音频解析 Cookie", Kind: "password", Placeholder: "仅用于受限音频解析，不用于用户资料或个人收藏"},
				{Key: "neteaseQuality", Label: "解析音质上限", Kind: "select", Options: []ConnectionOption{
					{Value: "standard", Label: "标准"}, {Value: "higher", Label: "较高"},
					{Value: "exhigh", Label: "极高"}, {Value: "lossless", Label: "无损"},
					{Value: "hires", Label: "高解析度"},
				}},
			},
		},
		{
			ID: "bilibili", Label: "Bilibili", DefaultMode: ServerDownload,
			SupportedModes: []string{DirectURL, ServerDownload},
			ConnectionFields: []ConnectionField{
				{Key: "biliCookie", Label: "音频解析 Cookie", Kind: "password", Placeholder: "仅用于受限音频解析（含 SESSDATA），不用于用户收藏身份"},
			},
		},
	}
}

func DefaultSources() map[string]SourceConfig {
	sources := make(map[string]SourceConfig)
	for _, descriptor := range SourceDescriptors() {
		sources[descriptor.ID] = SourceConfig{Enabled: true, DeliveryMode: descriptor.DefaultMode}
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
