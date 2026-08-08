//go:build windows

// Windows 防火墙: 局域网直连要求 27184 入站放行, 否则手机连过来会直接超时。
// 默认拒绝是静默的 —— 表现就是"扫码连不上", 所以这里主动检测并提供一键放行。
package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"strings"
)

const firewallRuleName = "PhoneCast 局域网直连"

// firewallRuleExists: 查是否已放行(按规则名)。
func firewallRuleExists() bool {
	cmd := exec.Command("netsh", "advfirewall", "firewall", "show", "rule",
		"name="+firewallRuleName)
	hideWindow(cmd)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return false
	}
	// 未命中时 netsh 输出 "No rules match the specified criteria."
	return !strings.Contains(strings.ToLower(string(out)), "no rules match")
}

// addFirewallRule: 以管理员身份添加入站放行规则(会弹 UAC)。
func addFirewallRule() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	// profile=any: Windows 常把家里的 WiFi 判成"公用网络", 只放行 private/domain
	// 的话规则形同虚设(手机连过来仍然超时)。
	// remoteip=LocalSubnet: 把放行范围压到"同一网段", 跨网段/公网都进不来;
	// 加上只放行本程序、且连上后还要过配对码认证, 暴露面已经很小。
	args := fmt.Sprintf(
		`advfirewall firewall add rule name="%s" dir=in action=allow program="%s" enable=yes profile=any remoteip=LocalSubnet`,
		firewallRuleName, exe)
	// 经 PowerShell 提权; -Verb RunAs 会弹出 UAC 确认框
	ps := exec.Command("powershell", "-NoProfile", "-Command",
		fmt.Sprintf(`Start-Process netsh -ArgumentList '%s' -Verb RunAs -WindowStyle Hidden -Wait`, args))
	hideWindow(ps)
	if out, err := ps.CombinedOutput(); err != nil {
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// checkFirewall: 启动时检查一次, 没放行就提示(只在启用了局域网直连时才有意义)。
func checkFirewall() {
	if *listen == "" {
		return
	}
	if firewallRuleExists() {
		log.Print("[防火墙] 局域网直连已放行")
		return
	}
	log.Print("[防火墙] 未检测到放行规则, 手机走局域网可能连不上 —— 可在托盘菜单点「允许局域网访问」")
}
