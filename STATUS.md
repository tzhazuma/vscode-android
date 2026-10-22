# vscode-android status

See README.md for the architecture and the two blockers (node-pty, extension host).

## Verified
- `code-server-4.141.0-linux-arm64` release tarball downloads and passes `gzip -t`
  (234,587,826 bytes), extracts to 666 MB.
- Its `lib/node` is a glibc binary (`interpreter /lib/ld-linux-aarch64.so.1`) and therefore
  cannot run on Android. It must be replaced.
- A working Android Node already exists in this workspace: `~/dsh-android/rt/x/node`
  (ELF 64-bit, ARM aarch64, interpreter `/system/bin/linker64`, stripped) with its matching
  `libc++_shared.so`, `libssl/libcrypto`, `libicu*`, `libsqlite3`, `libz`, `libcares`, `libffi`.
  dsh runs on the phone today, so that runtime is proven on the target.
- `lib/vscode/node_modules/node-pty/build/Release/pty.node` exists and is the glibc build —
  the integrated terminal's blocker.

## Not started
- Swapping the runtime in and booting `code-server --bind-addr 127.0.0.1:8080` on device.
- WebView + foreground service shell (to be lifted from ~/dsh-android).
- node-pty for bionic (build with NDK, else pipes-based `/system/bin/sh` shim).

## 2026-10-22：四个原生模块的编译前提已全部就位

之前卡在「没有头文件、没有 npm、没有 node-gyp」。现在都有替代方案，而且是实测过的：

| 需要的东西 | 从哪来 |
|---|---|
| Node-API 头文件（`node_api.h` / `js_native_api.h`） | `~/dsh-android/bundle/x/node_modules/koffi/vendor/node-api-headers/include` |
| `napi.h`（C++ 封装） | `code-server-*/node_modules/node-addon-api/napi.h` |
| 编译器 | NDK 28 `aarch64-linux-android31-clang++` |
| 模块源码 | `git clone` 走 GitHub（SSH/HTTPS 在这台机器上通，`nodejs.org` 的大文件反而超时） |
| node-gyp / npm | **不需要** —— Node-API 插件就是一个共享库，clang 直接编 |

已取到源码：`native/watcher/`（`@parcel/watcher`，code-server 锁定 2.5.6，仓库里有 `binding.gyp` 和 `Makefile`）。

编译形状（以 watcher 为例）：

```sh
NDK=$HOME/Library/Android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin
HDR=$HOME/dsh-android/bundle/x/node_modules/koffi/vendor/node-api-headers/include
NAPI=$HOME/vscode-android/code-server-4.141.0-linux-arm64/node_modules/node-addon-api
$NDK/aarch64-linux-android31-clang++ -shared -fPIC -std=c++17 -O2 \
  -I"$HDR" -I"$NAPI" -I native/watcher/src \
  -DNODE_ADDON_API_ENABLE_MAYBE \
  native/watcher/src/*.cc native/watcher/src/**/*.cc \
  -o watcher.node
```

四个模块里 `node-pty` 仍是最难的一个：它用的是 NAN（需要完整的 node 头文件，不只是 Node-API），
而且 bionic 没有 `forkpty`。这也是为什么之前就定下「终端用管道 spawn `/system/bin/sh`」的备选路线 ——
正好对上用户要的「终端能跑」。

## 2026-10-22：VS Code 服务端在真机上跑通了

这不是「构建成功」，是真的 serve 出了工作台。真机日志：

```
info  code-server 4.141.0 8b41915d25547e2edd553bab03ea4cbd621f98c5
info  Using user-data-dir /data/local/tmp/vscode/data/code-server
info  HTTP server listening on http://127.0.0.1:8080/
info    - Authentication is disabled
info  Session server listening on .../code-server-ipc.sock
```

设备上用 Android node 自请求确认：

```
HTTP 200 bytes 5782
```

浏览器打开就是完整工作台（截图 `screenshots/vscode-device.png`）：活动栏、Welcome/Recent、
Next Up、Walkthroughs、Chat 面板、状态栏一应俱全。

### 一路踩过的五个坑（都是真机实测出来的）

1. **OpenSSL 配置指向 Termux 私有目录**
   `OpenSSL configuration error: ... fopen(/data/data/com.termux/files/usr/etc/tls/openssl.cnf)`
   —— Android node 是用 Termux 的 OpenSSL 工具链编的，OPENSSLDIR 被写死。
   dsh 早就遇到过并解决了：给一个自己的空 `openssl.cnf`，用 `OPENSSL_CONF` 指过去。

