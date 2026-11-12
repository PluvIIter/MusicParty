package launch

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"sync"
	"time"

	"launcher/pkg/config"
	"launcher/pkg/process"
)

type Controller struct {
	BinDir        string
	DeviceDir     string
	GatewayDir    string
	ReadyTimeout  time.Duration
	TunnelTimeout time.Duration
	tunnel        TunnelStatus
	gate          *tunnelGate
	Client        *http.Client
	Log           func(string)
	startMu       sync.Mutex
	mu            sync.RWMutex
	manager       *process.ServiceManager
	cancel        context.CancelFunc
	ready         bool
	bundled       bool
}

func NewController(binDir, deviceDir string, log func(string)) *Controller {
	return &Controller{BinDir: binDir, DeviceDir: deviceDir, ReadyTimeout: 30 * time.Second, TunnelTimeout: 45 * time.Second, tunnel: TunnelStatus{State: "off"},
		Client: &http.Client{Timeout: time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, Log: log}
}

func (c *Controller) Stop() {
	c.mu.Lock()
	if c.cancel != nil {
		c.cancel()
	}
	manager := c.manager
	if c.gate != nil {
		c.gate.close()
		c.gate = nil
	}
	c.ready = false
	if c.tunnel.Enabled {
		c.tunnel.State = "stopped"
		c.tunnel.URL = ""
		c.tunnel.Error = ""
	}
	c.mu.Unlock()
	if manager != nil {
		manager.StopAll()
	}
}

func (c *Controller) Statuses() map[string]bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	statuses := map[string]bool{"NETEASE_API": false, "JAVA_SERVER": false, "QQMUSIC_API": false}
	if c.manager == nil {
		return statuses
	}
	for name, alive := range c.manager.GetStatuses() {
		statuses[name] = alive
	}
	statuses["QQMUSIC_API"] = c.ready && (!c.bundled || statuses["QQMUSIC_API"])
	return statuses
}

