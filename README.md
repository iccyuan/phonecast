# PhoneCast

把 **手机A**(ADB 连接电脑)的屏幕+声音投到 **手机B** 上,支持在手机B 上反向触摸操控手机A。手机B 可以直连电脑(局域网),也可以经云端 hub 中继(公网任意位置)。

```
                      直连 (局域网/adb reverse)
            ┌────────────────────────────────────────┐
            │                                        ▼
手机A ──adb──> 电脑 agent ──反向注册──> 云端 hub <──── 手机B (viewer App)
  ▲  scrcpy-server        (配对码撮合, 纯字节对拷, 流量统计)      │
  └────────────────── 触摸/按键控制消息 ◄───────────────────────┘
```

## 组成

| 目录 | 说明 |
|---|---|
| `relay/` | **agent**(电脑端, Go)。adb push 并启动官方 scrcpy-server v2.7(手机A 无需装 App),桥接 video/audio/control 三条 socket。直连监听 + hub 反向注册可同时开启 |
| `hub/` | **hub**(云端中继, Go)。agent/viewer 鉴权接入、按配对码撮合、纯字节对拷;HTTP 状态页(在线 agent、进行中会话、流量统计) |
| `viewer/` | 手机B 的 Android App(Kotlin,零第三方依赖,minSdk 24)。MediaCodec 硬解 H.264 → SurfaceView;AAC 音频解码 → AudioTrack;触摸/导航键按 scrcpy 控制协议回传 |

## 使用

### 电脑端 (agent)

**双击 `phonecast-relay.exe` 即可**。首次运行进入向导:粘贴一次密钥、回车确认 hub 地址,配置保存到 exe 旁的 `phonecast.json`,之后每次双击直接可用。启动后窗口显示手机B 需要填的 地址/密钥/配对码;没插手机会等待(提示开 USB 调试)而不是退出。

画质等参数编辑 `phonecast.json` 调整(`max_size`/`bit_rate`/`max_fps`/`audio`/`serial`,`hub` 留空=仅局域网直连,走公网建议码率 2-4 Mbps)。命令行参数仍然可用且优先于配置文件(`-key`/`-room`/`-hub`/`-listen`/`-s` 等,见 `-h`)。`scrcpy-server-v2.7` 放 exe 同目录。

托盘菜单:启动/停止/重新运行、配对码与二维码、**选择被投屏手机**(一台电脑插多台时切换)、检查更新、日志、hub 状态页。

### 手机B (viewer)

装 `phonecast-viewer.apk`。主页是**已配对手机列表**(显示真实机型),点条目直接连;右下角「+」手动添加(地址 / 设备名 / 6 位配对码)。最省事的是扫电脑托盘里的**配对二维码**,自动完成配对并连接。无 WiFi 也可 USB 直连电脑:`adb reverse tcp:27184 tcp:27184` 后地址填 `127.0.0.1:27184`。

两端都支持在线更新:电脑端托盘「检查更新」自动下载、替换并重启;App 在「+」菜单里「检查更新」,**应用内下载完直接拉起系统安装器**(首次需在系统里允许 PhoneCast 安装应用,会自动引导)。

## 签名

应用内更新要求新旧 APK **签名一致**,否则系统拒绝覆盖安装。因此 debug 与 release 都使用同一个正式 keystore:

- 本机:在 `viewer/local.properties` 配置(该文件已 gitignore)
  ```properties
  SIGNING_STORE_FILE=D:/keys/phonecast.jks
  SIGNING_STORE_PASSWORD=...
  SIGNING_KEY_ALIAS=phonecast
  SIGNING_KEY_PASSWORD=...
  ```
- CI:仓库 Secrets 配 `SIGNING_KEYSTORE_B64`(keystore 的 base64)、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。未配置时工作流会退化为 debug 签名并给出告警——那样的产物无法覆盖升级正式版。

新建 keystore:

```powershell
keytool -genkeypair -v -keystore phonecast.jks -alias phonecast `
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=PhoneCast, O=..., C=CN"
```

**keystore 必须备份**:丢失后无法再向已安装用户推送更新。

### 云端 hub

```bash
phonecast-hub -key <密钥> -listen :27190 -http :27191
```

状态页:浏览器打开 `http://<hub>:27191/` **登录后查看**(输入接入密钥,会话 Cookie 12 小时),含在线 agent、进行中会话、流量统计,3 秒自刷新;脚本用 `curl -H "Authorization: Bearer <密钥>" http://<hub>:27191/status` 取 JSON。

Linux systemd 部署示例(密钥放 root-only 的 env 文件,不进代码库):

```ini
# /etc/phonecast-hub.env (chmod 600)
PHONECAST_KEY=<你的密钥>

# /etc/systemd/system/phonecast-hub.service
[Unit]
Description=PhoneCast relay hub
After=network-online.target
[Service]
EnvironmentFile=/etc/phonecast-hub.env
ExecStart=/opt/phonecast/phonecast-hub -key ${PHONECAST_KEY} -listen :27190 -http :27191
Restart=always
User=nobody
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
[Install]
WantedBy=multi-user.target
```

放行 TCP 27190/27191(云厂商安全组 + 主机防火墙)。更新:交叉编译 `GOOS=linux GOARCH=amd64 go build`,替换二进制后 `systemctl restart phonecast-hub`。

## 安全模型

手机端只需输 **6 位配对码**,长密钥不用手打:

| 凭据 | 谁用 | 形态 |
|---|---|---|
| hub 密钥 | agent↔hub 注册、状态页登录 | 长随机串,只存在配置文件/env 里,永不手输 |
| 配对码 | 手机首次配对 | 6 位数字,30 分钟有效,可随时在托盘重生成 |
| 设备令牌 | 手机后续连接 | 32 字节随机,配对成功后自动下发并记住 |

