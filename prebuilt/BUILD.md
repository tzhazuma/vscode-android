# How these were built

Two of the four modules VS Code needs are compiled here for `arm64-v8a` Android, with no
node-gyp and no npm (neither exists on this machine):

```sh
NDK=$HOME/Library/Android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin
HDR=$HOME/dsh-android/bundle/x/node_modules/koffi/vendor/node-api-headers/include
NAPI=$HOME/vscode-android/code-server-4.141.0-linux-arm64/node_modules/node-addon-api

# @parcel/watcher (code-server pins 2.5.6)
$NDK/aarch64-linux-android31-clang++ -shared -fPIC -std=c++17 -O2 \
  -fstack-protector-strong -DNAPI_DISABLE_CPP_EXCEPTIONS \
  -DWATCHMAN -DINOTIFY -DBRUTE_FORCE \
  -I"$HDR" -I"$NAPI" -I native/watcher/src \
  native/watcher/src/{binding,Watcher,Backend,DirTree,Glob,Debounce}.cc \
  native/watcher/src/watchman/{BSER,WatchmanBackend}.cc \
  native/watcher/src/shared/BruteForceBackend.cc \
  native/watcher/src/linux/InotifyBackend.cc \
  native/watcher/src/unix/legacy.cc \
  -o prebuilt/watcher.node

# node-pty
$NDK/aarch64-linux-android31-clang++ -shared -fPIC -std=c++17 -O2 \
  -DNAPI_VERSION=8 -I"$HDR" -I"$NAPI" \
  -I native/node-pty/src -I native/node-pty/src/unix \
  native/node-pty/src/unix/pty.cc -o prebuilt/pty.node
```

## Why node-pty needs no patch

The usual story is "bionic has no forkpty, patch it like Termux does". That is out of date:
NDK 28's `<pty.h>` declares `openpty()` and `forkpty()` as `__INTRODUCED_IN(23)`, and both
resolve from libc at that API level. The built module carries
`U forkpty@LIBC` / `U openpty@LIBC` and links cleanly with only
`libc++_shared`, `libm`, `libdl`, `libc`.

So VS Code's terminal can have a **real pty**, not a pipes-based fallback.

Both binaries export `napi_register_module_v1` and are plain ARM aarch64 shared objects.