func (c *Controller) Start(parent context.Context, cfg *config.AppConfig) (err error) {
	c.startMu.Lock()
	defer c.startMu.Unlock()
	ctx, cancel := context.WithCancel(parent)
	manager := process.NewServiceManager()
	c.mu.Lock()
	previous := c.manager
	if c.gate != nil {
		c.gate.close()
		c.gate = nil
	}
	if c.cancel != nil {
		c.cancel()
	}
	c.manager, c.cancel, c.ready, c.bundled = manager, cancel, false, false
	c.tunnel = TunnelStatus{State: "off"}
	if cfg != nil && cfg.CloudflareTunnelEnabled {
		c.tunnel = TunnelStatus{Enabled: true, State: "starting"}
	}
	c.mu.Unlock()
	if previous != nil {
		previous.StopAll()
	}
	go func() { <-ctx.Done(); c.deactivateTunnel(manager, "stopped", ""); manager.StopAll() }()
	defer func() {
		if err != nil {
			c.deactivateTunnel(manager, "failed", "服务启动失败，隧道已停止")
			cancel()
			manager.StopAll()
			c.mu.Lock()
			if c.manager == manager {
				c.ready = false
				if c.tunnel.Enabled && c.tunnel.State != "stopped" {
					c.tunnel = TunnelStatus{Enabled: true, State: "failed", Error: "服务启动失败，隧道已停止"}
				}
			}
			c.mu.Unlock()
		}
	}()
	if ctx.Err() != nil {
		return fmt.Errorf("service startup canceled")
	}
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case message := <-manager.LogChannel:
				if c.Log != nil {
					c.Log(message)
				}
			}
		}
	}()
	if cfg == nil {
		return fmt.Errorf("configuration is required")
	}
	if err := cfg.Validate(); err != nil {
		return err
	}
	active := *cfg
	active.Sources = make(map[string]config.SourceConfig, len(cfg.Sources))
	for id, source := range cfg.Sources {
		active.Sources[id] = source
	}
	var handles []*process.Handle
	if active.SourceEnabled("qqmusic") {
		bundled := active.QqmusicGatewayMode == "bundled"
		c.mu.Lock()
		c.bundled = bundled
		c.mu.Unlock()
		var gateway *process.Handle
		if bundled {
			gatewayDir := c.GatewayDir
			if gatewayDir == "" {
				gatewayDir = filepath.Join(c.BinDir, "qqmusic")
			}
			python := filepath.Join(gatewayDir, "python", executable("python"))
			upstream := filepath.Join(gatewayDir, "upstream", "source")
			app := filepath.Join(gatewayDir, "run_web.py")
			for _, path := range []string{python, app, filepath.Join(upstream, "web", "run.py"), filepath.Join(upstream, "web", "src", "app.py")} {
				info, statErr := os.Stat(path)
				if statErr != nil || info.IsDir() {
					return fmt.Errorf("bundled QQ runtime is incomplete; reinstall the complete launcher package")
				}
			}
			listener, listenErr := net.Listen("tcp4", "127.0.0.1:0")
			if listenErr != nil {
				return fmt.Errorf("cannot allocate QQ loopback port")
			}
			port := listener.Addr().(*net.TCPAddr).Port
			listener.Close()
			active.QqmusicBaseURL = "http://127.0.0.1:" + strconv.Itoa(port)
			if mkdirErr := os.MkdirAll(c.DeviceDir, 0700); mkdirErr != nil {
				return fmt.Errorf("cannot prepare QQ device directory")
			}
			gateway, err = manager.Start(process.Spec{Name: "QQMUSIC_API", Command: python, Args: []string{"-I", "-B", app}, Dir: upstream, Quiet: true, UnsetEnv: inheritedQqVariables(os.Environ()),
				Env: map[string]string{"QQMUSIC_SERVER_HOST": "127.0.0.1", "QQMUSIC_SERVER_PORT": strconv.Itoa(port), "QQMUSIC_SERVER_WORKERS": "1", "QQMUSIC_CREDENTIAL_ENABLED": "false",
					"QQMUSIC_CREDENTIAL_STORE_PATH": filepath.Join(c.DeviceDir, "qqmusic-credentials.sqlite3"), "QQMUSIC_CLIENT_DEVICE_PATH": filepath.Join(c.DeviceDir, "qqmusic-device.json"), "QQMUSIC_LOGGING_LEVEL": "WARNING",
					"PYTHONNOUSERSITE": "1", "PYTHONDONTWRITEBYTECODE": "1", "PYTHONPATH": "", "PYTHONHOME": ""}})
			if err != nil {
				return err
			}
			handles = append(handles, gateway)
		}
		readyCtx, readyCancel := context.WithTimeout(ctx, c.ReadyTimeout)
		err = waitGateway(readyCtx, c.Client, active.QqmusicBaseURL, gateway)
		readyCancel()
		if err != nil {
			return err
		}
		c.mu.Lock()
		c.ready = true
		c.mu.Unlock()
	}
	if err := ctx.Err(); err != nil {
		return fmt.Errorf("service startup canceled")
	}
	if active.LocalServiceEnabled("NETEASE_API") {
		handle, startErr := manager.Start(process.Spec{Name: "NETEASE_API", Command: filepath.Join(c.BinDir, executable("netease-api")), Args: []string{"-p", "3000"}})
		if startErr != nil {
			return startErr
		}
		handles = append(handles, handle)
	}
	if info, statErr := os.Stat(filepath.Join(c.BinDir, "server.jar")); statErr != nil || info.IsDir() {
		return fmt.Errorf("bundled server.jar is missing")
	}
	var tunnelHandle *process.Handle
	var gate *tunnelGate
	var publicURL string
	if active.CloudflareTunnelEnabled {
		var gateErr error
		gate, gateErr = newTunnelGate(&active)
		if gateErr != nil {
			return gateErr
		}
		c.mu.Lock()
		c.gate = gate
		c.mu.Unlock()
		tunnelCtx, tunnelCancel := context.WithTimeout(ctx, c.TunnelTimeout)
		tunnelHandle, publicURL, err = c.startTunnel(tunnelCtx, manager, gate.origin)
		tunnelCancel()
		if err != nil {
			return err
		}
		active.BaseURL = publicURL
		handles = append(handles, tunnelHandle)
	}
	args, err := active.JavaArgs(filepath.Join(c.BinDir, "server.jar"))
	if err != nil {
		return err
	}
	java := filepath.Join(c.BinDir, "jre", "bin", executable("java"))
	if _, statErr := os.Stat(java); statErr != nil {
		java = "java"
	}
	secrets := []string{active.QqmusicCookie, active.NeteaseCookie, active.BiliCookie, active.AdminPassword}
	javaHandle, err := manager.Start(process.Spec{Name: "JAVA_SERVER", Command: java, Args: args,
		Env: map[string]string{"PATH": c.BinDir + string(os.PathListSeparator) + os.Getenv("PATH")}, Secrets: secrets})
	if err != nil {
		return err
	}
	handles = append(handles, javaHandle)
	if ctx.Err() != nil {
		return fmt.Errorf("service startup canceled")
	}
	if tunnelHandle != nil {
		readyCtx, readyCancel := context.WithTimeout(ctx, c.ReadyTimeout)
		readyErr := gate.waitBackend(readyCtx, c.Client, active.AdminPassword, javaHandle)
		readyCancel()
		if readyErr != nil {
			return readyErr
		}
	}
	if tunnelHandle != nil {
		c.mu.Lock()
		if c.manager == manager && ctx.Err() == nil {
			c.tunnel = TunnelStatus{Enabled: true, State: "ready", URL: publicURL}
		}
		c.mu.Unlock()
	}
	for _, handle := range handles {
		select {
		case <-handle.Done:
			return fmt.Errorf("a required service exited during startup")
		default:
		}
		go func(handle *process.Handle) {
			select {
			case <-ctx.Done():
				manager.StopAll()
			case <-handle.Done:
				c.deactivateTunnel(manager, "failed", "服务进程已退出，隧道已停止")
				cancel()
				manager.StopAll()
				c.mu.Lock()
				if c.manager == manager {
					c.ready = false
					if c.tunnel.Enabled && c.tunnel.State != "stopped" {
						c.tunnel = TunnelStatus{Enabled: true, State: "failed", Error: "服务进程已退出，隧道已停止"}
					}
				}
				c.mu.Unlock()
			}
		}(handle)
	}
	return nil
}

func executable(name string) string {
	if runtime.GOOS == "windows" {
		return name + ".exe"
	}
	return name
}
