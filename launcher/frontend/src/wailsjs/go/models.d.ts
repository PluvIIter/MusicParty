export namespace config {
  export interface SourceConfig {
    enabled: boolean;
    deliveryMode: 'DIRECT_URL' | 'SERVER_DOWNLOAD';
  }

  export interface AppConfig {
    sources: Record<string, SourceConfig>;
    [key: string]: unknown;
  }

  export interface SourceDescriptor {
    id: string;
    label: string;
    defaultMode: string;
    supportedModes: string[];
    localService?: string;
    connectionFields: Array<{
      key: string;
      label: string;
      placeholder: string;
      kind: string;
      options?: Array<{ value: string; label: string }>;
    }>;
  }
}
