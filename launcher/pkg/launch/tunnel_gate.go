package launch

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"sync/atomic"
	"time"

	"launcher/pkg/config"
	"launcher/pkg/process"
)

type tunnelGate struct {
	server *http.Server
	origin string
	target string
	open   atomic.Bool
}

func newTunnelGate(cfg *config.AppConfig) (*tunnelGate, error) {
	target, err := tunnelOrigin(cfg)
	if err != nil {
		return nil, err
	}
	targetURL, _ := url.Parse(target)
	bind := net.JoinHostPort(strings.Trim(cfg.ServerIP, "[]"), cfg.ServerPort)
	probe, err := net.Listen("tcp", bind)
	if err != nil {
		return nil, fmt.Errorf("server port is already occupied; tunnel was not started")
	}
	probe.Close()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("cannot create private tunnel application gate")
	}
	gate := &tunnelGate{origin: "http://" + listener.Addr().String(), target: target}
	proxy := httputil.NewSingleHostReverseProxy(targetURL)
	proxy.Transport = &http.Transport{Proxy: nil, DialContext: (&net.Dialer{Timeout: 5 * time.Second, KeepAlive: 30 * time.Second}).DialContext, ForceAttemptHTTP2: false}
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		http.Error(w, "Application unavailable", http.StatusServiceUnavailable)
	}
	gate.server = &http.Server{ReadHeaderTimeout: 5 * time.Second, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !gate.open.Load() {
			http.Error(w, "Application is starting", http.StatusServiceUnavailable)
			return
		}
		proxy.ServeHTTP(w, r)
	})}
	go gate.server.Serve(listener)
	return gate, nil
}

func (gate *tunnelGate) close() { gate.open.Store(false); gate.server.Close() }

func (gate *tunnelGate) waitBackend(ctx context.Context, client *http.Client, password string, handle *process.Handle) error {
	localClient := *client
	localClient.Transport = &http.Transport{Proxy: nil, DialContext: (&net.Dialer{Timeout: time.Second}).DialContext}
	client = &localClient
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-handle.Done:
			return fmt.Errorf("Java server exited before tunnel application readiness")
		default:
		}
		request, _ := http.NewRequestWithContext(ctx, http.MethodGet, gate.target+"/api/v2/sources", nil)
		request.Header.Set("X-Room-Password", password)
		response, err := client.Do(request)
		if err == nil {
			var body struct {
				SchemaVersion int               `json:"schemaVersion"`
				Sources       []json.RawMessage `json:"sources"`
			}
			decodeErr := json.NewDecoder(io.LimitReader(response.Body, 1024*1024)).Decode(&body)
			response.Body.Close()
			if response.StatusCode == 200 && decodeErr == nil && body.SchemaVersion == 2 && body.Sources != nil {
				select {
				case <-handle.Done:
					return fmt.Errorf("Java server exited before tunnel application readiness")
				default:
				}
				if ctx.Err() != nil {
					return fmt.Errorf("Java application readiness canceled")
				}
				gate.open.Store(true)
				return nil
			}
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("Java application readiness canceled or timed out")
		case <-ticker.C:
		}
	}
}
