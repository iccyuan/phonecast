// engine: agent 的可启停内核, 供托盘菜单控制。
// Start 后: 等设备 → push server → 起直连监听 + hub 注册; Stop 取消 ctx 并关掉
// 全部被跟踪的连接/监听器, 进行中的投屏会话随连接断开经既有 defer 链自然清理。
package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"slices"
	"strings"
	"sync"
	"time"
)

var errBadKey = errors.New("hub 拒绝: 密钥错误")

type engine struct {
	adb, jar string

	mu      sync.Mutex
	running bool
	cancel  context.CancelFunc
	conns   map[io.Closer]struct{}
	state   string
	onState func() // 托盘刷新回调 (可为 nil)
}

func newEngine(adb, jar string) *engine {
	return &engine{adb: adb, jar: jar, conns: map[io.Closer]struct{}{}, state: "未启动"}
}

func (e *engine) State() (bool, string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.running, e.state
}

// refreshState: 配对码等信息变化时重发一次当前状态, 触发托盘刷新。
func (e *engine) refreshState() {
	running, s := e.State()
	if running {
		e.setState(s)
	}
}

func (e *engine) setState(s string) {
	e.mu.Lock()
	e.state = s
	cb := e.onState
	e.mu.Unlock()
	log.Printf("[状态] %s", s)
	if cb != nil {
		cb()
	}
}

func (e *engine) track(c io.Closer) {
	e.mu.Lock()
	e.conns[c] = struct{}{}
	e.mu.Unlock()
}

func (e *engine) untrack(c io.Closer) {
	e.mu.Lock()
	delete(e.conns, c)
	e.mu.Unlock()
}

func (e *engine) Start() {
	e.mu.Lock()
	if e.running {
		e.mu.Unlock()
		return
	}
	var ctx context.Context
	ctx, e.cancel = context.WithCancel(context.Background())
	e.running = true
	e.mu.Unlock()
	go e.run(ctx)
}

func (e *engine) Stop() {
	e.mu.Lock()
	if !e.running {
		e.mu.Unlock()
		return
	}
	e.running = false
	e.cancel()
	closers := make([]io.Closer, 0, len(e.conns))
	for c := range e.conns {
		closers = append(closers, c)
	}
	e.mu.Unlock()
	for _, c := range closers {
		c.Close()
	}
	e.setState("已停止")
}

func (e *engine) Restart() {
	e.Stop()
	time.Sleep(500 * time.Millisecond)
	e.Start()
}

func (e *engine) run(ctx context.Context) {
	e.setState("等待手机A 接入 (USB 调试)...")
	if !e.waitForDevice(ctx) {
		return
	}
	if out, err := adbRun(e.adb, "push", e.jar, remoteJarPath); err != nil {
		log.Printf("adb push 失败: %v\n%s", err, out)
		alertf("adb push 失败: %v", err)
		go e.Stop()
		return
	}
	go probeEncoders(e.adb) // 探一次: 这台手机能不能硬编 H.265
	banner()
	go checkFirewall() // 局域网连不上最常见的原因就是这条规则没加
	if *listen != "" {
		go e.serveDirect(ctx)
	}
	if *hubAddr != "" {
		go e.hubLoop(ctx)
	}
	e.setState("运行中 · 设备名 " + *room)
}

// waitForDevice: 没插手机时等待; 多台设备自动选第一台。返回 false 表示被 Stop 打断。
func (e *engine) waitForDevice(ctx context.Context) bool {
	waiting := false
	for ctx.Err() == nil {
		out, err := adbRun(e.adb, "devices")
		if err != nil {
			log.Printf("运行 adb 失败: %v\n%s", err, out)
			alertf("运行 adb 失败: %v", err)
			go e.Stop()
			return false
		}
		ready, unauthorized := parseAdbDevices(out)
		if *serial != "" {
			if slices.Contains(ready, *serial) {
				rememberDevice(e.adb, *serial)
				return true
			}
			// 配置里指定的手机不在场: 等它, 不要擅自换一台
		} else if len(ready) > 0 {
			if len(ready) > 1 {
				log.Printf("检测到 %d 台手机, 先用第一台 (托盘菜单「选择被投屏手机」可切换)", len(ready))
			}
			*serial = ready[0]
			rememberDevice(e.adb, *serial)
			return true
		}
		if !waiting {
			waiting = true
			e.setState("等待手机A: 请 USB 连接并开启 USB 调试")
		}
		if unauthorized {
			e.setState("请在手机A 上点「允许 USB 调试」")
		}
		select {
		case <-ctx.Done():
			return false
		case <-time.After(3 * time.Second):
		}
	}
	return false
}

