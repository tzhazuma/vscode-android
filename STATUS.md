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