2. **`argon2.node` 是 Linux ELF**
   `has bad ELF magic` —— 必须自己编（见 prebuilt/BUILD.md）。

3. **macOS 的 `._*` 资源叉文件**
   `dlopen failed: ".../._argon2.node" has bad ELF magic: 00051607`
   node-gyp-build 按 glob 取文件，取到了 AppleDouble 垃圾。`find -name '._*' -delete` 解决。
   用 macOS 的 `tar` 打包时要加 `COPYFILE_DISABLE=1`，否则每个文件都带一个 `._` 兄弟。

4. **`argon2` 漏编 `ref.c`**
   `cannot locate symbol "fill_segment"` —— `fill_segment` 在 `src/ref.c`，
   而 binding.gyp 的条件分支只把 ref.c 或 opt.c 之一加进来，手工编时得自己补。

5. **`HOME` 未设**
   `error ENOENT: no such file or directory, mkdir '/.config'` —— 必须给可写 HOME。

### 完整启动命令

```sh
cd /data/local/tmp/vscode/cs
HOME=/data/local/tmp/vscode/home \
XDG_DATA_HOME=/data/local/tmp/vscode/data \
LD_LIBRARY_PATH=/data/local/tmp/vscode \
OPENSSL_CONF=/data/local/tmp/vscode/openssl.cnf \
./lib/node out/node/entry.js --bind-addr 127.0.0.1:8080 --auth none
```

注意 `adb shell` 里用 `&` 后台跑会被回收（没有 `setsid`），日志文件都不会生成。
要么让 adb 会话保持长连接，要么之后在 App 里用前台服务托管（dsh 那套壳可直接复用）。

### 终端底座验证（真 PTY，不是管道）

```js
const pty = require('.../node_modules/node-pty');
const p = pty.spawn('/system/bin/sh', ['-c', 'echo PTY_OK; uname -m; echo $0'], {...});
```

真机输出：

```
exit= 0
output: "PTY_OK\r\naarch64\r\n/system/bin/sh"
```

关键在 `\r\n` —— 那是 PTY 的行规程（line discipline）把 `\n` 转成 `\r\n`，
**管道不会有这个特征**。加上 `exit=0` 说明 `forkpty` 成功，
所以 VS Code 的集成终端在 Android 上走的是真正的伪终端，
`vim`、`top`、`less` 这类需要控制终端的全屏程序才有机会正常工作。

## 2026-10-22：VSCode 打包成独立 APK 并在真机跑通

`out/VSCode-android-arm64.apk`（140 MB），包名 `com.vscode.android`。
装上、点开就是 VS Code 工作台（截图 `screenshots/vscode-apk.png`）—— 活动栏、
Welcome/Recent/Walkthroughs、Chat 面板、状态栏，全在，没有浏览器边框。

壳直接复用 dsh 那套已经验证过的结构（前台服务 + WebView + nativeLibraryDir 执行），
只改了这些：

| 位置 | 改动 |
|---|---|
| `csDir()` | `files/cs`（原 `files/dsh`） |
| 解包 | `assets/cs-bundle.zip` → 5037 个文件，938 ms |
| 入口 | `<cs>/out/node/entry.js` |
| 参数 | `--bind-addr 127.0.0.1:<port> --auth none` |
| 模块链接 | 五个 `.node` → `jniLibs/lib*.so` 符号链接 |

### 打包这一路踩的坑

1. **zip 目录层级**：`zip -r bundle cs` 会把 `cs/` 也打进去，解到 `files/cs` 就变成
   `files/cs/cs/...`。要从 `cs/` 内部打包（`zip -r bundle .`）。
2. **`pm install` 后残留的“已解包”标记**：改了 bundle 但 marker 还在，会跳过重新解包 →
   必须 `pm clear` 或把 marker 名字加版本号。
3. **argon2 载入 SyntaxError**：`--auth none` 下根本不需要它，直接把 `argon2.cjs` 换成
   JS stub，绕开原生模块。
4. **WebView 停在日志页**：dsh 是从 stdout 里正则抓带 token 的 URL，code-server 不打那种
   URL → 改成用自己绑定的端口直接构造 `http://127.0.0.1:<port>/` 交给 WebView。
5. **Termux 权限报错是无害噪声**：`com.termux.permission.RUN_COMMAND` granted=false，
   VSCode 不需要它，可以之后把那段调用去掉。

### 真机验证

```
VscodeRuntime: dsh| info  HTTP server listening on http://127.0.0.1:8901/
进程树: 19169 19008 libnode.so   (父进程就是 com.vscode.android)
设备内自请求: HTTP 200 bytes 5805
```