// ---- 直连模式 ----

func (e *engine) serveDirect(ctx context.Context) {
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Printf("[直连] 监听 %s 失败 (端口被占用?): %v", *listen, err)
		return
	}
	log.Printf("[直连] 已监听 %s", *listen)
	e.track(ln)
	defer func() { e.untrack(ln); ln.Close() }()
	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() == nil {
				log.Printf("[直连] accept 失败: %v", err)
			}
			return
		}
		go e.handleDirectViewer(conn)
	}
}

func (e *engine) handleDirectViewer(conn net.Conn) {
	e.track(conn)
	defer func() { e.untrack(conn); conn.Close() }()
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	peer := conn.RemoteAddr().String()
	if !allowDirect(conn) { // 同网段校验 + 每 IP 失败冷却
		return
	}
	log.Printf("[直连] 收到来自 %s 的连接", peer)

	var magic [4]byte
	if _, err := io.ReadFull(conn, magic[:]); err != nil {
		log.Printf("[直连] %s 读握手失败: %v", peer, err)
		return
	}
	if magic != MagicViewer {
		log.Printf("[直连] %s 协议头不对 (%q), 可能是端口扫描或版本不匹配", peer, magic)
		return
	}
	gotRoom, err := ReadLenPrefixed(conn)
	if err != nil {
		log.Printf("[直连] %s 读设备名失败: %v", peer, err)
		return
	}
	if gotRoom != *room { // 设备名只是路由标识, 真正的门在下面的配对码认证
		log.Printf("[直连] %s 设备名不符: 收到 %q, 本机是 %q", peer, gotRoom, *room)
		conn.Write([]byte{StatusNoRoom})
		return
	}
	if !sessionMu.TryLock() {
		log.Printf("[直连] %s 被拒: 已有观看端在连", peer)
		conn.Write([]byte{StatusBusy})
		return
	}
	defer sessionMu.Unlock()

	conn.SetReadDeadline(time.Time{})
	conn.Write([]byte{StatusOK})
	if !authenticate(conn) {
		directLimiter.fail(remoteHost(conn))
		log.Printf("[直连] %s 认证失败, 断开", peer)
		return
	}
	directLimiter.ok(remoteHost(conn))
	log.Printf("[直连] 观看端 %s 接入", peer)
	e.runWatched(conn, true) // 局域网直连: 带宽充裕, 可以给高画质
}

// ---- 中继模式 ----

func (e *engine) hubLoop(ctx context.Context) {
	for ctx.Err() == nil {
		err := e.hubRegister()
		if ctx.Err() != nil {
			return
		}
		if errors.Is(err, errBadKey) {
			e.setState("密钥错误, 已停止")
			alertf("hub 拒绝连接: 密钥错误。\n请打开配置文件修正 key, 保存后点「重新运行」。")
			go e.Stop()
			return
		}
		log.Printf("[中继] %v, 5 秒后重连", err)
		select {
		case <-ctx.Done():
			return
		case <-time.After(5 * time.Second):
		}
	}
}

func (e *engine) hubRegister() error {
	conn, err := net.DialTimeout("tcp", *hubAddr, 5*time.Second)
	if err != nil {
		return fmt.Errorf("连接 hub 失败: %w", err)
	}
	e.track(conn)
	defer func() { e.untrack(conn); conn.Close() }()

	if err := WriteRoomHandshake(conn, MagicAgent, *key, *room); err != nil {
		return err
	}
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	status, err := ReadStatus(conn)
	if err != nil {
		return fmt.Errorf("hub 无响应: %w", err)
	}
	if status == StatusBadKey {
		return errBadKey
	}
	log.Printf("[中继] 已注册到 hub %s, 设备名: %s", *hubAddr, *room)

	fw := NewFrameWriter(conn)
	for {
		conn.SetReadDeadline(time.Now().Add(60 * time.Second)) // hub 每 20s ping 一次
		ch, payload, err := ReadFrame(conn)
		if err != nil {
			return fmt.Errorf("注册连接断开: %w", err)
		}
		switch ch {
		case ChPing:
			fw.WriteFrame(ChPing)
		case ChStart:
			if len(payload) == 16 {
				go e.hubSession(append([]byte(nil), payload...))
			}
		}
	}
}

