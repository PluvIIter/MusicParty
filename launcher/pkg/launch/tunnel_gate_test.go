package launch

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"launcher/pkg/config"
	"launcher/pkg/process"
)

func gateBackend(t *testing.T, host string, handler http.Handler) (*tunnelGate, *httptest.Server) {
	t.Helper()
	listener, err := net.Listen("tcp", net.JoinHostPort(host, "0"))
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	cfg := config.DefaultConfig()
	cfg.ServerIP, cfg.ServerPort = host, strconv.Itoa(port)
	gate, err := newTunnelGate(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(gate.close)
	backend := httptest.NewUnstartedServer(handler)
	backend.Listener.Close()
	backend.Listener, err = net.Listen("tcp", net.JoinHostPort(host, cfg.ServerPort))
	if err != nil {
		t.Fatal(err)
	}
	backend.Start()
	t.Cleanup(backend.Close)
	return gate, backend
}

func readyGate(t *testing.T, gate *tunnelGate, client *http.Client) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := gate.waitBackend(ctx, client, "fixture-admin", &process.Handle{Done: make(chan struct{})}); err != nil {
		t.Fatal(err)
	}
}

func TestGateStreamsWithoutWaitingForBackendCompletion(t *testing.T) {
	release := make(chan struct{})
	gate, _ := gateBackend(t, "127.0.0.1", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/status" {
			fmt.Fprint(w, `{"isSetup":true,"hasProtection":true}`)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "first\n")
		w.(http.Flusher).Flush()
		select {
		case <-release:
			fmt.Fprint(w, "second\n")
		case <-r.Context().Done():
		}
	}))
	client := &http.Client{Timeout: 3 * time.Second, Transport: &http.Transport{Proxy: nil}}
	response, err := client.Get(gate.origin + "/stream")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 503 {
		t.Fatal("closed gate forwarded before application readiness")
	}
	readyGate(t, gate, client)
	response, err = client.Get(gate.origin + "/stream")
	if err != nil {
		close(release)
		t.Fatal(err)
	}
	defer response.Body.Close()
	reader := bufio.NewReader(response.Body)
	if first, err := reader.ReadString('\n'); err != nil || first != "first\n" {
		close(release)
		t.Fatal("first streamed chunk was buffered")
	}
	close(release)
	if second, err := reader.ReadString('\n'); err != nil || second != "second\n" {
		t.Fatal("second streamed chunk lost")
	}
	gate.close()
	if response, err := client.Get(gate.origin); err == nil {
		response.Body.Close()
		t.Fatal("new connection accepted after gate close")
	}
}

func TestGateWebSocketUpgradeAndBidirectionalFrames(t *testing.T) {
	serverErr := make(chan error, 1)
	gate, _ := gateBackend(t, "127.0.0.1", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/status" {
			fmt.Fprint(w, `{"isSetup":true,"hasProtection":true}`)
			return
		}
		connection, buffer, err := w.(http.Hijacker).Hijack()
		if err != nil {
			serverErr <- err
			return
		}
		defer connection.Close()
		connection.SetDeadline(time.Now().Add(3 * time.Second))
		fmt.Fprint(buffer, "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: fixture\r\n\r\n")
		buffer.Flush()
		header := make([]byte, 2)
		if _, err = io.ReadFull(buffer, header); err != nil {
			serverErr <- err
			return
		}
		if header[0] != 0x81 || header[1]&0x80 == 0 || header[1]&0x7f > 125 {
			serverErr <- fmt.Errorf("invalid masked client frame")
			return
		}
		mask := make([]byte, 4)
		if _, err = io.ReadFull(buffer, mask); err != nil {
			serverErr <- err
			return
		}
		payload := make([]byte, int(header[1]&0x7f))
		if _, err = io.ReadFull(buffer, payload); err != nil {
			serverErr <- err
			return
		}
		for index := range payload {
			payload[index] ^= mask[index%4]
		}
		if string(payload) != "hello" {
			serverErr <- fmt.Errorf("client payload lost")
			return
		}
		_, err = connection.Write(append([]byte{0x81, byte(len(payload))}, payload...))
		serverErr <- err
	}))
	readyGate(t, gate, &http.Client{Timeout: time.Second})
	origin, _ := url.Parse(gate.origin)
	connection, err := net.DialTimeout("tcp", origin.Host, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	connection.SetDeadline(time.Now().Add(3 * time.Second))
	fmt.Fprintf(connection, "GET /socket HTTP/1.1\r\nHost: %s\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: Zml4dHVyZQ==\r\n\r\n", origin.Host)
	reader := bufio.NewReader(connection)
	response, err := http.ReadResponse(reader, &http.Request{Method: http.MethodGet})
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != 101 || !strings.EqualFold(response.Header.Get("Upgrade"), "websocket") {
		t.Fatal("WebSocket upgrade was not forwarded")
	}
	mask := []byte{1, 2, 3, 4}
	payload := []byte("hello")
	frame := append([]byte{0x81, 0x80 | byte(len(payload))}, mask...)
	for index, value := range payload {
		frame = append(frame, value^mask[index%4])
	}
	if _, err = connection.Write(frame); err != nil {
		t.Fatal(err)
	}
	echo := make([]byte, 7)
	if _, err = io.ReadFull(reader, echo); err != nil {
		t.Fatal(err)
	}
	if echo[0] != 0x81 || echo[1] != 5 || string(echo[2:]) != "hello" {
		t.Fatal("server frame did not traverse tunnel gate")
	}
	if err := <-serverErr; err != nil {
		t.Fatal(err)
	}
}