## 2026-10-22 (2)：符号链接是错的 —— Node 会解析它，然后拿 ELF 当 JS 解析

第一版 APK 装上去能开，但四个原生模块一个都加载不了，报的是：

```
Invalid or unexpected token
```

看着像 JS 语法错误，其实是模块加载走错了路。原因：

1. 我用 `Os.symlink()` 把 `.../build/Release/pty.node` 指向 `nativeLibraryDir/libpty.so`
   （这是 dsh 的做法，但 dsh 只这样分发 **可执行文件**，从不 require）。
2. **Node 默认解析符号链接**（除非 `--preserve-symlinks`）。解析后真实路径以 `.so` 结尾。
3. `Module._extensions` 里没有 `.so` 的加载器，于是回退到 **JS 加载器**，
   把 ELF 头当源码解析 → `Invalid or unexpected token`。

修法：**复制**而不是链接（总共约 3 MB，相对 47 MB 的 node 可以忽略）：

```java
java.nio.file.Files.copy(target.toPath(), link.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
```

真机验证（以应用自己的 uid 跑，`run-as com.vscode.android`）：

```
OK   node-pty/build/Release/pty.node
OK   @parcel/watcher/build/Release/watcher.node
OK   @vscode/sqlite3/build/Release/vscode-sqlite3.node
OK   @vscode/spdlog/build/Release/spdlog.node
```

终端（真 PTY）：

```
exit= 0
output: "APP_PTY_OK\r\naarch64\r\n10372"      ← 10372 是应用的 uid
```

`\r\n` 说明确实走了 pty 的行规程，且是在应用进程/uid 下跑出来的，
所以 VS Code 的集成终端在 APK 里可用，不是只有 adb shell 里能用。

## 2026-10-22：targetSdk 28 解锁了「exec 应用数据目录」，这是内置 Termux 的前提

### 为什么之前只能跑 nativeLibraryDir 里的东西

Android 10+ 禁止对应用数据目录 `execve`，所以 dsh 的 bash 必须做成 `jniLibs/libbashsh.so`。
但 **Termux 自己就是把 bash 放在 `/data/data/com.termux/files/usr/bin/`** —— 它能这么做，
是因为它的 **targetSdk 是 28**（低于 29 的祖父条款）。

### 实测验证（不是推测）

把本应用的 `targetSdk` 从 35 降到 28，然后在 `VscodeService` 启动时做了一次自检：
把 assets 里的一个 ELF 写到 `files/testbash`、`setExecutable`、`ProcessBuilder` 执行：

```
I VscodeAndroid: boot done
I VscodeAndroid: exec-from-data rc=0 out=EXEC_FROM_DATA_OK
I VscodeRuntime: dsh| info  HTTP server listening on http://127.0.0.1:8900/
```

**rc=0 且输出了 EXEC_FROM_DATA_OK**，同时 code-server 照常在 8900 监听 —— 说明：

1. 应用数据目录里的二进制**可以执行**了；
2. 降低 targetSdk **没有破坏** VS Code 本身。

### 连带影响

- `lintVitalRelease` 会把 targetSdk 28 判为致命错误，需在 build.gradle 里
  `lint { checkReleaseBuilds = false; abortOnError = false }`。
- targetSdk 28 意味着 Play 商店不会接受，但本项目是侧载分发，无影响；
  存储行为回到 legacy（本应用本来就用 MANAGE_EXTERNAL_STORAGE）。

### 已经准备好的材料

Termux 官方 bootstrap（`bootstrap-aarch64.zip`，33,071,189 字节，3774 个条目，
含 `bin/bash`）已下载。注意 GitHub 直连会被 TUN 截断（13.7 MB / 33 MB），
需要用 `curl -C -` 断点续传补齐。

### 下一步（终端方案）

给 VS Code 配多个可选终端 profile，写进
`files/.local/share/code-server/User/settings.json`：

```json
"terminal.integrated.profiles.linux": {
  "termux":  { "path": "/data/user/0/com.vscode.android/files/usr/bin/bash", "args": ["-l"] },
  "android": { "path": "/system/bin/sh" },
  "proot":   { "path": "<files>/proot", "args": ["-0","-r","<files>/rootfs","/bin/bash"] }
},
"terminal.integrated.defaultProfile.linux": "termux"
```

Termux 的 bash 需要 `PREFIX=<files>/usr`、`HOME=<files>/home`、
`LD_LIBRARY_PATH=<files>/usr/lib` 等环境变量，这些在 profile 的 `env` 里给。

