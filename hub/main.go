// phonecast hub: 云端中继。
// agent(电脑端)反向注册进来, viewer(手机B)按 配对码 撮合后, hub 对两条已鉴权
// 连接做纯字节对拷(不解析媒体内容), 并记录流量/会话状态供 HTTP 状态页查看。
//
// 安全模型:
//   - 所有 TCP 握手(agent 注册/viewer 接入/agent 会话)必须携带 -key, 常量时间比对
//   - 认证失败: 延迟 1s 后断开; 同 IP 失败 5 次锁定 10 分钟
//   - viewer 还须提供正确配对码(room)才能匹配到 agent
//   - 会话连接凭 16 字节随机 session id 认领, 一次有效
//   - 状态页 /status 同样要求密钥; 配对码打码显示
package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/json"
	"flag"
	"fmt"
	"html"
	"io"
	"log"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"
)

var (
	listenAddr = flag.String("listen", ":27190", "agent/viewer 接入的 TCP 监听地址")
	httpAddr   = flag.String("http", ":27191", "状态页 HTTP 监听地址")
	hubKey     = flag.String("key", "", "接入密钥 (必填)")
)

var startTime = time.Now()

func main() {
	flag.Parse()
	log.SetFlags(log.Ldate | log.Ltime)
	if *hubKey == "" {
		log.Fatal("[启动] 必须用 -key 指定接入密钥")
	}

	ln, err := net.Listen("tcp", *listenAddr)
	if err != nil {
		log.Fatalf("[启动] 监听 %s 失败: %v", *listenAddr, err)
	}
	log.Printf("[启动] hub 就绪: 接入 %s, 状态页 %s", *listenAddr, *httpAddr)

	go serveHTTP()

	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Fatalf("[接入] accept 失败: %v", err)
		}
		go handleConn(conn)
	}
}

// ---- 连接分发 ----

func handleConn(conn net.Conn) {
	if tc, ok := conn.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
		tc.SetKeepAlive(true)
		tc.SetKeepAlivePeriod(30 * time.Second)
	}
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))

	ip := remoteIP(conn)
	if authLimiter.locked(ip) {
		conn.Close()
		return
	}

	var magic [4]byte
	if _, err := io.ReadFull(conn, magic[:]); err != nil {
		conn.Close()
		return
	}

	// 观看端不再提交 hub 密钥: 真正的凭据(配对码/设备令牌)由 agent 端到端校验,
	// hub 只按设备名转发字节, 拿不到也用不了观看端的凭据。
	if magic == MagicViewer {
		if !sessionLimiter.allow(ip) {
			log.Printf("[限速] %s 建立会话过于频繁", ip)
			conn.Close()
			return
		}
		handleViewer(conn)
		return
	}

	// agent 注册 / agent 会话连接: 仍需 hub 密钥 (在配置文件里, 不用手输)
	gotKey, err := ReadLenPrefixed(conn)
	if err != nil {
		conn.Close()
		return
	}
	if subtle.ConstantTimeCompare([]byte(gotKey), []byte(*hubKey)) != 1 {
		authLimiter.fail(ip)
		log.Printf("[认证] %s 密钥错误 (magic=%q)", ip, magic)
		time.Sleep(time.Second)
		conn.Write([]byte{StatusBadKey})
		conn.Close()
		return
	}
	authLimiter.ok(ip)

	switch magic {
	case MagicAgent:
		handleAgent(conn)
	case MagicSession:
		handleSessionConn(conn)
	default:
		conn.Close()
	}
}

// ---- agent 注册连接 ----

type agentReg struct {
	room  string
	conn  net.Conn
	fw    *FrameWriter
	addr  string
	since time.Time
	busy  atomic.Bool
	gone  atomic.Bool
}

var (
	roomsMu sync.Mutex
	rooms   = map[string]*agentReg{}
)

