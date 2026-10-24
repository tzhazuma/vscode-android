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

## @vscode/sqlite3

Needs the SQLite amalgamation that the repo ships as a tarball, plus libuv headers
(`uv.h` comes from the sparse node source clone at `native/nodehdr/deps/uv/include`).

```sh
cd native/sqlite3/deps && tar xzf sqlite-autoconf-3390400.tar.gz
SQL=deps/sqlite-autoconf-3390400

$NDK/aarch64-linux-android31-clang -c -fPIC -O2 -DSQLITE_THREADSAFE=1 \
  -DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS4 -DSQLITE_ENABLE_FTS5 \
  -DSQLITE_ENABLE_JSON1 -DSQLITE_ENABLE_RTREE -DHAVE_USLEEP=1 \
  -I$SQL $SQL/sqlite3.c -o sqlite3.o

$NDK/aarch64-linux-android31-clang++ -shared -fPIC -std=c++17 -O2 \
  -DNAPI_DISABLE_CPP_EXCEPTIONS -DNODE_API_SWALLOW_UNTHROWABLE_EXCEPTIONS \
  -I"$HDR" -I"$NAPI" -I native/nodehdr/deps/uv/include -I$SQL -Isrc \
  src/backup.cc src/database.cc src/node_sqlite3.cc src/statement.cc sqlite3.o \
  -o prebuilt/sqlite3.node
```

## Remaining: @vscode/spdlog

`github.com/microsoft/vscode-spdlog` does not exist, and this machine has no npm to pull
the published package, so its binding sources are not on disk. It is the last of the four.
Options, in order: find the repo that actually hosts the binding, or take the source out
of the npm tarball once npm is available.

If VS Code only needs it for native logging, a stub exporting the same symbols may be
enough to boot -- worth testing before chasing the real build.

## @vscode/spdlog — the tarball route

`microsoft/vscode-spdlog` does not exist, but the published npm package does, and curl can
fetch it without npm:

```sh
V=0.15.8   # the version code-server pins
curl -s -o spdlog.tgz "https://registry.npmjs.org/@vscode/spdlog/-/spdlog-$V.tgz"
tar xzf spdlog.tgz && cd package
git clone --depth 1 https://github.com/gabime/spdlog.git deps/spdlog   # its submodule

$NDK/aarch64-linux-android31-clang++ -shared -fPIC -std=c++17 -O2 \
  -I"$HDR" -I"$NAPI" -Ideps/spdlog/include -Isrc \
  -DNODE_API_SWALLOW_UNTHROWABLE_EXCEPTIONS -DFMT_CONSTEVAL= \
  src/main.cc src/logger.cc -o prebuilt/spdlog.node
```

The tarball ships the binding (`src/main.cc`, `src/logger.cc`) but not the spdlog library
itself, because that is a git submodule; cloning it is the only missing piece.

## Status: all four are built

| module | size | notes |
|---|---|---|
| `watcher.node` | 562 KB | @parcel/watcher, inotify backend |
| `pty.node` | 96 KB | node-pty, real pty via bionic's forkpty |
| `sqlite3.node` | 1.8 MB | @vscode/sqlite3 + amalgamation with FTS/JSON1/RTREE |
| `spdlog.node` | 654 KB | logging, built from the npm tarball + the spdlog submodule |

All four are ARM aarch64 shared objects exporting `napi_register_module_v1` and needing
only `libc++_shared`, `libm`, `libdl` and `libc` -- the same set the Android node runtime
already ships.

## `pet` (Python Environment Tool)

`ms-python.vscode-python-envs` 1.40.0 is installed from the **universal** VSIX, which has no
`python-env-tools/bin/pet`. The platform VSIXes are where that binary lives, and Open VSX has
no `linux-arm64` build of the extension at all:
`https://open-vsx.org/api/ms-python/vscode-python-envs/linux-arm64` -> "Extension not found".
The Python extension therefore fails with `spawn .../python-env-tools/bin/pet ENOENT`.

`pet` is Rust, from `https://github.com/microsoft/python-environment-tools` (v2024.16.0).
Its own releases attach no assets, and its Linux builds are glibc
(`aarch64-unknown-linux-gnu`) -- neither runs under Android's bionic. It builds directly for
`aarch64-linux-android` with the NDK, though:

```sh
NDK=$HOME/Library/Android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/aarch64-linux-android28-clang
export CC_aarch64_linux_android=$NDK/aarch64-linux-android28-clang
export AR_aarch64_linux_android=$NDK/llvm-ar
export CARGO_PROFILE_RELEASE_LTO=false CARGO_PROFILE_RELEASE_DEBUG=0 \
       CARGO_PROFILE_RELEASE_STRIP=true

git clone --depth 1 https://github.com/microsoft/python-environment-tools.git
cd python-environment-tools
cargo build -p pet --release --target aarch64-linux-android
```

The result is `target/aarch64-linux-android/release/pet` (4.8 MB, PIE,
interpreter `/system/bin/linker64`). It is committed as
`android-app/app/src/main/res/raw/pet`; `PetPatch` copies it into every installed
`ms-python.vscode-python-envs-*` / `ms-python.python-*` extension at boot unless that
extension already ships a working `pet`.

Verified on the device: `pet --version` -> `pet 0.1.0`; `pet resolve .../usr/bin/python`
reports Python 3.14.8 and the prefix.

