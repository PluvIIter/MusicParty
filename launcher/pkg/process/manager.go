package process

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

type Spec struct {
	Name    string
	Command string
	Args    []string
	Dir     string
	Env     map[string]string
	Secrets []string
	Quiet   bool
}

type Handle struct {
	Done chan struct{}
	mu   sync.RWMutex
	err  error
}

func (h *Handle) Err() error {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return h.err
}

type ServiceManager struct {
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	mu         sync.RWMutex
	stopped    bool
	statuses   map[string]bool
	LogChannel chan string
}

func NewServiceManager() *ServiceManager {
	ctx, cancel := context.WithCancel(context.Background())
	return &ServiceManager{ctx: ctx, cancel: cancel, statuses: make(map[string]bool), LogChannel: make(chan string, 100)}
}

func (m *ServiceManager) log(message string) {
	select {
	case m.LogChannel <- message:
	default:
	}
}

func (m *ServiceManager) StartProcess(name, command string, args ...string) error {
	_, err := m.Start(Spec{Name: name, Command: command, Args: args})
	return err
}

func (m *ServiceManager) Start(spec Spec) (*Handle, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.stopped {
		return nil, fmt.Errorf("services have been stopped")
	}
	if m.statuses[spec.Name] {
		return nil, fmt.Errorf("service is already running: %s", spec.Name)
	}
	cmd := exec.CommandContext(m.ctx, spec.Command, spec.Args...)
	cmd.Dir = spec.Dir
	cmd.Env = Environment(os.Environ(), spec.Env)
	configureProcess(cmd)
	cmd.Cancel = func() error { return killProcessTree(cmd) }
	cmd.WaitDelay = 2 * time.Second
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("cannot read service output: %s", spec.Name)
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, fmt.Errorf("cannot read service errors: %s", spec.Name)
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("cannot start service: %s", spec.Name)
	}
	handle := &Handle{Done: make(chan struct{})}
	m.statuses[spec.Name] = true
	m.wg.Add(1)
	m.log(fmt.Sprintf("[SYSTEM] Started %s", spec.Name))
	go func() {
		defer m.wg.Done()
		var readers sync.WaitGroup
		readers.Add(2)
		go func() { defer readers.Done(); m.capture(spec, stdout) }()
		go func() { defer readers.Done(); m.capture(spec, stderr) }()
		err := cmd.Wait()
		stdout.Close()
		stderr.Close()
		readers.Wait()
		handle.mu.Lock()
		handle.err = err
		handle.mu.Unlock()
		m.mu.Lock()
		m.statuses[spec.Name] = false
		m.mu.Unlock()
		close(handle.Done)
		m.log(fmt.Sprintf("[SYSTEM] %s exited", spec.Name))
	}()
	return handle, nil
}

func Environment(base []string, overrides map[string]string) []string {
	result := make([]string, 0, len(base)+len(overrides))
	for _, entry := range base {
		key, _, _ := strings.Cut(entry, "=")
		replaced := false
		for name := range overrides {
			if strings.EqualFold(key, name) {
				replaced = true
				break
			}
		}
		if !replaced {
			result = append(result, entry)
		}
	}
	for key, value := range overrides {
		result = append(result, key+"="+value)
	}
	return result
}

func (m *ServiceManager) capture(spec Spec, reader io.Reader) {
	if spec.Quiet {
		io.Copy(io.Discard, reader)
		return
	}
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		for _, secret := range spec.Secrets {
			if secret != "" {
				line = strings.ReplaceAll(line, secret, "[REDACTED]")
			}
		}
		m.log(fmt.Sprintf("[%s] %s", spec.Name, line))
	}
	if scanner.Err() != nil {
		io.Copy(io.Discard, reader)
	}
}

func (m *ServiceManager) GetStatuses() map[string]bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make(map[string]bool, len(m.statuses))
	for name, alive := range m.statuses {
		result[name] = alive
	}
	return result
}

func (m *ServiceManager) StopAll() {
	m.mu.Lock()
	m.stopped = true
	m.cancel()
	m.mu.Unlock()
	m.wg.Wait()
}
