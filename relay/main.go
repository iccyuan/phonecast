// phonecast agent: 电脑端, 无窗口托盘应用。
// 职责: adb push scrcpy-server 到手机A 并启动(video+audio+control 三条 socket),
// 按 v2 协议(见 proto.go)把媒体流下发给观看端、把控制消息注入回手机A。
// 观看端接入两种方式, 可同时开启:
//   - 直连: 手机B 连本机 -listen 端口(同局域网/adb reverse)
//   - 中继: agent 反向连到云端 hub(-hub), 手机B 从公网连 hub, 按配对码撮合
// 双击运行 → 任务栏托盘图标右键: 启动/停止/重新运行/复制连接信息/日志/退出。
// 从终端启动则同时把日志打到终端; 日志始终写 exe 旁的 phonecast.log。
package main

import (
	"crypto/rand"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	serverVersion = "2.7"
	remoteJarPath = "/data/local/tmp/phonecast-scrcpy-server.jar"
)

var (
	adbPath   = flag.String("adb", "", "adb 路径 (默认: PATH 中的 adb, 找不到再试 D:\\Develop\\Android\\platform-tools\\adb.exe)")
	serial    = flag.String("s", "", "adb 设备序列号 (多台设备时必填)")
	listen    = flag.String("listen", ":27184", "直连模式监听地址, 空字符串关闭直连")
	hubAddr   = flag.String("hub", "", "hub 地址 host:port, 设置后启用中继模式")
	key       = flag.String("key", "", "接入密钥: 直连时校验观看端, 中继时提交给 hub (必填)")
	room      = flag.String("room", "", "配对码, 手机B 用它找到本机 (默认随机生成)")
	localPort = flag.Int("local-port", 27183, "adb forward 用的本地端口")
	serverJar = flag.String("server", "", "scrcpy-server 文件路径 (默认: exe 同目录下的 scrcpy-server-v2.7)")
	maxSize   = flag.Int("max-size", 1440, "视频长边最大像素 (0=原始分辨率); 仅在观看端未上报屏幕尺寸时用")
	resolution = flag.String("resolution", "auto", "分辨率策略: auto=按观看端屏幕自适应, original=被投屏手机原始分辨率")
	videoCodec = flag.String("video-codec", "auto", "视频编码: auto=两端都支持就用 h265, 否则 h264; 也可强制 h264")
	bitRate   = flag.Int("bit-rate", 8_000_000, "视频码率 bps (走公网中继建议 2000000-4000000)")
	maxFps    = flag.Int("max-fps", 60, "最大帧率")
	audio     = flag.Bool("audio", true, "转发音频 (手机A 需 Android 11+, AAC 128kbps)")
)

// 同一时刻只允许一个观看会话(scrcpy-server 单实例)。
var sessionMu sync.Mutex

// hasConsole: 从终端启动(已附加父进程控制台)时为 true, 决定 向导/报错 用文本还是弹窗。
var hasConsole bool

// logSink: 日志汇 (文件 + 可选控制台), scrcpy server 输出也写这里。
var logSink io.Writer = os.Stderr

func main() {
	flag.Parse()
	hasConsole = attachParentConsole()
	setupLogging()
	ensureSingleInstance()

	applyConfig(loadConfig())
	if *key == "" {
		die("缺少接入密钥: 在 phonecast.json 里填 key, 或用 -key 参数")
	}
	if *room == "" {
		*room = randomToken(3) // 6 位 hex
	}
	if *listen == "" && *hubAddr == "" {
		die("直连(listen)与中继(hub)至少启用一个, 检查 phonecast.json")
	}

	eng := newEngine(resolveAdb(), resolveJar())
	initAuth(loadedCfg.Devices, func() { eng.refreshState() })
	runTray(eng) // 阻塞直到菜单点「退出」
}

