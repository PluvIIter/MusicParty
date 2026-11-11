package config

import "fmt"

func (c *AppConfig) JavaArgs(jarPath string) ([]string, error) {
	if err := c.Validate(); err != nil {
		return nil, err
	}
	args := []string{
		"-jar", jarPath,
		fmt.Sprintf("--server.address=%s", c.ServerIP),
		fmt.Sprintf("--server.port=%s", c.ServerPort),
		fmt.Sprintf("--app.music-api.base-url=%s", c.BaseURL),
		fmt.Sprintf("--app.music-api.admin-password=%s", c.AdminPassword),
		fmt.Sprintf("--app.music-api.author-name=%s", c.AuthorName),
		fmt.Sprintf("--app.music-api.back-words=%s", c.BackWords),
		"--app.music-api.netease.base-url=http://127.0.0.1:3000",
		fmt.Sprintf("--app.music-api.netease.cookie=%s", c.NeteaseCookie),
		fmt.Sprintf("--app.music-api.netease.quality=%s", c.NeteaseQuality),
		fmt.Sprintf("--app.music-api.bilibili.cookie=%s", c.BiliCookie),
		fmt.Sprintf("--app.music-api.queue.max-size=%d", c.QueueMaxSize),
		fmt.Sprintf("--app.music-api.queue.history-size=%d", c.QueueHistorySize),
		fmt.Sprintf("--app.music-api.queue.max-user-songs=%d", c.QueueMaxUserSongs),
		fmt.Sprintf("--app.music-api.player.max-playlist-import-size=%d", c.MaxPlaylistImportSize),
		fmt.Sprintf("--app.music-api.chat.max-history-size=%d", c.ChatMaxHistorySize),
		fmt.Sprintf("--app.music-api.chat.min-interval-ms=%d", c.ChatMinIntervalMs),
		fmt.Sprintf("--app.music-api.chat.max-message-length=%d", c.ChatMaxMessageLength),
		fmt.Sprintf("--app.music-api.cache.max-size=%s", c.CacheMaxSize),
		fmt.Sprintf("--app.music-api.auth.rate-limit.enabled=%v", c.AuthRateLimitEnabled),
		fmt.Sprintf("--app.music-api.auth.rate-limit.max-attempts=%d", c.AuthMaxAttempts),
		fmt.Sprintf("--app.music-api.auth.rate-limit.window-seconds=%d", c.AuthWindowSeconds),
		fmt.Sprintf("--app.music-api.auth.rate-limit.block-duration-seconds=%d", c.AuthBlockDuration),
		"--logging.level.root=INFO",
		"--logging.level.org.thornex.musicparty=DEBUG",
	}
	for _, id := range c.SourceIDs() {
		source := c.Sources[id]
		args = append(args,
			fmt.Sprintf("--app.music-api.sources.%s.enabled=%t", id, source.Enabled),
			fmt.Sprintf("--app.music-api.sources.%s.delivery-mode=%s", id, source.DeliveryMode),
		)
	}
	return args, nil
}