func handleAgent(conn net.Conn) {
	room, err := ReadLenPrefixed(conn)
	if err != nil || room == "" {
		conn.Close()
		return
	}
	a := &agentReg{room: room, conn: conn, fw: NewFrameWriter(conn), addr: remoteIP(conn), since: time.Now()}

	roomsMu.Lock()
	if old := rooms[room]; old != nil {
		old.gone.Store(true)
		old.conn.Close() // 同配对码重复注册: 顶掉旧连接
	}
	rooms[room] = a
	roomsMu.Unlock()

	conn.Write([]byte{StatusOK})
	log.Printf("[电脑端] %s 注册, 配对码 %s", a.addr, maskRoom(room))

	defer func() {
		roomsMu.Lock()
		if rooms[room] == a {
			delete(rooms, room)
		}
		roomsMu.Unlock()
		conn.Close()
		log.Printf("[电脑端] %s 离线, 配对码 %s", a.addr, maskRoom(room))
	}()

	stopPing := make(chan struct{})
	defer close(stopPing)
	go func() { // 保活: 20s 一次 ping, agent 回 ping
		t := time.NewTicker(20 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-stopPing:
				return
			case <-t.C:
				if a.fw.WriteFrame(ChPing) != nil {
					conn.Close()
					return
				}
			}
		}
	}()

	for {
		conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		if _, _, err := ReadFrame(conn); err != nil {
			return // ping 应答或任何帧都算活跃; 出错即离线
		}
	}
}

// ---- viewer 接入 ----

type sessionInfo struct {
	room    string
	viewer  string
	started time.Time
	up      atomic.Int64 // viewer → agent (控制)
	down    atomic.Int64 // agent → viewer (媒体)
}

var (
	sessionsMu sync.Mutex
	sessions   = map[*sessionInfo]struct{}{}

	totalSessions atomic.Int64
	totalUp       atomic.Int64
	totalDown     atomic.Int64

	pendingMu sync.Mutex
	pending   = map[[16]byte]chan net.Conn{}
)

func handleViewer(conn net.Conn) {
	defer conn.Close()
	room, err := ReadLenPrefixed(conn)
	if err != nil {
		return
	}
	roomsMu.Lock()
	a := rooms[room]
	roomsMu.Unlock()
	if a == nil {
		conn.Write([]byte{StatusNoRoom})
		return
	}
	if !a.busy.CompareAndSwap(false, true) {
		conn.Write([]byte{StatusBusy})
		return
	}
	defer a.busy.Store(false)

	// 生成一次性 session id, 通知 agent 另拨一条会话连接进来认领
	var sid [16]byte
	rand.Read(sid[:])
	ch := make(chan net.Conn, 1)
	pendingMu.Lock()
	pending[sid] = ch
	pendingMu.Unlock()
	defer func() {
		pendingMu.Lock()
		delete(pending, sid)
		pendingMu.Unlock()
	}()

	if a.fw.WriteFrame(ChStart, sid[:]) != nil {
		conn.Write([]byte{StatusNoRoom})
		return
	}
	var agentConn net.Conn
	select {
	case agentConn = <-ch:
	case <-time.After(10 * time.Second):
		log.Printf("[观看端] %s 等待电脑端会话连接超时, 配对码 %s", remoteIP(conn), maskRoom(room))
		conn.Write([]byte{StatusHubErr})
		return
	}
	defer agentConn.Close()

	conn.SetReadDeadline(time.Time{})
	conn.Write([]byte{StatusOK})

	sess := &sessionInfo{room: room, viewer: remoteIP(conn), started: time.Now()}
	sessionsMu.Lock()
	sessions[sess] = struct{}{}
	sessionsMu.Unlock()
	totalSessions.Add(1)
	log.Printf("[会话] %s ↔ 配对码 %s 开始", sess.viewer, maskRoom(room))

	defer func() {
		sessionsMu.Lock()
		delete(sessions, sess)
		sessionsMu.Unlock()
		totalUp.Add(sess.up.Load())
		totalDown.Add(sess.down.Load())
		log.Printf("[会话] %s ↔ 配对码 %s 结束 (下行 %s, 上行 %s, 时长 %s)",
			sess.viewer, maskRoom(room), humanBytes(sess.down.Load()), humanBytes(sess.up.Load()),
			time.Since(sess.started).Round(time.Second))
	}()

	// 纯字节对拷, 任一方向断开即拆双侧
	done := make(chan struct{}, 2)
	go func() {
		io.Copy(&countWriter{conn, &sess.down}, agentConn)
		conn.Close()
		agentConn.Close()
		done <- struct{}{}
	}()
	go func() {
		io.Copy(&countWriter{agentConn, &sess.up}, conn)
		conn.Close()
		agentConn.Close()
		done <- struct{}{}
	}()
	<-done
	<-done
}