func setupLogging() {
	f, err := os.Create(logPath())
	if err == nil {
		if hasConsole {
			logSink = io.MultiWriter(os.Stderr, f)
		} else {
			logSink = f
		}
	}
	log.SetFlags(log.Ltime)
	log.SetOutput(logSink)
}

func logPath() string {
	exe, err := os.Executable()
	if err != nil {
		return "phonecast.log"
	}
	return filepath.Join(filepath.Dir(exe), "phonecast.log")
}

func banner() {
	lines := []string{"手机B 打开 PhoneCast, 填以下信息:"}
	if *hubAddr != "" {
		lines = append(lines, fmt.Sprintf("  地址   %s", *hubAddr))
	}
	if *listen != "" {
		lines = append(lines, fmt.Sprintf("  地址   %s%s  (局域网直连)", firstLanIP(), *listen))
	}
	lines = append(lines, "  设备名 "+*room, "  配对码 "+pairCode()+" (6 位数字, 首次配对用)")
	for _, l := range lines {
		log.Print(l)
	}
}

// connInfoText: 托盘「复制连接信息」的剪贴板内容。
// 第一行给出 phonecast:// 链接 —— 把这段文本发到手机后点链接即可直接连,
// 比照着念地址/配对码省事 (电脑与手机的剪贴板并不互通)。
func connInfoText() string {
	var b strings.Builder
	fmt.Fprintf(&b, "PhoneCast 连接链接(手机上点开即连):\r\n%s\r\n\r\n", pairURI())
	if *hubAddr != "" {
		fmt.Fprintf(&b, "地址: %s\r\n", *hubAddr)
	}
	if *listen != "" {
		fmt.Fprintf(&b, "地址(局域网): %s%s\r\n", firstLanIP(), *listen)
	}
	fmt.Fprintf(&b, "设备名: %s\r\n配对码: %s\r\n", *room, pairCode())
	return b.String()
}

func parseAdbDevices(out string) (ready []string, unauthorized bool) {
	for _, line := range strings.Split(out, "\n")[1:] {
		fields := strings.Fields(line)
		if len(fields) != 2 {
			continue
		}
		switch fields[1] {
		case "device":
			ready = append(ready, fields[0])
		case "unauthorized":
			unauthorized = true
		}
	}
	return
}

// ---- scrcpy 会话 ----