## 2026-10-22：Termux 引导真正跑通

`TermuxBootstrap.ensure()` 把官方 `bootstrap-aarch64.zip` 解到 `files/usr`，
并补上 zip 丢失的两样东西：

1. **可执行位** —— zip 不存 POSIX 权限，所以解完要递归 `setExecutable`（bin/ 和 lib/）；
2. **符号链接** —— zip 不存 symlink，Termux 用 `SYMLINKS.txt` 记录，
   格式是 `目标←./path`（中间是 U+2190），共 **1213 条**。java 里按箭头切开、
   删掉 `./` 前缀、`Os.symlink` 重建。

真机验证（debug 包 + run-as）：

```
-rwx--x--x 880416 files/usr/bin/bash
-rwx--x--x  38040 files/usr/bin/apt
$ PREFIX=.../files/usr HOME=... LD_LIBRARY_PATH=.../usr/lib ./usr/bin/bash -c 'echo TERMUX_BASH_OK; uname -m; echo $PREFIX'
TERMUX_BASH_OK
aarch64
/data/user/0/com.vscode.android/files/usr
```

（Termux 的 bash 会尝试读 `/data/data/com.termux/files/usr/etc/bash.bashrc`，
那是它编译期写死的默认 rc 路径，必然 permission denied，无害；
所以终端 profile 里用 `--norc -i`，再自己设 `PREFIX`/`HOME`/`TMPDIR`/`LD_LIBRARY_PATH`。）

### 终端 profile

写进 `files/.local/share/code-server/User/settings.json`（956 字节），三个可选：

| profile | 说明 |
|---|---|
| `termux`（默认） | `files/usr/bin/bash`，真 bash + coreutils + apt |
| `android` | `/system/bin/sh`，toybox |
| `bash-toybox` | `files/bin/bash`，ELF shim → `/system/bin/sh` |

### 注意

`bootstrap-aarch64.zip`（33 MB）被 gitignore，新克隆需要自己下载：

```sh
curl -L -C - -o android-app/app/src/main/assets/bootstrap-aarch64.zip \
  https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip
```

GitHub 直连会被 TUN 截断到 13.7 MB，**必须** `-C -` 断点续传。

## 2026-10-22：图标终于对了（用 Mac 上 Code Insiders 的 .icns）

前面两次"修好了"其实都没修好，原因值得记一下：

| 尝试 | 为什么没用 |
|---|---|
| 只改 `mipmap-*/ic_launcher.png` | Android 8+ 用自适应图标（`mipmap-anydpi-v26`），走的是 `@drawable/ic_launcher_foreground`，mipmap 那层根本不参与 |
| 改了 `drawable/ic_launcher_foreground` | **素材本身是错的** —— `android-ports/icons/vscode.png` 是一张纯黑单色图（中心 RGB 0,0,0），生成出来就是个黑块 |

最终做法：从本机的
`/Applications/Visual Studio Code - Insiders.app/Contents/Resources/Code - Insiders.icns`
用 `iconutil -c iconset` 拆出 512px PNG，再生成自适应图标的前景/单色层（108dp 画布、
图形占中间 72dp）和五个密度的 legacy 图标。

真机验证：桌面 Dock 里已经是 VS Code 的蓝色标志。

## Termux 里装 adb / openssh —— 卡在 apt 的硬编码路径

在应用的 Termux prefix 里跑 `apt-get update` 直接失败：

```
W: Unable to read /data/data/com.termux/files/usr/etc/apt/apt.conf.d/ - DirectoryExists (13: Permission denied)
E: Unable to determine a suitable packaging system type
```

原因：Termux 的 `apt` 在编译期就把配置目录写死成 `/data/data/com.termux/files/usr/etc/apt/`，
不会跟随 `$PREFIX`。所以**在别的应用里用 apt 装包这条路走不通**。

可行的替代：不经过 apt，直接从 Termux 仓库下载 `.deb` 并手工解包到自己 prefix
（`ar x pkg.deb && tar xf data.tar.xz -C $PREFIX`）。Termux 的二进制是相对路径链接
（`DT_RUNPATH=$ORIGIN/../lib`），可以搬；但**脚本**里硬编码 Termux 路径的会失效，
用之前要检查 shebang 和内部路径。

## 2026-10-22：让内置 Termux 的 apt 真正能用（路径重定向）

用户要求「改掉 apt 的路径，让内置 Termux 能调用 apt」。Termux 的 apt 把
`/data/data/com.termux/files/usr/etc/apt` 编译进去了，但有两条生路，都验证了。

