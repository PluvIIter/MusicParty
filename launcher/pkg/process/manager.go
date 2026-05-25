package process

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os/exec"
	"sync"
	"runtime"
	"syscall"
)

type ProcessStatus struct {
	Name    string `json:"name"`
	IsAlive bool   `json:"isAlive"`
}

type ServiceManager struct {
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	LogChannel chan string
	StatusLock sync.RWMutex
	Statuses   map[string]bool
}

func NewServiceManager() *ServiceManager {
	ctx, cancel := context.WithCancel(context.Background())
	return &ServiceManager{
		ctx:        ctx,
		cancel:     cancel,
		LogChannel: make(chan string, 100),
		Statuses:   make(map[string]bool),
	}
}

func (m *ServiceManager) StartProcess(name string, command string, args ...string) {
	m.wg.Add(1)
	m.setStatus(name, true)
	
	go func() {
		defer m.wg.Done()
		defer m.setStatus(name, false)
		
		m.LogChannel <- fmt.Sprintf("[SYSTEM] Starting %s...", name)
		cmd := exec.CommandContext(m.ctx, command, args...)
		
		// 隐藏 Windows 命令行窗口
		if runtime.GOOS == "windows" {
			cmd.SysProcAttr = &syscall.SysProcAttr{
				HideWindow:    true,
				CreationFlags: 0x08000000, // CREATE_NO_WINDOW
			}
		}
		
		stdout, _ := cmd.StdoutPipe()
		stderr, _ := cmd.StderrPipe()
		
		go m.captureOutput(name, stdout)
		go m.captureOutput(name, stderr)
		
		if err := cmd.Start(); err != nil {
			m.LogChannel <- fmt.Sprintf("[%s ERROR] Failed to start: %v", name, err)
			return
		}
		
		cmd.Wait()
		m.LogChannel <- fmt.Sprintf("[SYSTEM] %s exited.", name)
	}()
}

func (m *ServiceManager) setStatus(name string, alive bool) {
	m.StatusLock.Lock()
	defer m.StatusLock.Unlock()
	m.Statuses[name] = alive
}

func (m *ServiceManager) GetStatuses() map[string]bool {
	m.StatusLock.RLock()
	defer m.StatusLock.RUnlock()
	// 返回副本防止并发问题
	res := make(map[string]bool)
	for k, v := range m.Statuses {
		res[k] = v
	}
	return res
}

func (m *ServiceManager) captureOutput(name string, r io.Reader) {
	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		m.LogChannel <- fmt.Sprintf("[%s] %s", name, scanner.Text())
	}
}

func (m *ServiceManager) StopAll() {
	m.cancel()
	m.wg.Wait()
	m.LogChannel <- "[SYSTEM] All services stopped."
}
