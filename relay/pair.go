// 配对页: 生成含 phonecast:// 深链的二维码 HTML, 用浏览器打开。
// 手机B 用系统相机/任意扫码 App 扫码 → 唤起 PhoneCast 自动填入并连接, 无需手抄密钥。
package main

import (
	"encoding/base64"
	"fmt"
	"html"
	"net/url"
	"os"
	"path/filepath"

	qrcode "github.com/skip2/go-qrcode"
)

// pairURI: 二维码内容。带的是短期配对码而非长期密钥 —— 即使二维码被拍到,
// 也只在有效期内、且配对码尚未被用错 5 次时可用, 且可在托盘一键作废。
func pairURI(addr string) string {
	return "phonecast://c?a=" + url.QueryEscape(addr) +
		"&r=" + url.QueryEscape(*room) +
		"&c=" + url.QueryEscape(pairCode())
}

// showPairPage 生成并打开配对页, 返回错误供托盘提示。
func showPairPage() error {
	type entry struct{ title, addr string }
	var entries []entry
	if *hubAddr != "" {
		entries = append(entries, entry{"公网中继 (手机B 在任意网络)", *hubAddr})
	}
	if *listen != "" {
		entries = append(entries, entry{"局域网直连 (手机B 与电脑同 WiFi)", firstLanIP() + *listen})
	}
	if len(entries) == 0 {
		return fmt.Errorf("直连与中继都未启用")
	}

	page := `<!doctype html><meta charset=utf-8><title>PhoneCast 配对</title>
<style>body{font-family:system-ui;background:#10131a;color:#e6e9f0;display:flex;flex-wrap:wrap;gap:2em;justify-content:center;padding:2em}
.card{background:#1a1e27;border-radius:16px;padding:1.5em;text-align:center;max-width:320px}
img{width:260px;height:260px;border-radius:12px}h2{font-size:1em;color:#2d7dff}
p{font-size:.85em;color:#8a93a6;word-break:break-all;line-height:1.7}b{color:#e6e9f0;font-size:1.05em}</style>`
	for _, en := range entries {
		png, err := qrcode.Encode(pairURI(en.addr), qrcode.Medium, 520)
		if err != nil {
			return err
		}
		page += fmt.Sprintf(`<div class=card><h2>%s</h2><img src="data:image/png;base64,%s">
<p>手机B 用相机或扫码 App 扫码, 自动打开 PhoneCast 连接。<br>
手动填写: 地址 <b>%s</b><br>设备名 <b>%s</b> · 配对码 <b>%s</b></p></div>`,
			html.EscapeString(en.title), base64.StdEncoding.EncodeToString(png),
			html.EscapeString(en.addr), html.EscapeString(*room), html.EscapeString(pairCode()))
	}

	path := filepath.Join(os.TempDir(), "phonecast-pair.html")
	if err := os.WriteFile(path, []byte(page), 0o600); err != nil {
		return err
	}
	openBrowser(path)
	return nil
}
