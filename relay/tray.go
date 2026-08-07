// 托盘 UI: 双击启动后常驻任务栏, 右键菜单控制 engine 启停。
package main

import (
	_ "embed"
	"fmt"
	"net"
	"strings"

	"fyne.io/systray"
)

//go:embed icon.ico
var iconData []byte

func runTray(e *engine) {
	systray.Run(func() { trayReady(e) }, nil)
}

func trayReady(e *engine) {
	systray.SetIcon(iconData)
	systray.SetTooltip("PhoneCast")

	mState := systray.AddMenuItem("状态: 启动中...", "")
	mState.Disable()
	systray.AddSeparator()
	mStart := systray.AddMenuItem("启动", "")
	mStop := systray.AddMenuItem("停止", "")
	mRestart := systray.AddMenuItem("重新运行", "改配置后用这个生效")
	systray.AddSeparator()
	mPair := systray.AddMenuItem("显示配对二维码", "手机B 扫码即连")
	mCopy := systray.AddMenuItem("复制手机端连接信息", "地址/密钥/配对码")
	mConfig := systray.AddMenuItem("打开配置文件", "保存后点「重新运行」生效")
	mLog := systray.AddMenuItem("查看日志", "")
	var mHub *systray.MenuItem
	if url := hubStatusURL(); url != "" {
		mHub = systray.AddMenuItem("打开 hub 状态页", url)
	}
	systray.AddSeparator()
	mQuit := systray.AddMenuItem("退出", "")

	e.onState = func() {
		running, s := e.State()
		mState.SetTitle("状态: " + s)
		systray.SetTooltip("PhoneCast · " + s)
		if running {
			mStart.Disable()
			mStop.Enable()
			mRestart.Enable()
		} else {
			mStart.Enable()
			mStop.Disable()
			mRestart.Enable() // 停止态下「重新运行」= 启动
		}
	}
	e.Start()

	go func() {
		for {
			select {
			case <-mStart.ClickedCh:
				e.Start()
			case <-mStop.ClickedCh:
				go e.Stop()
			case <-mRestart.ClickedCh:
				go e.Restart()
			case <-mPair.ClickedCh:
				if err := showPairPage(); err != nil {
					alertf("生成配对页失败: %v", err)
				}
			case <-mCopy.ClickedCh:
				if err := setClipboard(connInfoText()); err == nil {
					alertOnceCopied()
				} else {
					alertf("复制失败: %v", err)
				}
			case <-mConfig.ClickedCh:
				openEditor(configPath())
			case <-mLog.ClickedCh:
				openEditor(logPath())
			case <-clickedOrNil(mHub):
				openBrowser(hubStatusURL())
			case <-mQuit.ClickedCh:
				e.Stop()
				systray.Quit()
				return
			}
		}
	}()
}

// clickedOrNil: 菜单项可能不存在, nil channel 在 select 里永不触发。
func clickedOrNil(mi *systray.MenuItem) chan struct{} {
	if mi == nil {
		return nil
	}
	return mi.ClickedCh
}

// hubStatusURL: 按约定 hub 状态页端口 = 接入端口 + 1。
func hubStatusURL() string {
	if *hubAddr == "" {
		return ""
	}
	host, port, err := net.SplitHostPort(*hubAddr)
	if err != nil {
		return ""
	}
	var p int
	fmt.Sscanf(port, "%d", &p)
	return fmt.Sprintf("http://%s:%d/", host, p+1) // 状态页要登录, 不在 URL 里带密钥
}

var copyNoticeShown bool

// alertOnceCopied: 复制成功轻提示, 只弹一次以免烦人。
func alertOnceCopied() {
	if copyNoticeShown || hasConsole {
		return
	}
	copyNoticeShown = true
	messageBox("PhoneCast", "已复制到剪贴板:\n\n"+strings.ReplaceAll(connInfoText(), "\r\n", "\n"), mbOK|mbIconInfo)
}
