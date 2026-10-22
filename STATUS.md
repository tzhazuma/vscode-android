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
