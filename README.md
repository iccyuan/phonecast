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

### 手机B (viewer)

装 `viewer/app/build/outputs/apk/debug/app-debug.apk`,填 **地址**(hub 或电脑 IP:端口)、**密钥**、**配对码**,连接即投屏。无 WiFi 也可 USB 直连电脑:`adb reverse tcp:27184 tcp:27184` 后地址填 `127.0.0.1:27184`。

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

- 所有接入(agent 注册 / viewer / agent 会话连接 / HTTP 状态页)必须携带 **密钥**;常量时间比对,失败延迟 1s 断开,同 IP 失败 5 次锁 10 分钟。
- viewer 还须提供正确 **配对码** 才能匹配到手机A —— 密钥+配对码双因素,拿不全就无法观看/控制。
- 会话连接凭 16 字节一次性随机 session id 认领;状态页上配对码打码显示。
- hub 以 `User=nobody` + systemd 沙箱(ProtectSystem=strict 等)运行。
- **边界**:传输是明文 TCP,密钥与媒体流未加密——能嗅探你链路的人可以看到画面。要过不可信网络建议套 TLS/WireGuard,尚未内置。

## 协议 v2(agent↔viewer↔hub 同一套)

握手(连接方先发):`magic(4B) + u8 keyLen + key + 载荷`,magic:
- `PCV2` viewer 接入,载荷 `u8 roomLen + room` → 回 1 字节状态(0=ok 1=密钥错 2=配对码不在线 3=已有观看端 4=hub 错误)
- `PCA2` agent 注册(载荷同上);`PCS2` agent 会话连接(载荷=16B session id)

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

## 已知边界

- 单观看端(scrcpy-server 单实例);viewer 退后台即断开,重进重连。
- 音频需手机A Android 11+,不满足时自动降级为仅视频。
- 手机A 旋转:解码器动态分辨率切换,新 SPS/PPS 内联送入;个别机型解码器不支持 DRC 时需重建 codec(未遇到再处理)。
- 音画各自低延迟播放,无严格同步时钟。
