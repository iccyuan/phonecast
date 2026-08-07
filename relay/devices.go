// 被投屏手机的枚举与选择: 一台电脑可能同时插了多台手机。
package main

import (
	"strings"
	"sync"
)

type adbDevice struct {
	Serial string
	Model  string // 机型, 如 "Pixel 8"; 取不到时回落到序列号
	State  string // device / unauthorized / offline
}

func (d adbDevice) Label() string {
	if d.Model == "" {
		return d.Serial
	}
	return d.Model
}

var currentDevice struct {
	sync.Mutex
	label string
}

func setCurrentDeviceLabel(s string) {
	currentDevice.Lock()
	currentDevice.label = s
	currentDevice.Unlock()
}

// deviceLabel: 当前被投屏手机的显示名, 手机端列表用它。
func deviceLabel() string {
	currentDevice.Lock()
	defer currentDevice.Unlock()
	if currentDevice.label == "" {
		return *room
	}
	return currentDevice.label
}

// listAdbDevices 解析 `adb devices -l`:
//
//	3A181FDJH000J8   device usb:1-2 product:shiba model:Pixel_8 device:shiba transport_id:1
func listAdbDevices(adb string) []adbDevice {
	out, err := adbRunNoSerial(adb, "devices", "-l")
	if err != nil {
		return nil
	}
	var list []adbDevice
	for _, line := range strings.Split(out, "\n")[1:] {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		d := adbDevice{Serial: fields[0], State: fields[1]}
		for _, f := range fields[2:] {
			if model, ok := strings.CutPrefix(f, "model:"); ok {
				d.Model = strings.ReplaceAll(model, "_", " ")
			}
		}
		list = append(list, d)
	}
	return list
}

func readyDevices(adb string) []adbDevice {
	var ready []adbDevice
	for _, d := range listAdbDevices(adb) {
		if d.State == "device" {
			ready = append(ready, d)
		}
	}
	return ready
}