func handleSessionConn(conn net.Conn) {
	var sid [16]byte
	if _, err := io.ReadFull(conn, sid[:]); err != nil {
		conn.Close()
		return
	}
	pendingMu.Lock()
	ch := pending[sid]
	delete(pending, sid) // 一次有效
	pendingMu.Unlock()
	if ch == nil {
		conn.Close()
		return
	}
	conn.SetReadDeadline(time.Time{})
	ch <- conn
}

// ---- 认证失败限速 (每 IP: 10 分钟窗口内 5 次失败即锁定) ----

type limiter struct {
	mu    sync.Mutex
	fails map[string][]time.Time
}

var authLimiter = &limiter{fails: map[string][]time.Time{}}

// sessionLimiter: 观看端建会话的频率闸门 (每 IP 10 分钟 30 次)。
// 观看端无需 hub 密钥, 靠它挡住拿设备名刷 agent 的行为。
var sessionLimiter = &rateLimiter{hits: map[string][]time.Time{}}

type rateLimiter struct {
	mu   sync.Mutex
	hits map[string][]time.Time
}

func (l *rateLimiter) allow(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	cutoff := time.Now().Add(-10 * time.Minute)
	var recent []time.Time
	for _, t := range l.hits[ip] {
		if t.After(cutoff) {
			recent = append(recent, t)
		}
	}
	if len(recent) >= 30 {
		l.hits[ip] = recent
		return false
	}
	l.hits[ip] = append(recent, time.Now())
	return true
}

func (l *limiter) fail(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.fails[ip] = append(l.trimmed(ip), time.Now())
}

func (l *limiter) ok(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.fails, ip)
}

func (l *limiter) locked(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	recent := l.trimmed(ip)
	l.fails[ip] = recent
	return len(recent) >= 5
}

func (l *limiter) trimmed(ip string) []time.Time {
	cutoff := time.Now().Add(-10 * time.Minute)
	var out []time.Time
	for _, t := range l.fails[ip] {
		if t.After(cutoff) {
			out = append(out, t)
		}
	}
	return out
}

// ---- HTTP 状态页 ----

func serveHTTP() {
	mux := http.NewServeMux()
	mux.HandleFunc("/login", loginHandler)
	mux.HandleFunc("/logout", logoutHandler)
	mux.HandleFunc("/status", requireAuth(statusJSON, false))
	mux.HandleFunc("/", requireAuth(statusPage, true))
	if err := http.ListenAndServe(*httpAddr, mux); err != nil {
		log.Fatalf("[启动] HTTP 监听失败: %v", err)
	}
}

// ---- 登录会话 (浏览器 Cookie, 12 小时) ----

const sessionTTL = 12 * time.Hour

var (
	sessMu     sync.Mutex
	sessTokens = map[string]time.Time{}
)

func sessionValid(r *http.Request) bool {
	c, err := r.Cookie("pc_session")
	if err != nil {
		return false
	}
	sessMu.Lock()
	defer sessMu.Unlock()
	exp, ok := sessTokens[c.Value]
	if !ok {
		return false
	}
	if time.Now().After(exp) {
		delete(sessTokens, c.Value)
		return false
	}
	return true
}

func newSession(w http.ResponseWriter) {
	b := make([]byte, 32)
	rand.Read(b)
	token := fmt.Sprintf("%x", b)
	sessMu.Lock()
	now := time.Now()
	for t, exp := range sessTokens { // 顺手清过期
		if now.After(exp) {
			delete(sessTokens, t)
		}
	}
	sessTokens[token] = now.Add(sessionTTL)
	sessMu.Unlock()
	http.SetCookie(w, &http.Cookie{
		Name: "pc_session", Value: token, Path: "/",
		HttpOnly: true, SameSite: http.SameSiteLaxMode, MaxAge: int(sessionTTL.Seconds()),
	})
}