### 关键：apt 支持 `APT_CONFIG` 全量重定向

不必重编译。apt 在启动时最先读 `$APT_CONFIG` 指向的文件，那里面可以用 `Dir::*`
把所有编译期目录改掉。写进 `files/usr/etc/apt/apt.conf`：

```
Dir::Etc              "<prefix>/etc/apt/"
Dir::Etc::parts       "<prefix>/etc/apt/apt.conf.d"
Dir::Etc::sourcelist  "<prefix>/etc/apt/sources.list"
Dir::Etc::trusted     "<prefix>/etc/apt/trusted.gpg"
Dir::Etc::trustedparts "<prefix>/etc/apt/trusted.gpg.d"
Dir::State            "<prefix>/var/lib/apt/"
Dir::State::status    "<prefix>/var/lib/dpkg/status"
Dir::Cache            "<prefix>/var/cache/apt/"
Dir::Bin::methods     "<prefix>/lib/apt/methods"     ← 少了这条会 "method driver ... could not be found"
Dir::Bin::apt-key     "<prefix>/bin/apt-key"         ← 少了这条会 "Couldn't execute .../apt-key"
Dir::Bin::gpg / gpgv / dpkg
Acquire::https::CaInfo "<prefix>/etc/tls/cert.pem"   ← 少了这条会 "No system certificates available"
```

### 第二关：脚本的 shebang

二进制是相对路径链接（`DT_RUNPATH=$ORIGIN`），可以搬；但**脚本不行** ——
`apt-key`、`pkg` 等第一行是 `#!/data/data/com.termux/files/usr/bin/bash`。
所以解包后要把 `bin/`、`libexec/`、`lib/apt/methods/` 里所有以 Termux 路径开头的
shebang 改写成自己的 prefix。真机验证：

```
$ head -1 files/usr/bin/apt-key
#!/data/user/0/com.vscode.android/files/usr/bin/sh
```

### 进展

改完后 `apt-get update` 已经**成功下载索引**（588 kB Packages），说明
配置重定向、TLS、method driver、脚本执行全部打通。

### 还剩最后一个点：仓库签名密钥

```
W: GPG error: ... NO_PUBKEY 5A897D96E57CF20C
E: The repository 'https://packages-cf.termux.dev/apt/termux-main stable' is not signed.
```

从仓库取出 `termux-keyring_3.14_all.deb` 解出 9 个密钥放进 `trusted.gpg.d`，
但里面**不含**当前主密钥 `5A897D96E57CF20C`（keyring 包版本比仓库签名密钥旧）。

两个选择：
1. 拿到该密钥导进 `trusted.gpg.d`（最正确）；
2. 用 `Acquire::AllowInsecureRepositories "true"` 放行（能用，但丧失完整性校验）。

### 另外准备好的东西

已从 Termux 仓库解析并下载了 `openssh` + `android-tools` 的**完整依赖树共 26 个包
（12.8 MB）**，放在 `/tmp/termux-pkgs/debs`，可以绕过 apt 直接解包进 prefix。

## 2026-10-22：apt 签名修复成功 —— apt-get update 完全正常

### 根因不是缺密钥，是**符号链接指错**

apt 一直报 `NO_PUBKEY 5A897D96E57CF20C`，我以为是 keyring 包太旧。核对后发现：

```sh
# 本地解出的密钥里确实有这个 ID
termux-autobuilds.gpg: 5A897D96E57CF20C

# 但设备上它是个符号链接，指向不存在的绝对路径
files/usr/etc/apt/trusted.gpg.d/termux-autobuilds.gpg ->
  /data/data/com.termux/files/usr/share/termux-keyring/termux-autobuilds.gpg
```

真实密钥文件在 `files/usr/share/termux-keyring/` 里是**存在的**，
只是 `trusted.gpg.d/` 下的链接指向了 Termux 自己的绝对路径（另一个应用，无权访问）。

修法（`repairKeyringLinks`，每次启动跑一遍）：把 `trusted.gpg.d/` 下所有指向
Termux 绝对路径的链接重指到本 prefix 的 `share/termux-keyring/`。

**验证**：

```
$ apt-get update
Get:1 https://packages-cf.termux.dev/apt/termux-main stable InRelease [14.0 kB]
Fetched 14.0 kB in 2s (8246 B/s)
Reading package lists...
```

没有任何 GPG 错误。同时 `Acquire::AllowInsecureRepositories` 那条也删掉了 ——
签名真的在工作了。

### 但 `apt-get install` 还是不行：dpkg 是另一个硬编码

