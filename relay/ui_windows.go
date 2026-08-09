//go:build windows

// Windows 交互层: 无窗口(windowsgui)运行下的 控制台附加/弹窗/剪贴板/单实例/隐藏子进程窗口。
package main

import (
	"encoding/base64"
	"fmt"
	"log"
	"os"
	"os/exec"
	"syscall"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	user32         = windows.NewLazySystemDLL("user32.dll")
	kernel32       = windows.NewLazySystemDLL("kernel32.dll")
	procMessageBox = user32.NewProc("MessageBoxW")
	procAttachCon  = kernel32.NewProc("AttachConsole")
)

const (
	mbOK          = 0x0
	mbOKCancel    = 0x1
	mbIconInfo    = 0x40
	mbIconWarning = 0x30
	idOK          = 1
)

// attachParentConsole: 从终端启动时接上父进程控制台, 让日志能打回终端。
// 双击启动(无父控制台)返回 false。
func attachParentConsole() bool {
	const attachParentProcess = ^uintptr(0) // ATTACH_PARENT_PROCESS (-1)
	if r, _, _ := procAttachCon.Call(attachParentProcess); r == 0 {
		return false
	}
	for _, f := range []struct {
		name string
		std  **os.File
	}{{"stdout", &os.Stdout}, {"stderr", &os.Stderr}} {
		h, err := os.OpenFile("CONOUT$", os.O_WRONLY, 0)
		if err == nil {
			*f.std = h
		}
	}
	if in, err := os.OpenFile("CONIN$", os.O_RDONLY, 0); err == nil {
		os.Stdin = in
	}
	fmt.Fprintln(os.Stderr) // 与 shell 提示符隔开
	return true
}

func messageBox(title, text string, flags uint32) int {
	t, _ := windows.UTF16PtrFromString(text)
	c, _ := windows.UTF16PtrFromString(title)
	r, _, _ := procMessageBox.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), uintptr(flags))
	return int(r)
}

// alertf: 需要用户注意的错误 —— 有控制台就打印, 没有就弹窗。
func alertf(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	log.Printf("[提示] %s", msg)
	if !hasConsole {
		messageBox("PhoneCast", msg, mbOK|mbIconWarning)
	}
}

// die: 致命错误, 提示后退出。
func die(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	log.Printf("[致命] %s", msg)
	if hasConsole {
		fmt.Fprintln(os.Stderr, msg)
	} else {
		messageBox("PhoneCast", msg, mbOK|mbIconWarning)
	}
	os.Exit(1)
}

// ensureSingleInstance: 防止双开 (两个实例会抢 adb forward 端口与直连端口)。
func ensureSingleInstance() {
	name, _ := windows.UTF16PtrFromString("PhoneCastAgentMutex")
	windows.CreateMutex(nil, false, name)
	if windows.GetLastError() == windows.ERROR_ALREADY_EXISTS {
		die("PhoneCast 已在运行 (看任务栏托盘图标), 不要重复启动")
	}
}

// hideWindow: windowsgui 程序启动控制台子进程(adb)时不闪黑窗。
func hideWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
}

// setClipboard: 经 PowerShell -EncodedCommand 写剪贴板 (双重 base64, 彻底绕开引号/编码问题)。
func setClipboard(text string) error {
	inner := "Set-Clipboard -Value ([System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('" +
		base64.StdEncoding.EncodeToString([]byte(text)) + "')))"
	u := utf16.Encode([]rune(inner))
	raw := make([]byte, len(u)*2)
	for i, c := range u {
		raw[i*2] = byte(c)
		raw[i*2+1] = byte(c >> 8)
	}
	cmd := exec.Command("powershell", "-NoProfile", "-EncodedCommand", base64.StdEncoding.EncodeToString(raw))
	hideWindow(cmd)
	return cmd.Run()
}

func openEditor(path string) {
	cmd := exec.Command("notepad.exe", path)
	cmd.Start()
}

// openEditorWait: 打开记事本并等它关闭 (首次配置向导用)。
func openEditorWait(path string) {
	cmd := exec.Command("notepad.exe", path)
	cmd.Run()
}

func openBrowser(url string) {
	cmd := exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	hideWindow(cmd)
	cmd.Start()
}
