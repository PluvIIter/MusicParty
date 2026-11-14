package launch

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"

	"launcher/pkg/config"
	"launcher/pkg/process"
)

type TunnelStatus struct {
	Enabled bool   `json:"enabled"`
	State   string `json:"state"`
	URL     string `json:"url"`
	Error   string `json:"error"`
}

func (c *Controller) GetTunnelStatus() TunnelStatus {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.tunnel
}

var tunnelAddress = regexp.MustCompile(`https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.trycloudflare\.com`)

type tunnelEvents struct {
	mu        sync.Mutex
	url       string
	connected bool
	changed   chan struct{}
}

func (events *tunnelEvents) line(line string) {
	var event struct {
		Message string `json:"message"`
	}
	if json.Unmarshal([]byte(line), &event) != nil {
		return
	}
	events.mu.Lock()
	defer events.mu.Unlock()
	for _, candidate := range strings.Fields(event.Message) {
		candidate = strings.Trim(candidate, "| \t")
		if tunnelAddress.FindString(candidate) == candidate && candidate != "" {
			events.url = candidate
		}
	}
	if event.Message == "Registered tunnel connection" {
		events.connected = true
	}
	select {
	case events.changed <- struct{}{}:
	default:
	}
}

func (events *tunnelEvents) snapshot() (string, bool) {
	events.mu.Lock()
	defer events.mu.Unlock()
	return events.url, events.connected
}

func tunnelOrigin(cfg *config.AppConfig) (string, error) {
	host := strings.Trim(cfg.ServerIP, "[]")
	switch host {
	case "0.0.0.0", "":
		host = "127.0.0.1"
	case "::":
		host = "::1"
	}
	ip := net.ParseIP(host)
	if host != "localhost" && (ip == nil || !localBindIP(ip)) {
		return "", fmt.Errorf("tunnel origin requires a local server bind address")
	}
	port, err := strconv.Atoi(cfg.ServerPort)
	if err != nil || port < 1 || port > 65535 {
		return "", fmt.Errorf("tunnel origin requires a valid server port")
	}
	return "http://" + net.JoinHostPort(host, strconv.Itoa(port)), nil
}

func localBindIP(ip net.IP) bool {
	if ip.IsLoopback() {
		return true
	}
	addresses, err := net.InterfaceAddrs()
	if err != nil {
		return false
	}
	for _, address := range addresses {
		local, _, err := net.ParseCIDR(address.String())
		if err == nil && local.Equal(ip) {
			return true
		}
	}
	return false
}

func inheritedTunnelVariables(base []string) []string {
	var names []string
	for _, entry := range base {
		key, _, _ := strings.Cut(entry, "=")
		upper := strings.ToUpper(key)
		if strings.HasPrefix(upper, "TUNNEL_") || strings.HasPrefix(upper, "CLOUDFLARE_") || upper == "NO_AUTOUPDATE" || upper == "NO_TLS_VERIFY" || upper == "STDIN_CONTROL" || upper == "DIAL_EDGE_TIMEOUT" {
			names = append(names, key)
		}
	}
	return names
}

func (c *Controller) startTunnel(ctx context.Context, manager *process.ServiceManager, origin string) (*process.Handle, string, error) {
	if err := os.MkdirAll(c.DeviceDir, 0700); err != nil {
		return nil, "", fmt.Errorf("cannot prepare private tunnel configuration")
	}
	configFile, err := os.CreateTemp(c.DeviceDir, ".cloudflared-*.json")
	if err != nil {
		return nil, "", fmt.Errorf("cannot prepare private tunnel configuration")
	}
	path := configFile.Name()
	if _, err := configFile.WriteString("{}\n"); err != nil {
		configFile.Close()
		removeTunnelConfig(c.DeviceDir, path)
		return nil, "", fmt.Errorf("cannot prepare private tunnel configuration")
	}
	if err := configFile.Close(); err != nil {
		removeTunnelConfig(c.DeviceDir, path)
		return nil, "", fmt.Errorf("cannot prepare private tunnel configuration")
	}
	events := &tunnelEvents{changed: make(chan struct{}, 1)}
	handle, err := manager.Start(process.Spec{Name: "CLOUDFLARE_TUNNEL", Command: filepath.Join(c.BinDir, executable("cloudflared")), Args: []string{"tunnel", "--config", path, "--no-autoupdate", "--output", "json", "--protocol", "http2", "--metrics", "127.0.0.1:0", "--url", origin}, UnsetEnv: inheritedTunnelVariables(os.Environ()), Env: map[string]string{"NO_AUTOUPDATE": "true"}, Quiet: true, OnLine: events.line})
	if err != nil {
		removeTunnelConfig(c.DeviceDir, path)
		return nil, "", fmt.Errorf("cannot start bundled Cloudflare Tunnel")
	}
	go func() { <-handle.Done; removeTunnelConfig(c.DeviceDir, path) }()
	for {
		url, connected := events.snapshot()
		if url != "" && connected {
			select {
			case <-handle.Done:
				return nil, "", fmt.Errorf("Cloudflare Tunnel exited during startup")
			default:
			}
			return handle, url, nil
		}
		select {
		case <-ctx.Done():
			return nil, "", fmt.Errorf("Cloudflare Tunnel startup canceled or timed out")
		case <-handle.Done:
			return nil, "", fmt.Errorf("Cloudflare Tunnel exited before connecting")
		case <-events.changed:
		}
	}
}

func (c *Controller) deactivateTunnel(manager *process.ServiceManager, state, message string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.manager != manager {
		return
	}
	if c.gate != nil {
		c.gate.close()
		c.gate = nil
	}
	if c.tunnel.Enabled && c.tunnel.State != "stopped" && !(state == "stopped" && c.tunnel.State == "failed") {
		c.tunnel = TunnelStatus{Enabled: true, State: state, Error: message}
	}
}

func removeTunnelConfig(directory, path string) {
	parent, err := filepath.Abs(directory)
	if err != nil {
		return
	}
	absolute, err := filepath.Abs(path)
	if err != nil {
		return
	}
	if filepath.Dir(absolute) == parent && strings.HasPrefix(filepath.Base(absolute), ".cloudflared-") && filepath.Ext(absolute) == ".json" {
		os.Remove(absolute)
	}
}