// runSession: 启动 scrcpy-server, 把三条 socket 桥接到 viewerConn 上的 v2 帧流。
// 任一关键通路断开即整体清理; 音频通路失败只降级不拆会话。
func runSession(adb string, viewerConn net.Conn, info clientInfo, viaLan bool) {
	scid := randomToken(4)
	forwardSpec := fmt.Sprintf("tcp:%d", *localPort)
	if out, err := adbRun(adb, "forward", forwardSpec, "localabstract:scrcpy_"+scid); err != nil {
		log.Printf("adb forward 失败: %v\n%s", err, out)
		return
	}
	defer adbRun(adb, "forward", "--remove", forwardSpec)

	codec := pickCodec(info)
	size := pickMaxSize(info)
	rate := pickBitRate(size, codec, viaLan)
	wantAudio := *audio && info.Audio
	log.Printf("[编码] %s, 长边 %d(%s), %d kbps, 音频 %v, 路径 %s",
		codec, size, *resolution, rate/1000, wantAudio,
		map[bool]string{true: "局域网", false: "中继"}[viaLan])

	args := append(adbBaseArgs(), "shell",
		"CLASSPATH="+remoteJarPath, "app_process", "/", "com.genymobile.scrcpy.Server", serverVersion,
		"scid="+scid, "log_level=info",
		"video=true", "video_codec="+codec,
		fmt.Sprintf("audio=%v", wantAudio), "audio_codec=aac",
		fmt.Sprintf("max_size=%d", size),
		fmt.Sprintf("video_bit_rate=%d", rate),
		fmt.Sprintf("max_fps=%d", *maxFps),
		"tunnel_forward=true", "control=true", "cleanup=true",
		"send_device_meta=false", "send_dummy_byte=true",
		"send_codec_meta=true", "send_frame_meta=true",
	)
	// scrcpy-server 快速重启时偶发启动即崩("Aborted"), 失败重试一次
	var srv *exec.Cmd
	var videoConn net.Conn
	for attempt := 1; ; attempt++ {
		srv = exec.Command(adb, args...)
		srv.Stdout = prefixWriter("[server] ")
		srv.Stderr = prefixWriter("[server] ")
		hideWindow(srv) // windowsgui 下防止 adb 弹出控制台窗口
		if err := srv.Start(); err != nil {
			log.Printf("启动 scrcpy-server 失败: %v", err)
			return
		}
		var err error
		// tunnel_forward 模式连接顺序: video(带 1 字节就绪 dummy) → audio(若开) → control
		videoConn, err = dialWithDummy(*localPort, 8*time.Second)
		if err == nil {
			break
		}
		srv.Process.Kill()
		srv.Wait()
		if attempt >= 2 {
			log.Printf("连接 video socket 失败: %v, 放弃", err)
			return
		}
		log.Printf("连接 video socket 失败: %v, 重试", err)
		time.Sleep(time.Second)
	}
	defer func() {
		srv.Process.Kill()
		srv.Wait()
	}()
	defer videoConn.Close()

	var audioConn net.Conn
	if wantAudio {
		c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", *localPort), 3*time.Second)
		if err != nil {
			log.Printf("连接 audio socket 失败: %v", err)
			return
		}
		audioConn = c
		defer audioConn.Close()
	}

	controlConn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", *localPort), 3*time.Second)
	if err != nil {
		log.Printf("连接 control socket 失败: %v", err)
		return
	}
	defer controlConn.Close()
	log.Printf("scrcpy-server 就绪 (scid=%s), 开始转发 (audio=%v)", scid, wantAudio)

	fw := NewFrameWriter(viewerConn)
	done := make(chan string, 4)

	go func() { done <- pumpMedia(fw, ChVideo, videoConn, 12) }()
	if audioConn != nil {
		// 音频失败(如手机A < Android 11, server 发 4 字节 0 后关流)不拆会话
		go func() { log.Printf("音频通路: %s", pumpMedia(fw, ChAudio, audioConn, 4)) }()
	} else {
		fw.WriteFrame(ChAudio, make([]byte, 4)) // 主动告知观看端: 音频禁用
	}
	go func() { // 上行: 观看端帧 → 控制消息注入 / 音频开关
		for {
			ch, payload, err := ReadFrame(viewerConn)
			if err != nil {
				done <- fmt.Sprintf("观看端断开 (%v)", err)
				return
			}
			switch ch {
			case ChControl:
				if _, err := controlConn.Write(payload); err != nil {
					done <- fmt.Sprintf("控制注入中断 (%v)", err)
					return
				}
			case ChAudioToggle:
				// 观看端静音时干脆别传, 省的是公网中继的流量
				if len(payload) == 1 {
					on := payload[0] == 1
					audioMuted.Store(!on)
					log.Printf("[音频] 观看端%s声音转发", map[bool]string{true: "开启", false: "关闭"}[on])
				}
			}
		}
	}()
	go func() { // control socket 的设备消息(剪贴板等): 读掉丢弃
		io.Copy(io.Discard, controlConn)
		done <- "控制下行关闭"
	}()

	log.Print(<-done) // 任一关键通路结束即拆会话, defer 链完成清理
}

// 观看端是否临时关掉了声音(不拆流, 只是不转发, 这样随时能开回来)
var audioMuted atomic.Bool