// requireAuth: 浏览器走 Cookie 登录; 脚本可用 Authorization: Bearer <密钥> 访问。
// wantLogin=true 时未登录渲染登录页, 否则返回 401。
func requireAuth(h http.HandlerFunc, wantLogin bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if sessionValid(r) {
			h(w, r)
			return
		}
		if auth := r.Header.Get("Authorization"); len(auth) > 7 && auth[:7] == "Bearer " &&
			subtle.ConstantTimeCompare([]byte(auth[7:]), []byte(*hubKey)) == 1 {
			h(w, r)
			return
		}
		if wantLogin {
			renderLogin(w, "")
			return
		}
		http.Error(w, "unauthorized (先在浏览器登录, 或带 Authorization: Bearer 密钥)", http.StatusUnauthorized)
	}
}

func loginHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Redirect(w, r, "/", http.StatusFound)
		return
	}
	ip, _, _ := net.SplitHostPort(r.RemoteAddr)
	if authLimiter.locked(ip) {
		http.Error(w, "尝试过多, 10 分钟后再试", http.StatusTooManyRequests)
		return
	}
	if subtle.ConstantTimeCompare([]byte(r.FormValue("key")), []byte(*hubKey)) != 1 {
		authLimiter.fail(ip)
		log.Printf("[认证] %s 状态页登录失败", ip)
		time.Sleep(time.Second)
		renderLogin(w, "密钥错误")
		return
	}
	authLimiter.ok(ip)
	newSession(w)
	http.Redirect(w, r, "/", http.StatusFound)
}

func logoutHandler(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie("pc_session"); err == nil {
		sessMu.Lock()
		delete(sessTokens, c.Value)
		sessMu.Unlock()
	}
	http.SetCookie(w, &http.Cookie{Name: "pc_session", Value: "", Path: "/", MaxAge: -1})
	http.Redirect(w, r, "/", http.StatusFound)
}

func renderLogin(w http.ResponseWriter, errMsg string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	msg := ""
	if errMsg != "" {
		msg = `<p style="color:#e77">` + html.EscapeString(errMsg) + `</p>`
	}
	fmt.Fprintf(w, `<!doctype html><meta charset=utf-8><title>PhoneCast Hub 登录</title>
<style>body{font-family:system-ui;background:#111;color:#ddd;display:flex;justify-content:center;align-items:center;height:100vh;margin:0}
form{background:#1b1b1b;padding:2em;border-radius:12px;text-align:center}
input{padding:.6em;margin:.8em 0;width:240px;background:#111;border:1px solid #444;color:#ddd;border-radius:6px}
button{padding:.6em 2em;background:#2d7dff;border:0;color:#fff;border-radius:6px;cursor:pointer}</style>
<form method=post action=/login><h2>PhoneCast Hub</h2>%s
<input type=password name=key placeholder="接入密钥" autofocus><br><button>登录</button></form>`, msg)
}

type statusData struct {
	Uptime   string        `json:"uptime"`
	Agents   []agentJSON   `json:"agents"`
	Sessions []sessionJSON `json:"sessions"`
	Totals   totalsJSON    `json:"totals"`
}
type agentJSON struct {
	Room  string `json:"room"`
	Addr  string `json:"addr"`
	Since string `json:"online_for"`
	Busy  bool   `json:"streaming"`
}
type sessionJSON struct {
	Room     string `json:"room"`
	Viewer   string `json:"viewer"`
	Duration string `json:"duration"`
	Down     string `json:"media_down"`
	Up       string `json:"control_up"`
}
type totalsJSON struct {
	Sessions int64  `json:"sessions"`
	Down     string `json:"media_down"`
	Up       string `json:"control_up"`
}