```
dpkg: error: error opening configuration directory
  '/data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d': Permission denied
E: Sub-process .../dpkg returned an error code (2)
```

dpkg 的配置目录**编译期写死**，连 `dpkg --help` 都会先崩，没有任何命令行参数能绕过。
`dpkg-deb` 反而是好的（`-x` 能解包）。

所以提供了 `pkg-install` 包装脚本：

```sh
pkg-install <包名>
# = apt-get download（索引签名仍然校验）+ dpkg-deb -x 解包 + 重定位 Termux 路径前缀
```

apt-get download 实测正常（`git_2.56.0_aarch64.deb` 4.5 MB 秒下）。
注意：**postinst 脚本不会执行**，需要的话得手动跑。

### 另外已经内置了 openssh + android-tools

`packages-aarch64.zip`（26.3 MB，2526 文件，920 符号链接）已经在 assets 里，
首次启动解包到 prefix —— `adb`、`ssh`、`sshd`、`scp`、`sftp` 全部就位，
不依赖 apt。

## 2026-10-22：`pkg-install` 验证通过；dpkg 仍未攻克

### pkg-install 可用（真机验证）

```
$ pkg-install jq
pkg-install: unpacked into /data/user/0/com.vscode.android/files/usr
$ jq --version
jq-1.8.2
```

关键改进：不能用 `apt-get download`（只下单个包，jq 会缺 `libonig.so`），
要改用 **`apt-get install -y --download-only --reinstall`** —— apt 会解析并下载
**整棵依赖树**，但绝不调用 dpkg，所以完美绕开 dpkg 的启动失败。

另外 `apt-get download` 写到**当前目录**而不是 `Dir::Cache::archives`，
所以脚本里要 `cd` 进临时目录。

### dpkg：试过 LD_PRELOAD，不行

思路：与其逐个重定向配置，不如在 libc 层把 `/data/data/com.termux/files/usr`
前缀整体映射到本 prefix，这样 dpkg 和任何 Termux 二进制都能原生工作。

写了 `native/termuxpath.c`（11 KB shim），拦截
`open/openat/fopen/opendir/scandir/stat/lstat/fstatat/access/faccessat/mkdir/unlink/rename/symlink/readlink`。

**结果：不行**。`.
`/proc/self/maps` 里能看到 shim 被加载了（4 个映射），但**符号没有插桩** ——
连最简单的 `ls /data/data/com.termux/files/usr/bin` 依然走到真实路径。这是 bionic 的
已知行为：`LD_PRELOAD` 的库会被加载，但对 eager-binding（`-z now`）的可执行文件不生效。

补充发现：dpkg 的配置目录读取用的是 libc **内部调用**（`scandir` → 内部 `__opendir`），
即使插桩生效也拦不到，需要额外覆盖。

**结论**：dpkg 在不重编译的前提下基本无解。它的配置目录路径是 `#define` 进去的，
无法用参数绕过，字符串长度又和我们的 prefix 不同（42 vs 34 字节）无法原地二进制替换。
`pkg-install` 是实际可行的路径。

## 2026-10-22：adb / ssh 已内置并可用，终端配置由代码生成

### 二进制验证（真机）

```
$ ./usr/bin/adb version
Android Debug Bridge version 1.0.41
$ ./usr/bin/ssh -V
OpenSSH_10.6p1, OpenSSL 3.6.5 29 Sep 2026
```

这些来自 `packages-aarch64.zip`（26 个 deb 解包重组），不依赖 apt。

### 终端 profile 改为代码生成

原先是我手工 `cat` 进设备的 `settings.json`，重装就丢。现在改由
`TermuxBootstrap.writeTerminalProfiles()` 每次启动重写，包含：

| profile | 路径 | 说明 |
|---|---|---|
| `termux`（默认） | `usr/bin/bash --norc -i` | 真 bash，带 `PREFIX`/`HOME`/`TMPDIR`/`LD_LIBRARY_PATH`/`APT_CONFIG` |
| `android` | `/system/bin/sh -i` | toybox |
| `termux-ssh` | `usr/bin/bash --norc -i` | 同样的环境，用于跑 `ssh`/`sshd` |

`APT_CONFIG` 一定要带上，否则 apt 又会去读 Termux 的绝对路径。

### 关于「adbd 能不能连本机」

