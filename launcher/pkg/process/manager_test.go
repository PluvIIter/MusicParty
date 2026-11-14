package process

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestHelperProcess(t *testing.T) {
	if os.Getenv("PROCESS_HELPER") != "1" {
		return
	}
	fmt.Println(strings.Repeat("x", 2*1024*1024))
	fmt.Println("private-token")
	if os.Getenv("PROCESS_WAIT") == "1" {
		time.Sleep(time.Hour)
	}
	os.Exit(0)
}

func TestStartFailureAndUnconsumedLogsDoNotBlockStop(t *testing.T) {
	manager := NewServiceManager()
	if _, err := manager.Start(Spec{Name: "missing", Command: "/no/such/executable"}); err == nil {
		t.Fatal("missing process accepted")
	}
	handle, err := manager.Start(Spec{Name: "helper", Command: os.Args[0], Args: []string{"-test.run=^TestHelperProcess$"}, Env: map[string]string{"PROCESS_HELPER": "1", "PROCESS_WAIT": "1"}, Secrets: []string{"private-token"}})
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { manager.StopAll(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("stop blocked without log consumer")
	}
	select {
	case <-handle.Done:
	default:
		t.Fatal("process was not reaped")
	}
	if manager.GetStatuses()["helper"] {
		t.Fatal("dead process reported alive")
	}
}

func TestTreeHelper(t *testing.T) {
	mode := os.Getenv("PROCESS_TREE_HELPER")
	if mode == "" {
		return
	}
	if mode == "child" {
		listener, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			os.Exit(2)
		}
		defer listener.Close()
		data, _ := json.Marshal(struct {
			PID     int
			Address string
		}{os.Getpid(), listener.Addr().String()})
		if os.WriteFile(os.Getenv("PROCESS_TREE_READY"), data, 0600) != nil {
			os.Exit(3)
		}
		for {
			connection, err := listener.Accept()
			if err != nil {
				os.Exit(4)
			}
			connection.Close()
		}
	}
	child := exec.Command(os.Args[0], "-test.run=^TestTreeHelper$")
	child.Env = Environment(os.Environ(), map[string]string{"PROCESS_TREE_HELPER": "child"})
	child.Stdout, child.Stderr = os.Stdout, os.Stderr
	if child.Start() != nil {
		os.Exit(5)
	}
	child.Wait()
	os.Exit(0)
}

func TestStopKillsOwnedDescendantHoldingOutputPipes(t *testing.T) {
	manager := NewServiceManager()
	ready := filepath.Join(t.TempDir(), "child.json")
	handle, err := manager.Start(Spec{Name: "tree", Command: os.Args[0], Args: []string{"-test.run=^TestTreeHelper$"}, Env: map[string]string{"PROCESS_TREE_HELPER": "parent", "PROCESS_TREE_READY": ready}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(manager.StopAll)
	var child struct {
		PID     int
		Address string
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		data, _ := os.ReadFile(ready)
		if json.Unmarshal(data, &child) == nil && child.PID > 0 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if child.PID <= 0 {
		t.Fatal("descendant did not signal readiness")
	}
	t.Cleanup(func() {
		process, err := os.FindProcess(child.PID)
		if err == nil {
			process.Kill()
		}
	})
	connection, err := net.DialTimeout("tcp", child.Address, time.Second)
	if err != nil {
		t.Fatal("descendant was not alive before stop")
	}
	connection.Close()
	done := make(chan struct{})
	go func() { manager.StopAll(); close(done) }()
	select {
	case <-done:
	case <-time.After(8 * time.Second):
		t.Fatal("tree stop blocked on inherited output pipe")
	}
	select {
	case <-handle.Done:
	default:
		t.Fatal("parent was not reaped")
	}
	deadline = time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		connection, err = net.DialTimeout("tcp", child.Address, 100*time.Millisecond)
		if err != nil {
			break
		}
		connection.Close()
		time.Sleep(20 * time.Millisecond)
	}
	if err == nil {
		t.Fatal("descendant survived process-tree stop")
	}
	if runtime.GOOS == "windows" {
		output, err := exec.Command("tasklist", "/FI", "PID eq "+strconv.Itoa(child.PID), "/FO", "CSV", "/NH").Output()
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(output), `,"`+strconv.Itoa(child.PID)+`",`) {
			t.Fatal("descendant remains in Windows process table")
		}
	} else {
		output, err := exec.Command("ps", "-o", "stat=", "-p", strconv.Itoa(child.PID)).Output()
		state := strings.TrimSpace(string(output))
		if err == nil && state != "" && !strings.HasPrefix(state, "Z") {
			t.Fatal("descendant remains alive in POSIX process table")
		}
	}
}

func TestEnvironmentOverridesCaseInsensitiveWithoutTouchingParent(t *testing.T) {
	base := []string{"PATH=old", "Token=old", "KEEP=yes"}
	env := Environment(base, map[string]string{"PATH": "new", "TOKEN": "secret"})
	if len(env) != 3 || base[0] != "PATH=old" {
		t.Fatal("environment was not isolated")
	}
	for _, entry := range env {
		if entry == "PATH=old" || entry == "Token=old" {
			t.Fatal("inherited override remained")
		}
	}
}
