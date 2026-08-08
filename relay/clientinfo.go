// 观看端能力协商: 决定用哪种视频编码、编多大分辨率、要不要音频。
//
// 分辨率默认「自适应」—— 按观看端(手机B)的屏幕来编码。手机B 屏幕就那么大,
// 按被控手机的原生分辨率编码等于把码率浪费在看不见的像素上。
package main

import (
	"log"
	"strconv"
	"strings"
	"sync"
	"time"
)

// clientInfo: 观看端上报的能力与偏好。
type clientInfo struct {
	Width   int  // 观看端屏幕像素
	Height  int
	H265    bool // 观看端能否硬解 H.265
	Audio   bool // 观看端是否要音频
	Present bool // 是否收到过上报(老版本客户端不会发)
}

// readClientInfo: 认证通过后读一帧能力上报。老客户端不发, 等一小会儿就用默认值。
func readClientInfo(conn interface {
	SetReadDeadline(time.Time) error
}, readFrame func() (byte, []byte, error)) clientInfo {
	info := clientInfo{Audio: true}
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	defer conn.SetReadDeadline(time.Time{})

	ch, payload, err := readFrame()
	if err != nil || ch != ChClientInfo {
		log.Print("[协商] 观看端未上报能力, 使用默认参数")
		return info
	}
	for _, kv := range strings.Split(string(payload), ";") {
		k, v, ok := strings.Cut(kv, "=")
		if !ok {
			continue
		}
		n, _ := strconv.Atoi(v)
		switch k {
		case "w":
			info.Width = n
		case "h":
			info.Height = n
		case "h265":
			info.H265 = n == 1
		case "audio":
			info.Audio = n == 1
		}
	}
	info.Present = true
	log.Printf("[协商] 观看端: %dx%d, H.265=%v, 要音频=%v", info.Width, info.Height, info.H265, info.Audio)
	return info
}

// 设备端编码能力(启动时探测一次)
var encoderCaps struct {
	sync.Mutex
	probed bool
	h265   bool
}

// probeEncoders: 问 scrcpy-server 要一份编码器清单, 看被投屏手机能不能硬编 H.265。
// 只探一次, 结果缓存; 探测失败就当作不支持, 回落 H.264。
func probeEncoders(adb string) {
	encoderCaps.Lock()
	if encoderCaps.probed {
		encoderCaps.Unlock()
		return
	}
	encoderCaps.Unlock()

	out, err := adbRun(adb, "shell",
		"CLASSPATH="+remoteJarPath, "app_process", "/", "com.genymobile.scrcpy.Server",
		serverVersion, "list_encoders=true", "cleanup=false", "log_level=info")
	h265 := err == nil && strings.Contains(out, "--video-codec=h265")

	encoderCaps.Lock()
	encoderCaps.probed = true
	encoderCaps.h265 = h265
	encoderCaps.Unlock()
	log.Printf("[编码] 被投屏手机 H.265 硬编: %v", h265)
}

func deviceSupportsH265() bool {
	encoderCaps.Lock()
	defer encoderCaps.Unlock()
	return encoderCaps.h265
}

// pickCodec: 两端都支持才用 H.265, 否则 H.264。
// H.265 同画质省 25%~45% 码率, 但硬件支持面不如 H.264, 所以必须能回落。
func pickCodec(info clientInfo) string {
	if *videoCodec == "h264" {
		return "h264"
	}
	if deviceSupportsH265() && info.H265 {
		return "h265"
	}
	return "h264"
}

// pickMaxSize: 自适应模式按观看端屏幕长边编码; 原始模式不限制。
// 观看端没上报时退回配置里的固定值。
func pickMaxSize(info clientInfo) int {
	if *resolution == "original" {
		return 0
	}
	if info.Width > 0 && info.Height > 0 {
		long := info.Width
		if info.Height > long {
			long = info.Height
		}
		// 留一点余量避免因取整反而放大; 同时限制在合理区间
		return clampInt(long, 480, 2160)
	}
	return *maxSize
}

// pickBitRate: 码率跟着分辨率与传输路径走。
//
// 之前的写法只会"往下压", 结果 2160 长边配上 2.8 Mbps —— 分辨率高但糊, 两头不讨好。
// 现在按长边给出目标码率, 再按路径设上限: 局域网带宽充裕, 该给的画质就给;
// 走公网中继才需要克制(配置里的 bit_rate 就是中继上限)。
func pickBitRate(maxSizePx int, codec string, viaLan bool) int {
	target := 9_000_000
	switch {
	case maxSizePx <= 0: // 原始分辨率, 未知大小, 按高档给
		target = 10_000_000
	case maxSizePx <= 800:
		target = 2_000_000
	case maxSizePx <= 1280:
		target = 3_500_000
	case maxSizePx <= 1600:
		target = 5_000_000
	case maxSizePx <= 2000:
		target = 7_000_000
	}
	if codec == "h265" {
		target = target * 7 / 10 // 同画质下 H.265 大约省 30%
	}
	if viaLan {
		return minInt(target, 12_000_000)
	}
	return minInt(target, *bitRate)
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
