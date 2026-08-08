// 直连入口的应用层防护。
//
// 局域网直连在公用网络(咖啡厅/机场)下也要能用, 所以不能只靠防火墙那一层。
// 这里做三件事, 彼此独立、任一层失效另外两层仍在:
//  1. 同网段校验: 只接受与本机处于同一子网的来源, 不依赖防火墙规则是否被改过
//  2. 每 IP 失败限速: 单个来源连错几次就冷却, 防止有人反复试码
//  3. 全局失败计数(见 auth.go): 达到阈值即作废配对码
//
// 第 2 条还有个容易忽略的作用: 没有它, 攻击者可以靠"故意连错"不断触发第 3 条,
// 把用户的配对码顶掉 —— 这是一种拒绝服务, 不是信息泄露, 但同样恼人。
package main

import (
	"log"
	"net"
	"sync"
	"time"
)

const (
	// 每 IP 阈值必须【小于】配对码作废阈值(auth.go 的 maxCodeFails=5), 否则单个来源
	// 就能靠故意连错把用户的配对码顶掉 —— 那是一种拒绝服务。取 3 留出余量。
	directMaxFails   = 3
	directFailWindow = 10 * time.Minute
)

// ipLimiter: 每来源 IP 的失败计数与冷却。
type ipLimiter struct {
	mu    sync.Mutex
	fails map[string][]time.Time
}

var directLimiter = &ipLimiter{fails: map[string][]time.Time{}}

func (l *ipLimiter) recent(ip string) []time.Time {
	cutoff := time.Now().Add(-directFailWindow)
	var out []time.Time
	for _, t := range l.fails[ip] {
		if t.After(cutoff) {
			out = append(out, t)
		}
	}
	return out
}

func (l *ipLimiter) blocked(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	r := l.recent(ip)
	l.fails[ip] = r
	return len(r) >= directMaxFails
}

func (l *ipLimiter) fail(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.fails[ip] = append(l.recent(ip), time.Now())
}

func (l *ipLimiter) ok(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.fails, ip)
}

// sameSubnet: 来源是否与本机某个网卡处于同一子网。
// 防火墙的 LocalSubnet 已经做过一次, 这里再做一次是因为规则可能被改动或没加上,
// 而"只服务本地网络"是这个功能的固有前提, 不该依赖外部配置来保证。
func sameSubnet(remote net.IP) bool {
	if remote == nil {
		return false
	}
	if remote.IsLoopback() {
		return true // adb reverse / 本机自测
	}
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.Contains(remote) {
				return true
			}
		}
	}
	return false
}

// allowDirect: 直连连接的准入检查, 返回是否放行。
func allowDirect(conn net.Conn) bool {
	host, _, err := net.SplitHostPort(conn.RemoteAddr().String())
	if err != nil {
		return false
	}
	if !sameSubnet(net.ParseIP(host)) {
		log.Printf("[直连] 拒绝 %s: 不在同一子网", host)
		return false
	}
	if directLimiter.blocked(host) {
		log.Printf("[直连] 拒绝 %s: 失败次数过多, 冷却中", host)
		return false
	}
	return true
}

func remoteHost(conn net.Conn) string {
	host, _, err := net.SplitHostPort(conn.RemoteAddr().String())
	if err != nil {
		return conn.RemoteAddr().String()
	}
	return host
}