// pumpMedia: 读媒体 socket 并转成帧下发。先透传 metaLen 字节的 codec meta,
// 之后循环解析 scrcpy 的 [8B pts+flags][4B size][payload] 包。
//
// 音频通路上还做两件省流量的事: 观看端静音时直接丢弃, 以及静音抑制(见 silence.go)。
// 注意即便不转发也必须把 socket 读干净, 否则 scrcpy-server 会被背压卡住。
func pumpMedia(fw *FrameWriter, ch byte, src net.Conn, metaLen int) string {
	name := map[byte]string{ChVideo: "视频", ChAudio: "音频"}[ch]
	meta := make([]byte, metaLen)
	if _, err := io.ReadFull(src, meta); err != nil {
		return fmt.Sprintf("%s meta 读取失败 (%v)", name, err)
	}
	if err := fw.WriteFrame(ch, meta); err != nil {
		return fmt.Sprintf("%s 下发中断 (%v)", name, err)
	}
	if ch == ChAudio && binary.BigEndian.Uint32(meta) == 0 {
		return "手机A 端音频不可用 (需 Android 11+)"
	}

	var quiet *silenceDetector
	if ch == ChAudio {
		quiet = &silenceDetector{}
		defer func() {
			s, f := quiet.Suppressed.Load(), quiet.Forwarded.Load()
			if s+f > 0 {
				log.Printf("[音频] 静音抑制 %d 帧 / 共 %d 帧 (省了 %.0f%%)",
					s, s+f, 100*float64(s)/float64(s+f))
			}
		}()
	}

	hdr := make([]byte, 12)
	var total int64
	for {
		if _, err := io.ReadFull(src, hdr); err != nil {
			return fmt.Sprintf("%s流中断 (已转发 %s, %v)", name, humanBytes(total), err)
		}
		size := binary.BigEndian.Uint32(hdr[8:12])
		if size == 0 || size > MaxFrameLen {
			return fmt.Sprintf("%s流异常包长 %d", name, size)
		}
		payload := make([]byte, size)
		if _, err := io.ReadFull(src, payload); err != nil {
			return fmt.Sprintf("%s流中断 (%v)", name, err)
		}

		if quiet != nil {
			isConfig := binary.BigEndian.Uint64(hdr[:8])&(1<<63) != 0
			// config 帧(AudioSpecificConfig)必须放行, 否则观看端建不起解码器
			if !isConfig && (audioMuted.Load() || !quiet.Feed(payload)) {
				continue
			}
		}

		total += int64(size)
		if err := fw.WriteFrame(ch, hdr[:8], payload); err != nil {
			return fmt.Sprintf("%s 下发中断 (已转发 %s, %v)", name, humanBytes(total), err)
		}
	}
}

// dialWithDummy 反复拨号直到读到 server 就绪的 dummy 字节。
// (adb forward 在 server 尚未监听时也会 accept 然后立刻断开, 所以必须以读到字节为准。)
func dialWithDummy(port int, timeout time.Duration) (net.Conn, error) {
	deadline := time.Now().Add(timeout)
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", addr, time.Second)
		if err == nil {
			conn.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
			buf := make([]byte, 1)
			if _, err := io.ReadFull(conn, buf); err == nil {
				conn.SetReadDeadline(time.Time{})
				return conn, nil
			}
			conn.Close()
		}
		time.Sleep(200 * time.Millisecond)
	}
	return nil, fmt.Errorf("等待 scrcpy-server 就绪超时 (%v)", timeout)
}

// ---- 工具函数 ----

func resolveAdb() string {
	if *adbPath != "" {
		return *adbPath
	}
	if p, err := exec.LookPath("adb"); err == nil {
		return p
	}
	fallback := `D:\Develop\Android\platform-tools\adb.exe`
	if _, err := os.Stat(fallback); err == nil {
		return fallback
	}
	die("找不到 adb: 请安装 Android platform-tools, 并在 phonecast.json 的 adb 字段填其路径")
	return ""
}