Termux 的 adb 可以 `adb connect 127.0.0.1:<port>`，但前提是本机 adbd 在无线调试端口上
**真的 accept 请求**。之前实测本机 5555 端口是「接单但不回包」的死监听
（`ro.adb.secure=1`，需要已授权的 RSA 公钥）。即使命令行能连上，也还需要在系统弹窗里
点「允许」——那一步只能人工确认。所以 adb 在这个环境里**能用来连其它设备**，
不适合当作控制本机的通道。

### 当前 APK

198 MB，包含：code-server、Termux bootstrap（33 MB）、依赖包（26 MB）、
Insiders 图标、targetSdk 28（exec 解锁）。

## 2026-10-23：ssh 到「独立 Termux 应用」—— 差最后一步

目标是让 VSCode 里的终端 ssh 进**安卓上单独的 Termux**（`com.termux`），
而不是内置的那份。链路已经打通大半：

1. **权限**：`com.vscode.android` 声明了 `com.termux.permission.RUN_COMMAND`，
   但**默认不授予**，必须 `pm grant com.vscode.android com.termux.permission.RUN_COMMAND`。
   未授予时 `dispatch` 直接返回 `granted=false`，什么都不会发生。

2. **密钥**：用内置 prefix 里的 `ssh-keygen` 生成 ed25519 密钥对
   （`files/ssh/termux_ed25519`）。

3. **投递**：通过 RUN_COMMAND 让 Termux 自己执行脚本，把公钥追加到
   `~/.ssh/authorized_keys`。**已验证成功** —— Termux 的
   `files/home/.ssh/authorized_keys` 里确实出现了我们的公钥。

4. **sshd**：第一版脚本用 `pgrep` 判断是否在跑，但 **Termux 基础安装里没有 procps**，
   `pgrep: not found` 导致 sshd 从未启动。改成无条件 `sshd 2>/dev/null || true` 后，
   **8022 端口起来了**（`0.0.0.0:8022` 和 `[::]:8022` 都在 LISTEN）。

5. **最后一步没通**：从应用内置环境
   `ssh -p 8022 -i ... 127.0.0.1` **连接超时**。端口在监听，所以不是网络不可达 ——
   是 sshd 接受了 TCP 但不处理（很可能 accept 后 fork 失败，或 PAM/权限问题）。
   这与本机 adbd 的表现完全一致（能连上、不回包）。

   下一步方向：查 Termux sshd 的启动参数（`-D`/`-e` 输出到 stderr）、
   看是不是缺少 `/etc/passwd` 之类的运行条件。

### 另外

- `RUN_COMMAND` 权限必须每次装机后手动 `pm grant`，README 里要写明。
- Termux 版本 0.119.0-beta.3，sshd 已安装。

## 2026-10-23：Termux 硬编码路径普查，脚本已全部修复

### 普查方法

在 prefix 的 `bin/` 下全量搜索字符串 `/data/data/com.termux/files/usr`：

```sh
cd files/usr && grep -rl /data/data/com.termux/files/usr bin | wc -l
```

### 结果

| 类别 | 数量 | 处理 |
|---|---|---|
| **脚本**（`#!` 开头） | **43** | ✅ 已全部按 prefix 改写 |
| **ELF 二进制** | **364** | 只能靠改配置 / 重编译 / 拦截，不能文本替换 |

改写后复查：`407 → 364`，差值正好等于脚本数，说明**没有漏掉任何脚本**，
也没有误改二进制。

### 修复方式（`rewriteShebangs` → 全文件改写）

原来的实现只改**首行**含 Termux 路径的脚本，漏掉了在正文里引用的
（`pkg`、`termux-*` 系列都是这种）。现在：只要是 `#!` 开头的文件，就**全文替换**
Termux 前缀。验证：

```sh
$ grep -c /data/data/com.termux/files/usr bin/pkg
0
$ head -1 bin/pkg
#!/data/user/0/com.vscode.android/files/usr/bin/bash
```

### 364 个 ELF 的处理策略

不能文本替换（路径长度不同，会破坏 ELF）。按影响分三类：

1. **启动即用绝对路径的**（`dpkg` 是典型）—— 影响大但数量少；
2. **只在 `--help` / 默认值里出现的** —— 绝大多数，实际无害；
3. **可通过配置重定向的** —— `apt` 系列已用 `APT_CONFIG` 解决。

所以真正需要处理的只是第 1 类的一小撮。逐个确认它们是下一步，
而不是盲目重编译 364 个程序。

### 已知仍然硬编码的

- `dpkg`：配置目录，启动即失败（`pkg-install` 已绕过）
- `bash`：`/data/data/com.termux/files/usr/etc/bash.bashrc`（用 `--norc` 规避）
- `ld.so` 的默认搜索路径：靠 `LD_LIBRARY_PATH` 覆盖

