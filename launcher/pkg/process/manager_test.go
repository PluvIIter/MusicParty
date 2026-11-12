package process

import (
	"fmt"
	"os"
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
