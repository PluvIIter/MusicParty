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
	DefaultEnabled   bool              `json:"defaultEnabled"`
	Description      string            `json:"description,omitempty"`
	ConnectionFields []ConnectionField `json:"connectionFields"`
	LocalService     string            `json:"localService,omitempty"`
}

// The launcher needs adapter defaults before the backend is running.
func SourceDescriptors() []SourceDescriptor {
	return []SourceDescriptor{
		{
			ID: "netease", Label: "网易云音乐", DefaultMode: DirectURL, DefaultEnabled: true,
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
			ID: "bilibili", Label: "Bilibili", DefaultMode: ServerDownload, DefaultEnabled: true,
			SupportedModes: []string{DirectURL, ServerDownload},
			ConnectionFields: []ConnectionField{
				{Key: "biliCookie", Label: "音频解析 Cookie", Kind: "password", Placeholder: "仅用于受限音频解析（含 SESSDATA），不用于用户收藏身份"},
			},
		},
		{
			ID: "qqmusic", Label: "QQ 音乐", DefaultMode: ServerDownload, DefaultEnabled: false,
			SupportedModes: []string{DirectURL, ServerDownload},
			Description:    "需要单独运行 QQ API 外部网关；启动器不会安装 Python、启动或打包 QQ 网关。凭据仅用于音频解析和原生私人 FM，不作为公开收藏的用户身份。",
			ConnectionFields: []ConnectionField{
				{Key: "qqmusicBaseUrl", Label: "QQ API 外部网关地址", Kind: "url", Placeholder: "http://localhost:8000；填写网关根地址，不含 /v1 路径"},
				{Key: "qqmusicGatewayToken", Label: "网关访问令牌", Kind: "password", Placeholder: "16–512 位可打印 ASCII，不含空格；须与外部网关 QQMUSIC_GATEWAY_TOKEN 一致"},
				{Key: "qqmusicCookie", Label: "QQ Credential JSON", Kind: "password", Placeholder: "填写规范化 Credential JSON 对象，不是浏览器 Cookie 串；留空仅尝试合法匿名音频"},
				{Key: "qqmusicQuality", Label: "音频音质", Kind: "select", Options: []ConnectionOption{
					{Value: "MP3_128", Label: "MP3 128 kbps"}, {Value: "MP3_320", Label: "MP3 320 kbps"},
					{Value: "FLAC", Label: "FLAC 无损"},
				}},
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
