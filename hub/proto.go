// PhoneCast v2 线协议: 握手 + 统一帧格式。
// agent↔viewer(直连) / agent↔hub / viewer↔hub 全部使用同一套。
//
// 握手(连接方先发): magic(4B) + u8 keyLen + key + 载荷
//   "PCV2" viewer:  载荷 = u8 roomLen + room        → 响应 1 字节状态码
//   "PCA2" agent注册: 载荷 = u8 roomLen + room      → 响应 1 字节状态码
//   "PCS2" agent会话: 载荷 = 16B session id          → 无响应, 直接进入帧流
//
// 帧: [u8 channel][u32 大端 len][payload]
package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
)

const (
	// 帧通道
	ChVideo   = 0x00 // 首帧=12B codec meta, 之后 [8B ptsAndFlags][ES 数据]
	ChAudio   = 0x01 // 首帧=4B codec id(0=禁用), 之后 [8B ptsAndFlags][ES 数据]
	ChControl = 0x02 // viewer→agent, 每帧一条 scrcpy 控制消息
	ChStart   = 0x10 // hub→agent(注册连接), payload=16B session id
	ChPing    = 0x11 // 注册连接保活, 双向回显

	// 握手响应状态码
	StatusOK      = 0
	StatusBadKey  = 1
	StatusNoRoom  = 2 // 配对码对应的 agent 不在线
	StatusBusy    = 3 // 已有观看端
	StatusHubErr  = 4 // hub 内部错误(等 agent 会话连接超时等)

	MaxFrameLen = 8 << 20
)

var (
	MagicViewer  = [4]byte{'P', 'C', 'V', '2'}
	MagicAgent   = [4]byte{'P', 'C', 'A', '2'}
	MagicSession = [4]byte{'P', 'C', 'S', '2'}
)

// FrameWriter 串行化多 goroutine 的帧写入(video/audio 并发下行)。
type FrameWriter struct {
	mu sync.Mutex
	w  io.Writer
}

func NewFrameWriter(w io.Writer) *FrameWriter { return &FrameWriter{w: w} }

// WriteFrame 支持多段 payload, 拼一次系统调用发出。
func (fw *FrameWriter) WriteFrame(ch byte, parts ...[]byte) error {
	total := 0
	for _, p := range parts {
		total += len(p)
	}
	buf := make([]byte, 5, 5+total)
	buf[0] = ch
	binary.BigEndian.PutUint32(buf[1:5], uint32(total))
	for _, p := range parts {
		buf = append(buf, p...)
	}
	fw.mu.Lock()
	defer fw.mu.Unlock()
	_, err := fw.w.Write(buf)
	return err
}

func ReadFrame(r io.Reader) (byte, []byte, error) {
	var hdr [5]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return 0, nil, err
	}
	n := binary.BigEndian.Uint32(hdr[1:5])
	if n > MaxFrameLen {
		return 0, nil, fmt.Errorf("帧长 %d 超限, 流已错位", n)
	}
	payload := make([]byte, n)
	if _, err := io.ReadFull(r, payload); err != nil {
		return 0, nil, err
	}
	return hdr[0], payload, nil
}

// WriteHandshake: magic + key + tail(room 或 session id 等)。
func WriteHandshake(w io.Writer, magic [4]byte, key string, tail []byte) error {
	if len(key) > 255 {
		return errors.New("key 过长")
	}
	buf := make([]byte, 0, 5+len(key)+len(tail))
	buf = append(buf, magic[:]...)
	buf = append(buf, byte(len(key)))
	buf = append(buf, key...)
	buf = append(buf, tail...)
	_, err := w.Write(buf)
	return err
}

func WriteRoomHandshake(w io.Writer, magic [4]byte, key, room string) error {
	if len(room) > 255 {
		return errors.New("room 过长")
	}
	tail := append([]byte{byte(len(room))}, room...)
	return WriteHandshake(w, magic, key, tail)
}

func ReadLenPrefixed(r io.Reader) (string, error) {
	var l [1]byte
	if _, err := io.ReadFull(r, l[:]); err != nil {
		return "", err
	}
	b := make([]byte, l[0])
	if _, err := io.ReadFull(r, b); err != nil {
		return "", err
	}
	return string(b), nil
}

func ReadStatus(c net.Conn) (byte, error) {
	var b [1]byte
	if _, err := io.ReadFull(c, b[:]); err != nil {
		return 0, err
	}
	return b[0], nil
}