func (e *engine) hubSession(sessionID []byte) {
	if !sessionMu.TryLock() {
		return // hub 侧有 busy 拦截, 这里兜底
	}
	defer sessionMu.Unlock()

	conn, err := net.DialTimeout("tcp", *hubAddr, 5*time.Second)
	if err != nil {
		log.Printf("[中继] 会话连接失败: %v", err)
		return
	}
	e.track(conn)
	defer func() { e.untrack(conn); conn.Close() }()
	if err := WriteHandshake(conn, MagicSession, *key, sessionID); err != nil {
		return
	}
	log.Printf("[中继] 会话开始 (id=%x)", sessionID[:4])
	if authenticate(conn) {
		e.runWatched(conn, false) // 经公网中继: 码率受配置上限约束
	} else {
		log.Printf("[中继] 认证失败, 断开 (id=%x)", sessionID[:4])
	}
	log.Printf("[中继] 会话结束 (id=%x)", sessionID[:4])
}

// rememberDevice: 记下当前被投屏手机的机型, 供手机端列表显示与托盘状态。
func rememberDevice(adb, serial string) {
	for _, d := range listAdbDevices(adb) {
		if d.Serial == serial {
			setCurrentDeviceLabel(d.Label())
			return
		}
	}
	setCurrentDeviceLabel(serial)
}

// SetLanDirect: 开关局域网直连监听 (托盘菜单), 写回配置并重启。
// 在公用网络里想彻底消除本机暴露时用它 —— 关掉后只走中继。
func (e *engine) SetLanDirect(on bool) {
	if on {
		*listen = defaultListen
	} else {
		*listen = ""
	}
	if loadedCfg != nil {
		loadedCfg.Listen = *listen
		saveConfig(configPath(), loadedCfg)
	}
	log.Printf("[设置] 局域网直连已%s", map[bool]string{true: "开启", false: "关闭"}[on])
	e.Restart()
}

// SwitchDevice: 切换被投屏手机 (托盘菜单), 会重启会话并写回配置。
func (e *engine) SwitchDevice(serialID string) {
	*serial = serialID
	if loadedCfg != nil {
		loadedCfg.Serial = serialID
		saveConfig(configPath(), loadedCfg)
	}
	rememberDevice(e.adb, serialID)
	log.Printf("[设备] 切换到 %s (%s)", deviceLabel(), serialID)
	e.Restart()
}

// runWatched: 包一层状态提示的 runSession。
// scrcpy-server (cleanup=true) 启动后会删掉自己的 jar, 所以每次会话前必须重新 push,
// 否则第二次会话 app_process 因 CLASSPATH 失效直接 Abort。
func (e *engine) runWatched(conn net.Conn, viaLan bool) {
	e.setState("投屏中 · 设备名 " + *room)
	fw := NewFrameWriter(conn)
	// 告诉手机端"你正在看哪台手机", 列表里显示真实机型而不是设备名
	fw.WriteFrame(ChDeviceInfo, []byte(deviceLabel()))
	// 把当前局域网地址同步过去: 换了 WiFi 后手机存的旧 IP 会失效, 靠这个自愈 ——
	// 即便这次是走中继连上的, 下次也能自动改走局域网。
	if *listen != "" {
		var lan []string
		for _, ip := range lanIPs() {
			lan = append(lan, ip+*listen)
		}
		if len(lan) > 0 {
			fw.WriteFrame(ChLanAddrs, []byte(strings.Join(lan, ",")))
		}
	}

	// 读观看端能力(屏幕尺寸/H.265/要不要音频), 决定这次会话怎么编码
	audioMuted.Store(false)
	info := readClientInfo(conn, func() (byte, []byte, error) { return ReadFrame(conn) })
	runSession(e.adb, conn, info, viaLan)
	if running, _ := e.State(); running {
		e.setState("运行中 · 设备名 " + *room)
	}
}
