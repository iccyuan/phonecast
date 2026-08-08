// 自适应码率。
//
// 约束: scrcpy-server 2.7 没有运行中调码率的接口(MediaCodec.setParameters 在它内部,
// 没暴露出来), 所以唯一的办法是用新参数重启编码会话。重启会闪一下, 因此策略必须"迟钝":
//   - 只在【持续】拥塞时降档, 单次抖动不动
//   - 两次调整之间有冷却, 免得反复横跳
//   - 回升比下降慢得多 (降档要快, 升档要稳)
//
// 判据来自观看端上报的真实体感, 而不是本机的发送量: 手机B 看到的 RTT 与积压丢弃,
// 才是"卡不卡"的定义。
package main

import (
	"log"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 码率档位(bps), 从高到低。降档就是往后走一格。
var bitrateLadder = []int{12_000_000, 8_000_000, 6_000_000, 4_000_000, 2_500_000, 1_500_000, 800_000}

const (
	adaptCooldown   = 20 * time.Second // 两次调整之间的最小间隔
	adaptUpAfter    = 60 * time.Second // 连续这么久没拥塞才尝试升一档
	rttCongestedMs  = 400              // P95 RTT 超过它算拥塞 (调成 1 可用于本地验证降档流程)
	dropsCongested  = 3                // 一个上报周期内的丢弃次数阈值
)

type netReport struct {
	RttP95  int
	Drops   int
	Pending int
}

// adaptor: 一个会话一个实例。
type adaptor struct {
	mu       sync.Mutex
	idx      int // 当前档位在 ladder 中的下标
	lastMove time.Time
	lastBad  time.Time
	// 请求重启编码会话; 由 runSession 注入
	restart func(newBitRate int)
}

func newAdaptor(startBitRate int, restart func(int)) *adaptor {
	idx := 0
	for i, b := range bitrateLadder {
		if b <= startBitRate {
			idx = i
			break
		}
		idx = i
	}
	return &adaptor{idx: idx, lastMove: time.Now(), lastBad: time.Now(), restart: restart}
}

func (a *adaptor) Current() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return bitrateLadder[a.idx]
}

// Report: 收到观看端的一次拥塞上报。
func (a *adaptor) Report(r netReport) {
	a.mu.Lock()
	defer a.mu.Unlock()

	congested := r.RttP95 >= rttCongestedMs || r.Drops >= dropsCongested
	now := time.Now()
	if congested {
		a.lastBad = now
	}
	if now.Sub(a.lastMove) < adaptCooldown {
		return
	}

	switch {
	case congested && a.idx < len(bitrateLadder)-1:
		a.idx++
		a.lastMove = now
		log.Printf("[自适应] 拥塞(RTT P95 %dms, 丢弃 %d) → 降到 %d kbps",
			r.RttP95, r.Drops, bitrateLadder[a.idx]/1000)
		go a.restart(bitrateLadder[a.idx])

	case !congested && a.idx > 0 && now.Sub(a.lastBad) >= adaptUpAfter:
		a.idx--
		a.lastMove = now
		a.lastBad = now // 升档后重新计时, 免得连着升
		log.Printf("[自适应] 链路稳定 → 升到 %d kbps", bitrateLadder[a.idx]/1000)
		go a.restart(bitrateLadder[a.idx])
	}
}

func parseNetReport(payload []byte) netReport {
	var r netReport
	for _, kv := range strings.Split(string(payload), ";") {
		k, v, ok := strings.Cut(kv, "=")
		if !ok {
			continue
		}
		n, _ := strconv.Atoi(v)
		switch k {
		case "rtt":
			r.RttP95 = n
		case "drops":
			r.Drops = n
		case "pending":
			r.Pending = n
		}
	}
	return r
}