func collectStatus() statusData {
	d := statusData{Uptime: time.Since(startTime).Round(time.Second).String()}
	roomsMu.Lock()
	for _, a := range rooms {
		d.Agents = append(d.Agents, agentJSON{
			Room: maskRoom(a.room), Addr: a.addr,
			Since: time.Since(a.since).Round(time.Second).String(), Busy: a.busy.Load(),
		})
	}
	roomsMu.Unlock()
	var liveUp, liveDown int64
	sessionsMu.Lock()
	for s := range sessions {
		liveUp += s.up.Load()
		liveDown += s.down.Load()
		d.Sessions = append(d.Sessions, sessionJSON{
			Room: maskRoom(s.room), Viewer: s.viewer,
			Duration: time.Since(s.started).Round(time.Second).String(),
			Down:     humanBytes(s.down.Load()), Up: humanBytes(s.up.Load()),
		})
	}
	sessionsMu.Unlock()
	d.Totals = totalsJSON{
		Sessions: totalSessions.Load(),
		Down:     humanBytes(totalDown.Load() + liveDown),
		Up:       humanBytes(totalUp.Load() + liveUp),
	}
	return d
}

func statusJSON(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(collectStatus())
}

func statusPage(w http.ResponseWriter, _ *http.Request) {
	d := collectStatus()
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html><meta charset=utf-8><meta http-equiv=refresh content=3>
<title>PhoneCast Hub</title>
<style>body{font-family:system-ui;background:#111;color:#ddd;padding:2em;max-width:720px;margin:auto}
h1{font-size:1.3em}h2{font-size:1em;color:#8ab;margin-top:1.5em}
table{border-collapse:collapse;width:100%%}td,th{border-bottom:1px solid #333;padding:.4em .6em;text-align:left;font-size:.9em}
.muted{color:#777}.on{color:#7c7}</style>
<h1>PhoneCast Hub <span class=muted>运行 %s</span></h1>
<h2>在线 agent (%d)</h2><table><tr><th>配对码</th><th>来源</th><th>在线时长</th><th>状态</th></tr>`,
		html.EscapeString(d.Uptime), len(d.Agents))
	for _, a := range d.Agents {
		state := "空闲"
		if a.Busy {
			state = `<span class=on>投屏中</span>`
		}
		fmt.Fprintf(w, "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>",
			html.EscapeString(a.Room), html.EscapeString(a.Addr), html.EscapeString(a.Since), state)
	}
	fmt.Fprintf(w, `</table><h2>进行中会话 (%d)</h2><table><tr><th>配对码</th><th>观看端</th><th>时长</th><th>媒体下行</th><th>控制上行</th></tr>`, len(d.Sessions))
	for _, s := range d.Sessions {
		fmt.Fprintf(w, "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>",
			html.EscapeString(s.Room), html.EscapeString(s.Viewer), html.EscapeString(s.Duration),
			html.EscapeString(s.Down), html.EscapeString(s.Up))
	}
	fmt.Fprintf(w, `</table><h2>累计</h2><p>会话 %d 次 · 媒体下行 %s · 控制上行 %s</p>`,
		d.Totals.Sessions, html.EscapeString(d.Totals.Down), html.EscapeString(d.Totals.Up))
}

// ---- 工具 ----

type countWriter struct {
	w io.Writer
	n *atomic.Int64
}

func (c *countWriter) Write(p []byte) (int, error) {
	n, err := c.w.Write(p)
	c.n.Add(int64(n))
	return n, err
}

func maskRoom(room string) string {
	if len(room) <= 2 {
		return "***"
	}
	return room[:2] + "***"
}

func remoteIP(conn net.Conn) string {
	if host, _, err := net.SplitHostPort(conn.RemoteAddr().String()); err == nil {
		return host
	}
	return conn.RemoteAddr().String()
}

func humanBytes(n int64) string {
	switch {
	case n > 1<<30:
		return fmt.Sprintf("%.2f GB", float64(n)/(1<<30))
	case n > 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	case n > 1<<10:
		return fmt.Sprintf("%.1f KB", float64(n)/(1<<10))
	}
	return fmt.Sprintf("%d B", n)
}
