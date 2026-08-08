// 在线更新: 查 GitHub Releases, 下载新版 zip, 用一个自删的 bat 在本进程退出后替换 exe 并重启。
package main

import (
	"archive/zip"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	// 必须与发布 tag 一致: 更新检查是拿它跟 GitHub 上的 tag_name 比,
	// 落后于已发布版本会导致每次启动都误报"有新版本"。
	appVersion   = "0.7.2"
	releasesAPI  = "https://api.github.com/repos/iccyuan/phonecast/releases/latest"
	relayAssetZip = "phonecast-relay-windows.zip"
	relayExeName  = "phonecast-relay.exe"
)

type release struct {
	TagName string `json:"tag_name"`
	HTMLURL string `json:"html_url"`
	Body    string `json:"body"`
	Assets  []struct {
		Name string `json:"name"`
		URL  string `json:"browser_download_url"`
	} `json:"assets"`
}

// checkUpdate: manual=true 时无论结果都给反馈; 自动检查只在有新版本时打扰。
func checkUpdate(manual bool) {
	rel, err := latestRelease()
	if err != nil {
		log.Printf("[更新] 检查失败: %v", err)
		if manual {
			alertf("检查更新失败: %v\n\n(需要能访问 GitHub;如走代理请设置系统代理)", err)
		}
		return
	}
	latest := strings.TrimPrefix(rel.TagName, "v")
	if !newerThan(latest, appVersion) {
		log.Printf("[更新] 当前 v%s 已是最新", appVersion)
		if manual {
			messageBox("PhoneCast", fmt.Sprintf("当前版本 v%s 已是最新。", appVersion), mbOK|mbIconInfo)
		}
		return
	}

	var zipURL string
	for _, a := range rel.Assets {
		if a.Name == relayAssetZip {
			zipURL = a.URL
		}
	}
	log.Printf("[更新] 发现新版本 v%s (当前 v%s)", latest, appVersion)
	if zipURL == "" { // 没有对应产物, 引导去发布页
		if messageBox("PhoneCast 有新版本",
			fmt.Sprintf("发现新版本 v%s(当前 v%s)。\n\n点「确定」打开发布页手动下载。", latest, appVersion),
			mbOKCancel|mbIconInfo) == idOK {
			openBrowser(rel.HTMLURL)
		}
		return
	}

	notes := strings.TrimSpace(rel.Body)
	if len(notes) > 500 {
		notes = notes[:500] + "..."
	}
	if messageBox("PhoneCast 有新版本",
		fmt.Sprintf("发现新版本 v%s(当前 v%s)。\n\n%s\n\n点「确定」下载并自动更新,更新后会自动重启。",
			latest, appVersion, notes),
		mbOKCancel|mbIconInfo) != idOK {
		return
	}
	if err := downloadAndApply(zipURL); err != nil {
		log.Printf("[更新] 失败: %v", err)
		alertf("更新失败: %v", err)
	}
}

func latestRelease() (*release, error) {
	client := &http.Client{Timeout: 15 * time.Second}
	req, err := http.NewRequest("GET", releasesAPI, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "phonecast/"+appVersion)
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("GitHub 返回 %s", resp.Status)
	}
	var rel release
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&rel); err != nil {
		return nil, err
	}
	return &rel, nil
}

// newerThan: 比较 "0.5.0" 形式的版本号, 逐段数值比较。
func newerThan(a, b string) bool {
	as, bs := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(as) || i < len(bs); i++ {
		x, y := 0, 0
		if i < len(as) {
			x, _ = strconv.Atoi(strings.TrimSpace(as[i]))
		}
		if i < len(bs) {
			y, _ = strconv.Atoi(strings.TrimSpace(bs[i]))
		}
		if x != y {
			return x > y
		}
	}
	return false
}

// downloadAndApply: 下载 zip → 取出新 exe → 生成替换脚本 → 退出本进程由脚本接手。
func downloadAndApply(zipURL string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	tmpZip := filepath.Join(os.TempDir(), "phonecast-update.zip")

	client := &http.Client{Timeout: 5 * time.Minute}
	resp, err := client.Get(zipURL)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("下载返回 %s", resp.Status)
	}
	f, err := os.Create(tmpZip)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, io.LimitReader(resp.Body, 200<<20)); err != nil {
		f.Close()
		return err
	}
	f.Close()
	defer os.Remove(tmpZip)

	newExe := filepath.Join(os.TempDir(), "phonecast-relay.new.exe")
	if err := extractFile(tmpZip, relayExeName, newExe); err != nil {
		return err
	}

	// 替换脚本: 等本进程退出 → 覆盖 exe → 重新启动 → 自删
	bat := filepath.Join(os.TempDir(), "phonecast-update.bat")
	script := fmt.Sprintf(`@echo off
setlocal
set TARGET=%s
set SOURCE=%s
:waitloop
tasklist /fi "IMAGENAME eq %s" 2>nul | find /i "%s" >nul
if not errorlevel 1 (
  ping -n 2 127.0.0.1 >nul
  goto waitloop
)
move /y "%%SOURCE%%" "%%TARGET%%" >nul
start "" "%%TARGET%%"
del "%%~f0"
`, exe, newExe, relayExeName, relayExeName)
	if err := os.WriteFile(bat, []byte(script), 0o600); err != nil {
		return err
	}

	log.Print("[更新] 开始替换并重启...")
	cmd := exec.Command("cmd", "/c", bat)
	hideWindow(cmd)
	if err := cmd.Start(); err != nil {
		return err
	}
	os.Exit(0) // 让脚本接手; 托盘图标随进程退出消失, 新版本会自动拉起
	return nil
}

// extractFile: 从 zip 里取出指定名字的文件写到 dst。
func extractFile(zipPath, name, dst string) error {
	r, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer r.Close()
	for _, f := range r.File {
		if filepath.Base(f.Name) != name {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		defer rc.Close()
		out, err := os.Create(dst)
		if err != nil {
			return err
		}
		defer out.Close()
		_, err = io.Copy(out, io.LimitReader(rc, 200<<20))
		return err
	}
	return fmt.Errorf("更新包里没有 %s", name)
}
