// 配对页: 生成含 phonecast:// 深链的二维码 HTML, 用浏览器打开。
// 一个二维码同时带上局域网地址与中继地址, 手机端自己按顺序试(局域网优先)。
package main

import (
	"encoding/base64"
	"fmt"
	"html"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	qrcode "github.com/skip2/go-qrcode"
)

// pairURI: 二维码内容。带的是短期配对码而非长期密钥 —— 即使二维码被拍到,
// 也只在有效期内、且配对码尚未被用错 5 次时可用, 且可在托盘一键作废。
//
//	l = 局域网直连地址(可选), a = 中继地址(可选), r = 设备名, c = 配对码
func pairURI() string {
	q := "phonecast://c?r=" + url.QueryEscape(*room) + "&c=" + url.QueryEscape(pairCode())
	if *listen != "" {
		// 本机可能有多张网卡, 与其猜哪个对, 不如全带上让手机逐个试(每个失败只花 1.5 秒)
		for _, ip := range lanIPs() {
			q += "&l=" + url.QueryEscape(ip+*listen)
		}
	}
	if *hubAddr != "" {
		q += "&a=" + url.QueryEscape(*hubAddr)
	}
	return q
}

// showPairPage 生成并打开配对页, 返回错误供托盘提示。
func showPairPage() error {
	if *listen == "" && *hubAddr == "" {
		return fmt.Errorf("直连与中继都未启用")
	}
	png, err := qrcode.Encode(pairURI(), qrcode.Medium, 560)
	if err != nil {
		return err
	}

	var routes strings.Builder
	if *listen != "" {
		ips := lanIPs()
		note := "同一 WiFi 时自动优先走这条"
		if len(ips) > 1 {
			note = fmt.Sprintf("共 %d 个候选地址, 手机会自动挑通的那个", len(ips))
		}
		shown := "<未检测到网卡>"
		if len(ips) > 0 {
			shown = ips[0] + *listen
		}
		fmt.Fprintf(&routes, `<div class=row><span class=tag>局域网</span><b>%s</b><span class=note>%s</span></div>`,
			html.EscapeString(shown), html.EscapeString(note))
	}
	if *hubAddr != "" {
		fmt.Fprintf(&routes, `<div class=row><span class="tag alt">中继</span><b>%s</b><span class=note>不在同一网络时自动回落</span></div>`,
			html.EscapeString(*hubAddr))
	}

	page := fmt.Sprintf(`<!doctype html><meta charset=utf-8><title>PhoneCast 配对</title>
<style>
body{font-family:system-ui;background:#0e1117;color:#e8ebf2;display:flex;justify-content:center;padding:2.5em 1em}
.card{background:#161a22;border:1px solid #29303d;border-radius:20px;padding:2em;text-align:center;max-width:420px}
h1{font-size:1.15em;margin:0 0 .3em}
.sub{color:#8b94a7;font-size:.85em;margin-bottom:1.4em}
img{width:300px;height:300px;border-radius:14px;background:#fff;padding:10px}
.row{display:flex;align-items:center;gap:.6em;justify-content:flex-start;margin-top:.7em;font-size:.85em;flex-wrap:wrap}
.tag{background:#2d7dff;color:#fff;border-radius:6px;padding:.15em .5em;font-size:.8em}
.tag.alt{background:#39414f}
.note{color:#5a6376;font-size:.9em}
b{color:#e8ebf2;font-weight:500}
.manual{margin-top:1.6em;padding-top:1.2em;border-top:1px solid #29303d;color:#8b94a7;font-size:.85em;line-height:1.9}
.code{color:#2d7dff;font-size:1.5em;letter-spacing:.12em;font-weight:600}
</style>
<div class=card>
  <h1>用 PhoneCast 扫这个码</h1>
  <div class=sub>打开 App → 右下角「+」→ 扫码添加</div>
  <img src="data:image/png;base64,%s">
  %s
  <div class=manual>手动填写时:设备名 <b>%s</b><br>配对码 <span class=code>%s</span></div>
</div>`,
		base64.StdEncoding.EncodeToString(png), routes.String(),
		html.EscapeString(*room), html.EscapeString(pairCode()))

	path := filepath.Join(os.TempDir(), "phonecast-pair.html")
	if err := os.WriteFile(path, []byte(page), 0o600); err != nil {
		return err
	}
	openBrowser(path)
	return nil
}
