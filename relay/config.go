// 配置文件与首次运行向导: 让 agent 双击即用, 不必记命令行参数。
// 优先级: 显式命令行参数 > phonecast.json > 内置默认值。
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// 不内置任何默认 hub 地址 (服务器信息不进代码库); 向导里留空即仅局域网直连。
const defaultHub = ""

type config struct {
	Key     string `json:"key"`
	Room    string `json:"room"`
	Hub     string `json:"hub"`
	Listen  string `json:"listen"`
	Serial  string `json:"serial,omitempty"`
	Adb     string `json:"adb,omitempty"`
	MaxSize int    `json:"max_size"`
	BitRate int    `json:"bit_rate"`
	MaxFps  int    `json:"max_fps"`
	Audio   *bool  `json:"audio"`
}

func configPath() string {
	exe, err := os.Executable()
	if err != nil {
		return "phonecast.json"
	}
	return filepath.Join(filepath.Dir(exe), "phonecast.json")
}

// loadConfig 读取配置; 文件不存在时走首次向导生成。
func loadConfig() *config {
	path := configPath()
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return firstRunWizard(path)
	}
	if err != nil {
		die("读取配置 %s 失败: %v", path, err)
	}
	cfg := &config{}
	if err := json.Unmarshal(data, cfg); err != nil {
		die("配置 %s 不是合法 JSON: %v\n(删掉该文件重新运行可重新生成)", path, err)
	}
	if cfg.Room == "" { // 配对码固定下来, 手机B 不用每次重填
		cfg.Room = randomToken(3)
		saveConfig(path, cfg)
	}
	return cfg
}

func firstRunWizard(path string) *config {
	if !hasConsole {
		return guiWizard(path)
	}
	fmt.Println("=== PhoneCast 首次配置 (之后双击即可运行) ===")
	in := bufio.NewReader(os.Stdin)

	var key string
	for key == "" {
		fmt.Print("接入密钥 (hub 部署时生成的那串): ")
		line, _ := in.ReadString('\n')
		key = strings.TrimSpace(line)
	}
	fmt.Print("hub 中继地址 host:port (留空=仅局域网直连): ")
	line, _ := in.ReadString('\n')
	hub := strings.TrimSpace(line)

	on := true
	cfg := &config{
		Key: key, Room: randomToken(3), Hub: hub, Listen: ":27184",
		MaxSize: 1440, BitRate: 8_000_000, MaxFps: 60, Audio: &on,
	}
	if hub != "" {
		cfg.BitRate = 4_000_000 // 走公网默认降码率
	}
	saveConfig(path, cfg)
	fmt.Printf("已保存到 %s (画质等参数可编辑该文件调整)\n\n", path)
	return cfg
}

// guiWizard: 双击启动(无控制台)的首次配置 —— 生成模板, 弹窗引导用记事本填密钥。
func guiWizard(path string) *config {
	on := true
	cfg := &config{
		Key: "", Room: randomToken(3), Hub: defaultHub, Listen: ":27184",
		MaxSize: 1440, BitRate: 4_000_000, MaxFps: 60, Audio: &on,
	}
	saveConfig(path, cfg)
	for {
		if messageBox("PhoneCast 首次配置",
			"已生成配置文件:\n"+path+
				"\n\n点「确定」用记事本打开, 填两项后保存关闭:\n"+
				"  key = 接入密钥\n  hub = 中继服务器 IP:27190 (仅局域网直连可留空)",
			mbOKCancel|mbIconInfo) != idOK {
			os.Exit(0)
		}
		openEditorWait(path)
		if data, err := os.ReadFile(path); err == nil {
			fresh := &config{}
			if json.Unmarshal(data, fresh) == nil && fresh.Key != "" {
				if fresh.Room == "" {
					fresh.Room = cfg.Room
					saveConfig(path, fresh)
				}
				return fresh
			}
		}
		if messageBox("PhoneCast", "key 还没填 (或 JSON 格式坏了), 再试一次?", mbOKCancel|mbIconWarning) != idOK {
			os.Exit(0)
		}
	}
}

func saveConfig(path string, cfg *config) {
	data, _ := json.MarshalIndent(cfg, "", "  ")
	if err := os.WriteFile(path, append(data, '\n'), 0600); err != nil {
		die("写入配置 %s 失败: %v", path, err)
	}
}

// applyConfig: 未在命令行显式指定的参数, 用配置文件的值覆盖内置默认。
func applyConfig(cfg *config) {
	set := map[string]bool{}
	flag.Visit(func(f *flag.Flag) { set[f.Name] = true })

	setStr := func(name string, dst *string, v string) {
		if !set[name] && v != "" {
			*dst = v
		}
	}
	setInt := func(name string, dst *int, v int) {
		if !set[name] && v != 0 {
			*dst = v
		}
	}
	setStr("key", key, cfg.Key)
	setStr("room", room, cfg.Room)
	setStr("hub", hubAddr, cfg.Hub)
	setStr("s", serial, cfg.Serial)
	setStr("adb", adbPath, cfg.Adb)
	setInt("max-size", maxSize, cfg.MaxSize)
	setInt("bit-rate", bitRate, cfg.BitRate)
	setInt("max-fps", maxFps, cfg.MaxFps)
	if !set["listen"] {
		*listen = cfg.Listen // 允许配置为空串=关闭直连
	}
	if !set["audio"] && cfg.Audio != nil {
		*audio = *cfg.Audio
	}
}

