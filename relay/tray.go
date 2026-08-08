// 托盘 UI: 双击启动后常驻任务栏, 右键菜单控制 engine 启停。
package main

import (
	_ "embed"
	"fmt"
	"net"
	"strings"
	"time"

	"fyne.io/systray"
)

// 托盘子菜单里最多列出的手机数 (systray 菜单项不能动态增删, 预留固定槽位)
const maxDeviceSlots = 6

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
	mCode := systray.AddMenuItem("配对码: ------", "手机首次连接时输入")
	mCode.Disable()
	mPair := systray.AddMenuItem("显示配对二维码", "手机B 扫码即连, 无需手输")
	mNewCode := systray.AddMenuItem("重新生成配对码", "作废旧码")
	mForget := systray.AddMenuItem("撤销已配对手机", "让所有手机重新配对")
	mFirewall := systray.AddMenuItem("允许局域网访问", "添加 Windows 防火墙入站规则(需管理员确认)")
	mCopy := systray.AddMenuItem("复制手机端连接信息", "地址/设备名/配对码")
	mConfig := systray.AddMenuItem("打开配置文件", "保存后点「重新运行」生效")
	mLog := systray.AddMenuItem("查看日志", "")
	var mHub *systray.MenuItem
	if url := hubStatusURL(); url != "" {
		mHub = systray.AddMenuItem("打开 hub 状态页", url)
	}
	systray.AddSeparator()
	mDevices := systray.AddMenuItem("选择被投屏手机", "一台电脑插多台手机时在这里切换")
	deviceSlots := make([]*systray.MenuItem, maxDeviceSlots)
	for i := range deviceSlots {
		deviceSlots[i] = mDevices.AddSubMenuItem("", "")
		deviceSlots[i].Hide()
	}
	systray.AddSeparator()
	mUpdate := systray.AddMenuItem("检查更新", "当前 v"+appVersion)
	mQuit := systray.AddMenuItem("退出", "")

	e.onState = func() {
		running, s := e.State()
		mState.SetTitle("状态: " + s)
		systray.SetTooltip("PhoneCast · " + s)
		mCode.SetTitle(fmt.Sprintf("配对码: %s   (已配对 %d 台)", pairCode(), deviceCount()))
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

	// 设备列表随插拔变化, 定期刷新子菜单
	serials := make([]string, maxDeviceSlots)
	refreshDevices := func() {
		list := readyDevices(e.adb)
		for i, slot := range deviceSlots {
			if i < len(list) {
				d := list[i]
				serials[i] = d.Serial
				mark := "   "
				if d.Serial == *serial {
					mark = "✓ "
				}
				slot.SetTitle(mark + d.Label() + "  (" + d.Serial + ")")
				slot.Show()
			} else {
				serials[i] = ""
				slot.Hide()
			}
		}
	}
	refreshDevices()
	go func() {
		t := time.NewTicker(20 * time.Second)
		defer t.Stop()
		for range t.C {
			refreshDevices()
		}
	}()
	go checkUpdate(false) // 启动时静默检查, 有新版才打扰

	for i, slot := range deviceSlots {
		go func(i int, slot *systray.MenuItem) {
			for range slot.ClickedCh {
				if s := serials[i]; s != "" && s != *serial {
					e.SwitchDevice(s)
					refreshDevices()
				}
			}
		}(i, slot)
	}

	go func() {
		for {
			select {
			case <-mUpdate.ClickedCh:
				go checkUpdate(true)
			case <-mStart.ClickedCh:
				e.Start()
			case <-mStop.ClickedCh:
				go e.Stop()
			case <-mRestart.ClickedCh:
				go e.Restart()
			case <-mPair.ClickedCh:
				if !pairCodeValid() {
					rotatePairCode() // 过期了先换新码再展示
				}
				if err := showPairPage(); err != nil {
					alertf("生成配对页失败: %v", err)
				}
			case <-mNewCode.ClickedCh:
				code := rotatePairCode()
				if !hasConsole {
					messageBox("PhoneCast", "新配对码: "+code+
						"\n\n旧配对码已作废。已配对过的手机不受影响。", mbOK|mbIconInfo)
				}
			case <-mForget.ClickedCh:
				if hasConsole || messageBox("PhoneCast",
					"撤销后所有手机都需要用新配对码重新配对, 继续?",
					mbOKCancel|mbIconWarning) == idOK {
					forgetDevices()
				}
			case <-mFirewall.ClickedCh:
				go func() {
					if firewallRuleExists() {
						messageBox("PhoneCast", "局域网直连已经放行, 无需重复添加。", mbOK|mbIconInfo)
						return
					}
					if err := addFirewallRule(); err != nil {
						alertf("添加防火墙规则失败: %v\n\n可手动执行(管理员命令行):\nnetsh advfirewall firewall add rule name=\"%s\" dir=in action=allow program=\"<本程序路径>\" enable=yes", err, firewallRuleName)
						return
					}
					if firewallRuleExists() {
						messageBox("PhoneCast", "已放行局域网直连,现在手机可以走局域网连接了。", mbOK|mbIconInfo)
					} else {
						alertf("规则似乎没添加成功, 请确认刚才的管理员提示是否被取消。")
					}
				}()
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
	messageBox("PhoneCast",
		"已复制到【电脑】剪贴板:\n\n"+strings.ReplaceAll(connInfoText(), "\r\n", "\n")+
			"\n注意: 电脑剪贴板不会同步到手机。\n"+
			"请把这段文字发到手机(微信/QQ 等), 在手机上点开链接即可直接连接。\n"+
			"更省事的做法: 用「显示配对二维码」直接扫码。",
		mbOK|mbIconInfo)
}
