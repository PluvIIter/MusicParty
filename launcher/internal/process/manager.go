package process

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os/exec"
	"sync"
)

type ServiceManager struct {
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	LogChannel chan string
}

func NewServiceManager() *ServiceManager {
	ctx, cancel := context.WithCancel(context.Background())
	return &ServiceManager{
		ctx:        ctx,
		cancel:     cancel,
		LogChannel: make(chan string, 100),
	}
}

func (m *ServiceManager) StartProcess(name string, command string, args ...string) {
	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		
		m.LogChannel <- fmt.Sprintf("[SYSTEM] Starting %s...", name)
		cmd := exec.CommandContext(m.ctx, command, args...)
		
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
