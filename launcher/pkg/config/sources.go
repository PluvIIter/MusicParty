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
	GatewayMode string             `json:"gatewayMode,omitempty"`
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
				{Key: "neteaseCookie", Label: "网易云账号 Cookie", Kind: "password", Placeholder: "配置后所有网易云数据查询、音频解析和私人推荐均使用该账号；未配置才匿名"},
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
				{Key: "biliCookie", Label: "Bilibili 账号 Cookie", Kind: "password", Placeholder: "配置后所有 Bilibili 数据查询和音频解析均使用该账号（含 SESSDATA）；未配置才匿名"},
			},
		},
		{
			ID: "qqmusic", Label: "QQ 音乐", DefaultMode: ServerDownload, DefaultEnabled: false,
			SupportedModes: []string{DirectURL, ServerDownload}, LocalService: "QQMUSIC_API",
			Description: "启用后一键运行随包 1124 QQMusic 原生 Web API，无需安装 Python。外部 API 必须关闭默认账号池、勿公开 Web 服务。配置的账号凭据用于所有 QQ 数据查询、音频和私人 FM；未配置才匿名。绑定用户只是查询目标，访问权限由上游按配置账号判断。",
			ConnectionFields: []ConnectionField{
				{Key: "qqmusicGatewayMode", Label: "QQ 原生 API 运行方式", Kind: "select", Options: []ConnectionOption{{Value: "bundled", Label: "内置一键启动（推荐）"}, {Value: "external", Label: "外部原生 API"}}},
				{Key: "qqmusicBaseUrl", Label: "QQ 原生 Web API 地址", Kind: "url", GatewayMode: "external", Placeholder: "http://127.0.0.1:8000；填写 1124 原生 Web API 根地址"},
				{Key: "qqmusicCookie", Label: "QQ 原生 Cookie JSON", Kind: "password", Placeholder: "必需 musicid、musickey；可选 str_musicid、openid、refresh_token、access_token、expired_at、unionid、refresh_key；留空匿名"},
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
		if descriptor.LocalService == name && c.SourceEnabled(descriptor.ID) && (name != "QQMUSIC_API" || c.QqmusicGatewayMode == "bundled") {
			return true
		}
	}
	return false
}
