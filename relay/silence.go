// 静音抑制: 画面静止时音频占了总流量的 7 成(实测 AAC 恒定 ~120 kbps),
// 因为 AAC 是恒定码率, 静音也照样编满。
//
// 关键观察(实测): 输入是数字静音时, 编码器输出的 AAC 帧会在极少数几种字节序列
// 之间循环 —— 20 秒 874 帧里只有 3 种不同的帧, 99.7% 与历史帧逐字节相同。
// 于是不必解码, 只要认出"又是那几帧"就能判定静音并停发, 一有新内容立刻恢复。
//
// 取舍: 判定需要连续若干帧重复, 所以静音开始后会多发很短一段(~0.5s);
// 而恢复是即时的 —— 第一帧不重复就立刻放行, 不会切掉声音的起音。
package main

import (
	"crypto/sha256"
	"sync/atomic"
)

const (
	silenceRing      = 6  // 记住最近几种不同的帧, 静音时它们会循环出现
	silenceEnterRuns = 24 // 连续多少帧都是"老面孔"才认定静音 (AAC 一帧约 21ms → 约 0.5s)
)

// silenceDetector 无状态依赖外部锁: 每个会话一个实例, 只在音频泵单线程里用。
type silenceDetector struct {
	recent [silenceRing][32]byte // 最近见过的帧指纹
	n      int
	runs   int
	silent bool

	// 统计, 供日志
	Suppressed atomic.Int64
	Forwarded  atomic.Int64
}

// Feed 返回该帧是否应当转发。
func (d *silenceDetector) Feed(es []byte) bool {
	sum := sha256.Sum256(es)
	var fp [32]byte
	copy(fp[:], sum[:])

	known := false
	for i := 0; i < d.n; i++ {
		if d.recent[i] == fp {
			known = true
			break
		}
	}

	if known {
		d.runs++
		if d.runs >= silenceEnterRuns {
			d.silent = true
		}
	} else {
		// 出现新内容: 立刻结束静音, 并把它记进指纹环
		d.runs = 0
		d.silent = false
		if d.n < silenceRing {
			d.recent[d.n] = fp
			d.n++
		} else {
			copy(d.recent[:], d.recent[1:])
			d.recent[silenceRing-1] = fp
		}
	}

	if d.silent {
		d.Suppressed.Add(1)
		return false
	}
	d.Forwarded.Add(1)
	return true
}

func (d *silenceDetector) Silent() bool { return d.silent }