func resolveJar() string {
	if *serverJar != "" {
		return *serverJar
	}
	exe, _ := os.Executable()
	for _, dir := range []string{filepath.Dir(exe), "."} {
		p := filepath.Join(dir, "scrcpy-server-v"+serverVersion)
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	die("找不到 scrcpy-server-v%s, 该文件应与 exe 放在同一目录", serverVersion)
	return ""
}

func adbBaseArgs() []string {
	if *serial != "" {
		return []string{"-s", *serial}
	}
	return nil
}

func adbRun(adb string, args ...string) (string, error) {
	cmd := exec.Command(adb, append(adbBaseArgs(), args...)...)
	hideWindow(cmd)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

// adbRunNoSerial: 不带 -s 的 adb 调用 (枚举设备时用)。
func adbRunNoSerial(adb string, args ...string) (string, error) {
	cmd := exec.Command(adb, args...)
	hideWindow(cmd)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

// randomToken 返回 n 字节随机数的 hex (2n 字符), 首 bit 清零以兼容 scid 的 31 位要求。
func randomToken(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	b[0] &= 0x7f
	return fmt.Sprintf("%x", b)
}

func firstLanIP() string {
	if ips := lanIPs(); len(ips) > 0 {
		return ips[0]
	}
	return "<本机IP>"
}

// 虚拟网卡关键字: Docker/WSL/Hyper-V/VMware/VirtualBox 等的地址手机根本连不上,
// 之前"提示的 IP 不对"就是被这些网卡顶掉了真实无线网卡。
var virtualNICKeywords = []string{
	"docker", "vethernet", "hyper-v", "vmware", "virtualbox", "vmnet",
	"wsl", "loopback", "tap-", "tailscale", "zerotier", "bluetooth",
	// 代理/VPN 的 TUN 网卡会抢占默认路由, 手机连它必然失败
	"tun", "singbox", "sing-box", "clash", "v2ray", "wireguard", "openvpn", "utun", "ppp",
}

// lanIPs 返回手机可能连得上的本机 IPv4, 按可信度排序:
// 系统默认出口网卡的地址排第一, 其余物理网卡在后; 虚拟网卡与链路本地(169.254)剔除。
// 全部写进二维码, 由手机逐个尝试, 免得我们在这边猜错。
func lanIPs() []string {
	var ips []string
	seen := map[string]bool{}
	add := func(ip string) {
		if ip != "" && !seen[ip] {
			seen[ip] = true
			ips = append(ips, ip)
		}
	}

	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		name := strings.ToLower(ifc.Name)
		if slices.ContainsFunc(virtualNICKeywords, func(k string) bool {
			return strings.Contains(name, k)
		}) {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			ipn, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip4 := ipn.IP.To4()
			if ip4 == nil || ip4.IsLoopback() || ip4.IsLinkLocalUnicast() {
				continue // 169.254.x.x 是没拿到 DHCP 的网卡, 连不上
			}
			add(ip4.String())
		}
	}

	// 系统默认出口地址若确实属于物理网卡, 提到最前; 若它来自代理 TUN 就忽略。
	if p := primaryOutboundIP(); seen[p] {
		ips = append([]string{p}, slices.DeleteFunc(ips, func(s string) bool { return s == p })...)
	}
	return ips
}

// primaryOutboundIP: 系统访问外网时会用哪个本地地址。
// UDP 的 Dial 不发包, 只是让内核按路由表选好源地址 —— 这是判断"主网卡"最可靠的办法。
func primaryOutboundIP() string {
	conn, err := net.Dial("udp4", "223.5.5.5:80")
	if err != nil {
		return ""
	}
	defer conn.Close()
	if ua, ok := conn.LocalAddr().(*net.UDPAddr); ok && ua.IP.To4() != nil {
		return ua.IP.String()
	}
	return ""
}

func humanBytes(n int64) string {
	switch {
	case n > 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	case n > 1<<10:
		return fmt.Sprintf("%.1f KB", float64(n)/(1<<10))
	}
	return fmt.Sprintf("%d B", n)
}

type prefixWriter string

func (p prefixWriter) Write(b []byte) (int, error) {
	logSink.Write(append([]byte(p), b...))
	return len(b), nil
}
