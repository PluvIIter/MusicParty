export namespace config {
  export interface SourceConfig {
    enabled: boolean;
    deliveryMode: 'DIRECT_URL' | 'SERVER_DOWNLOAD';
  }

  export interface AppConfig {
    serverIp: string;
    serverPort: string;
    baseUrl: string;
    adminPassword: string;
    authorName: string;
    backWords: string;
    sources: Record<string, SourceConfig>;
    neteaseCookie: string;
    neteaseQuality: string;
    biliCookie: string;
    qqmusicGatewayMode: 'bundled' | 'external';
    qqmusicBaseUrl: string;
    qqmusicGatewayToken: string;
    qqmusicCookie: string;
    qqmusicQuality: 'MP3_128' | 'MP3_320' | 'FLAC';
    queueMaxSize: number;
    queueHistorySize: number;
    queueMaxUserSongs: number;
    maxPlaylistImportSize: number;
    chatMaxHistorySize: number;
    chatMinIntervalMs: number;
    chatMaxMessageLength: number;
    cacheMaxSize: string;
    authRateLimitEnabled: boolean;
    authMaxAttempts: number;
    authWindowSeconds: number;
    authBlockDuration: number;
    autoStart: boolean;
  }

  export interface SourceDescriptor {
    id: string;
    label: string;
    defaultMode: string;
    defaultEnabled: boolean;
    description?: string;
    supportedModes: string[];
    localService?: string;
    connectionFields: Array<{
      key: string;
      label: string;
      placeholder: string;
      kind: string;
      gatewayMode?: 'external' | 'bundled';
      options?: Array<{ value: string; label: string }>;
    }>;
  }
}
