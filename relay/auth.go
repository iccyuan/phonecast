// 端到端认证: 手机B 只需输 6 位配对码, 之后靠设备令牌免输。
// 配对码与令牌都不上线(HMAC 挑战-应答), 中继服务器也无法冒充观看端。
package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"math/big"
	"net"
	"sync"
	"time"
)

const (
	maxCodeFails = 5               // 连续失败上限, 超过即作废配对码
	codeTTL      = 30 * time.Minute // 配对码有效期(仅对未配对设备)
)

var auth struct {
	mu        sync.Mutex
	code      string
	codeBorn  time.Time
	fails     int
	devices   []string // 已配对设备令牌 (hex), 持久化在 phonecast.json
	onChanged func()   // 托盘/横幅刷新
}

func initAuth(devices []string, onChanged func()) {
	auth.mu.Lock()
	defer auth.mu.Unlock()
	auth.devices = devices
	auth.onChanged = onChanged
	auth.code = newPairCode()
	auth.codeBorn = time.Now()
}

// newPairCode: 6 位数字, 加密随机。
func newPairCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "000000"
	}
	return fmt.Sprintf("%06d", n.Int64())
}

func pairCode() string {
	auth.mu.Lock()
	defer auth.mu.Unlock()
	return auth.code
}

func pairCodeValid() bool {
	auth.mu.Lock()
	defer auth.mu.Unlock()
	return time.Since(auth.codeBorn) < codeTTL
}

// rotatePairCode: 手动(托盘菜单)或失败超限时重新生成。
func rotatePairCode() string {
	auth.mu.Lock()
	auth.code = newPairCode()
	auth.codeBorn = time.Now()
	auth.fails = 0
	code, cb := auth.code, auth.onChanged
	auth.mu.Unlock()
	log.Printf("[认证] 新配对码: %s", code)
	if cb != nil {
		cb()
	}
	return code
}

func deviceCount() int {
	auth.mu.Lock()
	defer auth.mu.Unlock()
	return len(auth.devices)
}

// forgetDevices: 撤销全部已配对手机 (托盘菜单)。
func forgetDevices() {
	auth.mu.Lock()
	auth.devices = nil
	auth.mu.Unlock()
	persistDevices(nil)
	log.Print("[认证] 已撤销全部已配对设备")
	rotatePairCode()
}

// authenticate: 对已建立的观看端连接做挑战-应答。
// 成功且用的是配对码时, 下发一枚新设备令牌供下次免输。
func authenticate(conn net.Conn) bool {
	nonce := make([]byte, NonceLen)
	if _, err := rand.Read(nonce); err != nil {
		return false
	}
	fw := NewFrameWriter(conn)
	if fw.WriteFrame(ChAuthChallenge, nonce) != nil {
		return false
	}

	conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	ch, payload, err := ReadFrame(conn)
	conn.SetReadDeadline(time.Time{})
	if err != nil || ch != ChAuthResponse || len(payload) != 1+32 {
		return false
	}
	byToken := payload[0] == 1
	proof := payload[1:]

	auth.mu.Lock()
	code, born, devices := auth.code, auth.codeBorn, append([]string(nil), auth.devices...)
	auth.mu.Unlock()

	if byToken {
		for _, t := range devices {
			if AuthProofValid(t, nonce, proof) {
				fw.WriteFrame(ChAuthResult, []byte{StatusOK})
				log.Print("[认证] 已配对设备接入")
				return true
			}
		}
		// 令牌失效(如电脑端撤销过): 让手机回退到配对码
		fw.WriteFrame(ChAuthResult, []byte{StatusAuth})
		log.Print("[认证] 设备令牌无效, 需重新配对")
		return false
	}

	if time.Since(born) >= codeTTL {
		fw.WriteFrame(ChAuthResult, []byte{StatusAuth})
		log.Print("[认证] 配对码已过期, 请在托盘菜单重新生成")
		return false
	}
	if !AuthProofValid(code, nonce, proof) {
		auth.mu.Lock()
		auth.fails++
		n := auth.fails
		auth.mu.Unlock()
		log.Printf("[认证] 配对码错误 (%d/%d)", n, maxCodeFails)
		time.Sleep(time.Second) // 拖慢在线爆破
		fw.WriteFrame(ChAuthResult, []byte{StatusAuth})
		if n >= maxCodeFails {
			log.Print("[认证] 失败次数过多, 配对码已作废并重新生成")
			rotatePairCode()
		}
		return false
	}

	// 配对成功: 发一枚设备令牌, 手机存下后即可免输
	raw := make([]byte, TokenLen)
	rand.Read(raw)
	token := hex.EncodeToString(raw)
	auth.mu.Lock()
	auth.fails = 0
	auth.devices = append(auth.devices, token)
	all := append([]string(nil), auth.devices...)
	auth.mu.Unlock()
	persistDevices(all)

	fw.WriteFrame(ChAuthResult, append([]byte{StatusOK}, token...))
	log.Printf("[认证] 配对成功, 已授权新设备 (共 %d 台)", len(all))
	return true
}