- **配对码与令牌不上线**:agent 发 16 字节随机 nonce,手机回 `HMAC-SHA256(凭据, nonce)`。中继服务器与链路窃听者都拿不到可复用的凭据,也无法冒充观看端(**端到端认证,hub 零信任**)。
- **抗爆破**:配对码连错 5 次即自动作废并重新生成;每次失败延迟 1 秒。6 位数字在这个约束下足够安全。
- **设备名(room)不是密码**,只是 hub 上的路由标识;知道设备名也过不了认证。hub 对观看端有建连频率限制(每 IP 10 分钟 30 次)。
- **可撤销**:托盘「撤销已配对手机」清空全部令牌,所有手机需重新配对。
- 状态页需登录(密钥 → 12 小时 Cookie),脚本可用 `Authorization: Bearer`;失败同样限速锁定。设备名在页面上打码。
- hub 以 `User=nobody` + systemd 沙箱(ProtectSystem=strict 等)运行。
- **边界**:传输是明文 TCP,媒体流未加密——能嗅探你链路的人可以看到画面(但拿不到凭据、无法反控)。要过不可信网络建议套 WireGuard,尚未内置。

## 协议 v3(agent↔viewer↔hub 同一套)

握手(连接方先发):
- `PCV3` viewer 接入:`magic + u8 roomLen + room`(**不带密钥**)→ 回 1 字节状态(0=ok 2=设备名不在线 3=已有观看端 4=hub 错误)
- `PCA3` agent 注册:`magic + u8 keyLen + key + u8 roomLen + room`;`PCS3` agent 会话连接:`magic + key + 16B session id`

viewer 握手成功后,先做端到端认证再进媒体流:
- **ch0x20 挑战**(agent→viewer):16B 随机 nonce
- **ch0x21 应答**(viewer→agent):`u8 kind(0=配对码 1=设备令牌) + 32B HMAC-SHA256(凭据, nonce)`
- **ch0x22 结果**(agent→viewer):`u8 状态` +(首次配对成功时)64 字符 hex 设备令牌

之后统一帧 `[u8 ch][u32 len][payload]`:
- **ch0 视频**:首帧 12B codec meta(`u32 codecId + u32 w + u32 h`),之后 `[8B ptsAndFlags(bit63=config, bit62=关键帧)][H.264 ES]`
- **ch1 音频**:首帧 4B codec id(0=禁用,如手机A < Android 11),之后同视频格式(AAC ES,config 包=AudioSpecificConfig)
- **ch2 控制**:viewer→agent,每帧一条 scrcpy 控制消息:
  - 触摸 32B:`u8 type=2, u8 action, i64 pointerId, i32 x, i32 y, u16 videoW, u16 videoH, u16 pressure, i32 actionButton=0, i32 buttons=0`(videoW/H 必须等于 server 当前视频尺寸,否则被丢弃;viewer 跟随解码器输出尺寸)
  - 按键 14B:`u8 type=0, u8 action, i32 keycode, i32 repeat=0, i32 metaState=0`
- **ch0x10 start-session**(hub→agent 注册连接,16B session id)、**ch0x11 ping**(20s 保活)

hub 对已配对的两条连接做纯字节对拷,不解析媒体帧。

## 构建

```powershell
cd relay; go build -o phonecast-relay.exe .          # agent
cd hub;   go build -o phonecast-hub.exe .            # hub (本地调试)
cd viewer; .\gradlew.bat :app:assembleDebug          # App (Gradle 8.9/AGP 8.5.2/Kotlin 2.0.20)
```

`relay/proto.go` 与 `hub/proto.go` 是同一文件的两份拷贝(两个独立 Go module),改协议时同步两处。scrcpy-server 换版本:下载对应 release 的 `scrcpy-server-vX.Y`,同步改 `relay/main.go` 的 `serverVersion`(server 启动第一个参数必须与 jar 版本完全一致),并核对控制消息布局。`scrcpy-server-v2.7` 来自 [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)(Apache-2.0)。

## 连不上局域网时的排查

电脑托盘会自动检测并提示。按出现频率排序:

1. **手机开着 VPN / 科学上网(TUN 模式)**:最常见。VPN 会把去往局域网的流量一并吸进隧道,表现是"看似连上实则不通"(`nc` 探测甚至会返回 OPEN)。关掉 VPN,或打开它的「绕过局域网 / bypass private networks」选项。此时 App 会自动回落中继并给出提示。
2. **Windows 防火墙没放行**:托盘菜单「允许局域网访问」一键添加(需管理员确认)。规则只放行本程序、且限定 `remoteip=LocalSubnet`(同网段)。注意 Windows 常把家用 WiFi 判为「公用网络」,所以规则对所有配置文件生效,否则形同虚设。
3. **电脑上有虚拟网卡**(Docker / WSL / 代理 TUN):它们的地址手机连不上。二维码里会带上所有候选局域网地址,由手机逐个尝试,不依赖单一猜测。

## 已知边界

- **一台电脑同时只投一台手机**:scrcpy-server 单实例,插多台时在托盘「选择被投屏手机」里切换(切换会重启会话)。同一时刻也只允许一个观看端。
- viewer 退后台即断开,重进重连。
- 音频需手机A Android 11+,不满足时自动降级为仅视频。
- 手机A 旋转:解码器动态分辨率切换,新 SPS/PPS 内联送入;个别机型解码器不支持 DRC 时需重建 codec(未遇到再处理)。
- 音画各自低延迟播放,无严格同步时钟。