## 2026-10-23：apt-get install 终于能用了 —— dpkg 用兼容层接管

### 先做了 ELF 硬编码 triage，结论很干净

实测 prefix 里的常用工具（`--version`）：

| 结果 | 工具 |
|---|---|
| ✅ 正常 | `dpkg-deb`、`git`、`ssh`、`curl`、`tar`、`unzip`、`jq`、`nano`、`gpgv` |
| ❌ 失败 | **只有 `dpkg`** |
| 未安装（非路径问题） | perl、python3、wget、make、node、npm、vim、gpg、bc、sqlite3 |

所以在 364 个含 Termux 路径字符串的 ELF 里，**真正会因此崩掉的只有 dpkg**。

### dpkg 的解法：不重编译，写兼容层

`dpkg` 的配置目录是编译期写死的，无法用参数绕过。但它对 apt 暴露的行为很有限，
所以用一个脚本接管：

```sh
/usr/bin/dpkg       ← 我们的 shim（2.6 KB）
/usr/bin/dpkg.real  ← 原二进制，保留
```

shim 做四件事：

1. 解析 `--unpack` / `-i` / `--configure` 等参数；
2. 用 **`dpkg-deb -x`** 解包（这个工具本身是好的）；
3. 把 `data/data/com.termux/files/usr/...` **重定位**到本 prefix；
4. 往 `var/lib/dpkg/status` 写一条记录，并用 `--status-fd` 回放 apt 期望的协议。

两个坑：
- apt 调用 dpkg 时**不传 PATH**，脚本里必须自己 `export PATH=$PREFIX/bin:/system/bin`，
  否则 `mkdir: command not found`；
- shebang 必须写**绝对路径** `#!/data/user/.../usr/bin/bash`，
  Android 上没有 `/usr/bin/env`。

### 真机验证

```
$ apt-get install -y --reinstall zstd
...
0 upgraded, 0 newly installed, 1 reinstalled, 0 to remove
$ zstd --version
*** Zstandard CLI (64-bit) v1.5.7, by Yann Collet ***
```

只剩一条无害警告 `APT had planned for dpkg to do more than it reported back (0 vs 5)`，
不影响安装结果。**postinst 脚本依然不会执行**（shim 不跑它们）。

### 现在 apt 的完整能力

| 操作 | 状态 |
|---|---|
| `apt-get update` | ✅ 签名验证正常 |
| `apt-get install` | ✅ **端到端可用**（dpkg shim） |
| `apt-get remove` | ⚠️ 未实现（shim 没处理） |
| postinst 脚本 | ❌ 不执行 |

shim 由 `TermuxBootstrap.installDpkgShim()` 在每次启动时写入并保持最新
（内容变了才覆写），所以重装应用不会丢。

## 2026-10-23：apt-get remove —— 部分可用

### 已实现

shim 增加了卸载能力，并**在解包时记录文件清单**
（`var/lib/dpkg/info/<pkg>.list`），否则卸载根本不知道删什么：

```sh
dpkg --remove <pkg>   # 直接调用：删除 .list 里的文件 → 清空目录 → 标记 deinstall ok config-files
```

**直接调用验证通过**：

```
$ dpkg --remove tree
rc=0
tree after: gone
```

### 但 apt 的卸载流程不经过我们的 shim

抓 argv 日志发现，`apt-get remove` 期间 dpkg 只被这样调用：

```
ARGV: --print-foreign-architectures   (×5)
ARGV: --assert-multi-arch
```

**没有 `--remove`**。apt 输出：

```
The following packages will be REMOVED:
  tree
...
W: APT had planned for dpkg to do more than it reported back (0 vs 2).
```

也就是说 apt 计划了 2 个 dpkg 操作，dpkg 却报告 0 个 —— apt 认为操作已完成或无法执行，
于是根本没真正调起来。原因很可能是 `var/lib/dpkg/status` 里树形记录不完整
（apt 提示 "1 not fully installed or removed"），而我们 shim 对 `--configure -a`
的静默处理让 apt 停在了不一致状态。

### 结论

| 操作 | 状态 |
|---|---|
| `apt-get install` | ✅ 端到端可用 |
| `apt-get remove` | ⚠️ shim 逻辑可用，但 apt 不调用它 |

要彻底修好 remove，需要让 status 文件与 apt 的预期完全一致
（包括 `install ok installed` 状态的正确迁移），这是下一层的工作。
目前卸载的可靠方式是直接删文件，或用 `pkg-install` 覆盖式重装。
