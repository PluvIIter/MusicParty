//go:build !windows

package main

// notifyDialog 只在 Windows 上弹系统对话框；其它平台保持无操作。
func notifyDialog(text, title string) {}
