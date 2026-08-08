//go:build windows

// Windows 防火墙与网络分类。
//
// 设计取向: 局域网直连在所有网络类型下都要可用(Windows 常把家用 WiFi 判成"公用",
// 只放行 专用/域 会让功能在多数人家里直接失效)。因此放行覆盖全部类型, 但把暴露面
// 压到最小, 并且用户随时可关:
//   - 仅放行本程序, 且 remoteip=LocalSubnet(跨网段/公网进不来)
//   - 应用层再做一次同网段校验 + 每 IP 失败限速(见 engine.go)
//   - 连上后必须过配对码 HMAC 认证; 托盘可一键关闭局域网监听
// 处在公用网络时会明确提示, 让用户知道自己正暴露在什么环境里。
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
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
// 只覆盖 专用/域 网络 —— 公用网络交由系统策略继续拦截。
func addFirewallRule() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	args := fmt.Sprintf(
		`advfirewall firewall add rule name="%s" dir=in action=allow program="%s" enable=yes profile=any remoteip=LocalSubnet`,
		firewallRuleName, exe)
	return runElevated("netsh", args)
}

// runElevated: 经 PowerShell 提权执行(弹 UAC)。
func runElevated(program, args string) error {
	ps := exec.Command("powershell", "-NoProfile", "-Command",
		fmt.Sprintf(`Start-Process %s -ArgumentList '%s' -Verb RunAs -WindowStyle Hidden -Wait`, program, args))
	hideWindow(ps)
	if out, err := ps.CombinedOutput(); err != nil {
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

type netProfile struct {
	InterfaceAlias  string `json:"InterfaceAlias"`
	NetworkCategory any    `json:"NetworkCategory"` // 可能是数字或字符串
}

// currentNetwork: 返回承载本机局域网地址的网卡别名与它的网络类别。
// 类别为 Public 时, 按系统策略局域网直连不会被放行。
func currentNetwork() (alias, category string) {
	ip := firstLanIP()
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.String() == ip {
				alias = ifc.Name
			}
		}
	}
	if alias == "" {
		return "", ""
	}
	cmd := exec.Command("powershell", "-NoProfile", "-Command",
		"Get-NetConnectionProfile | Select-Object InterfaceAlias,NetworkCategory | ConvertTo-Json -Compress")
	hideWindow(cmd)
	out, err := cmd.Output()
	if err != nil {
		return alias, ""
	}
	raw := strings.TrimSpace(string(out))
	var list []netProfile
	if strings.HasPrefix(raw, "{") { // 只有一个配置文件时不是数组
		var one netProfile
		if json.Unmarshal([]byte(raw), &one) == nil {
			list = []netProfile{one}
		}
	} else {
		json.Unmarshal([]byte(raw), &list)
	}
	for _, p := range list {
		if p.InterfaceAlias == alias {
			return alias, categoryName(p.NetworkCategory)
		}
	}
	return alias, ""
}

// categoryName: PowerShell 可能给出数字枚举(0=Public 1=Private 2=Domain)或名字。
func categoryName(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case float64:
		switch int(x) {
		case 0:
			return "Public"
		case 1:
			return "Private"
		case 2:
			return "DomainAuthenticated"
		}
	}
	return ""
}

func isPublicNetwork(category string) bool { return category == "Public" }

// setNetworkPrivate: 把指定网卡的网络类别改为「专用」(需管理员)。
// 这是系统层面的安全设定, 只在用户明确要求时才做。
func setNetworkPrivate(alias string) error {
	return runElevated("powershell",
		fmt.Sprintf(`-NoProfile -Command "Set-NetConnectionProfile -InterfaceAlias ''%s'' -NetworkCategory Private"`, alias))
}

// checkFirewall: 启动时体检一次, 把"局域网为什么不通"讲清楚。
func checkFirewall() {
	if *listen == "" {
		return
	}
	alias, category := currentNetwork()
	switch {
	case !firewallRuleExists():
		log.Print("[防火墙] 未放行局域网直连 —— 托盘菜单「允许局域网访问」可添加(仅本程序、仅同网段)")
	case isPublicNetwork(category):
		log.Printf("[防火墙] 局域网直连已放行。注意: 当前网络「%s」是公用网络, "+
			"同网段设备可以探测到本机端口(仍需配对码才能连)。在不信任的网络里可在托盘关闭「局域网直连」。", alias)
	default:
		log.Printf("[防火墙] 局域网直连已放行 (网络「%s」类别 %s)", alias, category)
	}
}
