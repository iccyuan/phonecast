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
	"strings"
	"sync"
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
	maxSize   = flag.Int("max-size", 1440, "视频长边最大像素 (0=原始分辨率)")
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
	primary := *hubAddr
	if primary == "" {
		primary = firstLanIP() + *listen
	}
	fmt.Fprintf(&b, "PhoneCast 连接链接(手机上点开即连):\r\n%s\r\n\r\n", pairURI(primary))
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
func runSession(adb string, viewerConn net.Conn) {
	scid := randomToken(4)
	forwardSpec := fmt.Sprintf("tcp:%d", *localPort)
	if out, err := adbRun(adb, "forward", forwardSpec, "localabstract:scrcpy_"+scid); err != nil {
		log.Printf("adb forward 失败: %v\n%s", err, out)
		return
	}
	defer adbRun(adb, "forward", "--remove", forwardSpec)

	args := append(adbBaseArgs(), "shell",
		"CLASSPATH="+remoteJarPath, "app_process", "/", "com.genymobile.scrcpy.Server", serverVersion,
		"scid="+scid, "log_level=info",
		"video=true", "video_codec=h264",
		fmt.Sprintf("audio=%v", *audio), "audio_codec=aac",
		fmt.Sprintf("max_size=%d", *maxSize),
		fmt.Sprintf("video_bit_rate=%d", *bitRate),
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
	if *audio {
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
	log.Printf("scrcpy-server 就绪 (scid=%s), 开始转发 (audio=%v)", scid, *audio)

	fw := NewFrameWriter(viewerConn)
	done := make(chan string, 4)

	go func() { done <- pumpMedia(fw, ChVideo, videoConn, 12) }()
	if audioConn != nil {
		// 音频失败(如手机A < Android 11, server 发 4 字节 0 后关流)不拆会话
		go func() { log.Printf("音频通路: %s", pumpMedia(fw, ChAudio, audioConn, 4)) }()
	} else {
		fw.WriteFrame(ChAudio, make([]byte, 4)) // 主动告知观看端: 音频禁用
	}
	go func() { // 上行: 观看端帧 → 控制消息注入
		for {
			ch, payload, err := ReadFrame(viewerConn)
			if err != nil {
				done <- fmt.Sprintf("观看端断开 (%v)", err)
				return
			}
			if ch == ChControl {
				if _, err := controlConn.Write(payload); err != nil {
					done <- fmt.Sprintf("控制注入中断 (%v)", err)
					return
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

// pumpMedia: 读媒体 socket 并转成帧下发。先透传 metaLen 字节的 codec meta,
// 之后循环解析 scrcpy 的 [8B pts+flags][4B size][payload] 包。
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
		return ips[len(ips)-1] // 通常最后一个是物理网卡地址 (虚拟网卡排前面)
	}
	return "<本机IP>"
}

func lanIPs() []string {
	var ips []string
	addrs, _ := net.InterfaceAddrs()
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil && !ipn.IP.IsLoopback() {
			ips = append(ips, ipn.IP.String())
		}
	}
	return ips
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
