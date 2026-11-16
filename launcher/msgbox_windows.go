//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

var (
	user32          = syscall.NewLazyDLL("user32.dll")
	messageBoxWProc = user32.NewProc("MessageBoxW")
)

// notifyDialog 在窗口系统起来之前用系统对话框提示，避免「双击后毫无反应」。
func notifyDialog(text, title string) {
	textPtr, _ := syscall.UTF16PtrFromString(text)
	titlePtr, _ := syscall.UTF16PtrFromString(title)
	const mbIconError = 0x00000010
	_, _, _ = messageBoxWProc.Call(0, uintptr(unsafe.Pointer(textPtr)), uintptr(unsafe.Pointer(titlePtr)), mbIconError)
}