func TestGateAndReadinessNeverUseInheritedHTTPProxy(t *testing.T) {
	var proxyCalls atomic.Int32
	malicious := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { proxyCalls.Add(1); http.Error(w, "untrusted proxy", 502) }))
	defer malicious.Close()
	t.Setenv("HTTP_PROXY", malicious.URL)
	t.Setenv("HTTPS_PROXY", malicious.URL)
	t.Setenv("NO_PROXY", "")
	host := "127.0.0.1"
	addresses, _ := net.InterfaceAddrs()
	for _, address := range addresses {
		ip, _, err := net.ParseCIDR(address.String())
		if err == nil && ip.To4() != nil && !ip.IsLoopback() {
			host = ip.String()
			break
		}
	}
	gate, _ := gateBackend(t, host, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/status" {
			fmt.Fprint(w, `{"isSetup":true,"hasProtection":true}`)
			return
		}
		fmt.Fprint(w, "direct application")
	}))
	proxyURL, _ := url.Parse(malicious.URL)
	readyGate(t, gate, &http.Client{Timeout: time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}})
	client := &http.Client{Timeout: time.Second, Transport: &http.Transport{Proxy: nil}}
	response, err := client.Get(gate.origin + "/direct")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(response.Body)
	if string(body) != "direct application" || proxyCalls.Load() != 0 {
		t.Fatal("gate or readiness used inherited proxy")
	}
}

func TestGateReadinessUsesPublicStatusWithoutPasswordHeaders(t *testing.T) {
	for _, password := range []string{" leading and trailing ", "admin123", "", "   "} {
		t.Run(strconv.Quote(password), func(t *testing.T) {
			var calls atomic.Int32
			gate, _ := gateBackend(t, "127.0.0.1", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				if r.URL.Path != "/api/auth/status" || r.Method != http.MethodGet {
					http.Error(w, "wrong readiness route", http.StatusNotFound)
					return
				}
				if _, exists := r.Header["X-Room-Password"]; exists || r.Header.Get("Authorization") != "" {
					http.Error(w, "readiness must not carry credentials", http.StatusForbidden)
					return
				}
				fmt.Fprint(w, `{"isSetup":false,"hasProtection":false}`)
			}))
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			if err := gate.waitBackend(ctx, &http.Client{Timeout: time.Second}, password, &process.Handle{Done: make(chan struct{})}); err != nil {
				t.Fatal(err)
			}
			if calls.Load() == 0 || !gate.open.Load() {
				t.Fatal("public application status did not open the gate")
			}
		})
	}
}

func TestGateReadinessRejectsIncompleteOrNonBooleanStatus(t *testing.T) {
	for _, body := range []string{`{}`, `{"isSetup":true}`, `{"isSetup":null,"hasProtection":false}`, `{"isSetup":true,"hasProtection":"false"}`, `{"schemaVersion":2,"sources":[]}`} {
		gate, _ := gateBackend(t, "127.0.0.1", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			fmt.Fprint(w, body)
		}))
		ctx, cancel := context.WithTimeout(context.Background(), 120*time.Millisecond)
		err := gate.waitBackend(ctx, &http.Client{Timeout: time.Second}, "unused", &process.Handle{Done: make(chan struct{})})
		cancel()
		if err == nil || gate.open.Load() {
			t.Fatal("invalid public status was treated as application readiness")
		}
	}
}

func TestTunnelAllowsDefaultAdminWithoutBlockingStartup(t *testing.T) {
	for _, password := range []string{"", "admin123", "   "} {
		cfg := config.DefaultConfig()
		cfg.AdminPassword = password
		if err := cfg.Validate(); err != nil {
			t.Fatal("local configuration rejected")
		}
		cfg.CloudflareTunnelEnabled = true
		if err := cfg.Validate(); err != nil {
			t.Fatal("tunnel configuration blocked instead of warning")
		}
	}
	cfg := config.DefaultConfig()
	cfg.CloudflareTunnelEnabled = true
	cfg.AdminPassword = "fixture-admin-secure"
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
}

func TestParentCancellationClearsReadyTunnelURL(t *testing.T) {
	controller, cfg, _, _ := tunnelFixture(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := controller.Start(ctx, cfg); err != nil {
		t.Fatal(err)
	}
	if controller.GetTunnelStatus().State != "ready" {
		t.Fatal("tunnel never became ready")
	}
	cancel()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		status := controller.GetTunnelStatus()
		if status.URL == "" && status.State != "ready" && status.State != "starting" {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("parent cancellation retained ready tunnel URL")
}
