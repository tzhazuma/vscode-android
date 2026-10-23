# native VS Code on Android (arm64-v8a)

Real VS Code, running on the phone itself, no desktop and no remote host.

## Why this is "code-server in an app", not Electron

VS Code's desktop UI is Electron. Electron has no Android target and cannot be built for
one, so shipping the *desktop* UI natively is not possible. The honest route — and the one
that still gives you the actual VS Code workbench, offline, on-device — is:

    Android APK
     ├─ Node runtime (Android aarch64 ELF)          ← reuse ~/dsh-android/rt/x/*
     ├─ code-server bundle (the real VS Code workbench, as a web app)
     ├─ foreground service that runs it on 127.0.0.1
     └─ WebView pointed at that localhost URL

The workbench you get is the genuine VS Code UI (same source, same extensions API), because
`code-server` *is* VS Code with a browser front-end. This is also how Coder ships it.

## Status: it ships and it runs

`android-v1` carries a 139 MB APK. On a vivo PA2573 (Android 16, arm64) it installs, extracts
its bundle (5037 files, ~1 s), starts code-server on 127.0.0.1, and renders the full workbench
inside the app with no browser chrome.

The five native modules are cross-compiled for arm64-v8a with plain `clang++` -- no npm, no
node-gyp, and no node source build:

| module | how |
|---|---|
| `@parcel/watcher` | sources from GitHub, inotify backend |
| `node-pty` | **no patch needed**: NDK 28's `pty.h` declares `forkpty` from API 23 |
| `@vscode/sqlite3` | amalgamation with FTS3/4/5, JSON1, RTREE |
| `@vscode/spdlog` | npm tarball plus the spdlog submodule it depends on |
| `argon2` | npm tarball; replaced by a JS stub in the app because auth is disabled |

Verified as the app's own uid (`run-as`): all four modules load, and node-pty spawns
`/system/bin/sh` with output carrying CR-LF from the pty line discipline -- so the integrated
terminal has a real pty, which is what full-screen programs need.

## What already exists that we reuse

`~/dsh-android` solved the hard Android packaging problem for the same shape of app: a
foreground service hosting Node, with the runtime shipped inside the APK.

    ~/dsh-android/rt/x/node        ELF 64-bit, ARM aarch64, interpreter /system/bin/linker64
    ~/dsh-android/rt/x/lib*.so     libc++_shared, libssl/libcrypto, icu78, sqlite3, zlib, cares, ffi

That Node **runs on this phone today** (dsh is proof), so the runtime half is done — the
`lib/node` that code-server's Linux tarball ships is a glibc binary
(`interpreter /lib/ld-linux-aarch64.so.1`) and cannot run under bionic, so it must be
replaced by the dsh one.

## The two real blockers

1. **node-pty.** `lib/vscode/node_modules/node-pty/build/Release/pty.node` in the release
   tarball is built against glibc; it will not load under bionic. Without it VS Code's
   integrated terminal cannot start a pty.
   Plan, cheapest first:
   a. build node-pty from source with the NDK (forkpty/openpt should be reachable through
      bionic — Termux carries a patched node-pty, which confirms the approach);
   b. if that fights back, patch code-server's terminal to spawn `/system/bin/sh` with
      pipes via `child_process.spawn` — loses full-screen curses apps but gives a working
      shell, which is what the terminal is for here.

2. **Extension host permissions.** Android's `/data` is noexec-ish for app-private files in
   places, and extensions unpack native modules. dsh already hit this class of problem for
   node addons; reuse whatever it did.

## The terminal question (the user's main interest)

Termux is *not* required. A shell can be spawned straight from the app:

    /system/bin/sh        toybox, always present
    $PREFIX/bin/bash      only if Termux is installed, and then its $PREFIX is prepopulated

So the terminal should:
- default to `/system/bin/sh`;
- detect Termux (`com.termux`) and offer its `$PREFIX/bin/bash` plus Termux's coreutils as an
  optional, richer environment;
- expose the same thing over `adb shell` semantics (same toybox built-ins) so behaviour
  matches what the user already knows from Termux.

## Status

Scaffold only. `code-server-4.141.0-linux-arm64/` is extracted here for size/structure
investigation (666 MB, node-pty present, node is glibc). No APK yet.
Next concrete step: swap in the Android node, get `code-server --bind-addr 127.0.0.1:8080`
to boot on the device, then wrap it in the WebView + foreground service shell taken from
dsh-android.

## Native-module inventory (measured, not guessed)

`find . -name "*.node"` in `code-server-4.141.0-linux-arm64` gives 15 files. Feeding the
macOS node into `out/node/entry.js` dies on the very first one (`argon2.node`, "slice is not
valid mach-o"), which confirms every one of them is a Linux ELF that bionic cannot load.

| module | what it is | needed on Android? |
|---|---|---|
| `@vscode/spdlog` | logging | yes — build for bionic |
| `@vscode/sqlite3` | VS Code state / history store | yes — build for bionic |
| `@parcel/watcher` | file-change watching | yes — build for bionic |
| `node-pty` | integrated terminal | yes — the hard one |
| `argon2` | login password hashing | **no** — run `--auth none` |
| `kerberos` | Kerberos auth | no |
| `@vscode/deviceid` (`windows.node`) | Windows telemetry id | no, Windows-only |
| `@vscode/native-watchdog` | crash watchdog | no (optional) |
| `@github/copilot-sdk-linux-arm64` | Copilot CLI | no — remove |
| `extensions/copilot/*` | Copilot extension | no — remove |
| `extensions/ms-vscode.js-debug/*win32*` | Windows debugger helper | no — Windows-only |

So the honest minimum is **four modules** (spdlog, sqlite3, @parcel/watcher, node-pty), and
`--auth none` removes the fifth. Everything else ships as JS or is platform-irrelevant.

Removals are safe because the extension host loads native modules lazily: deleting
`extensions/copilot` and the `@github/copilot-sdk-*` trees costs the Copilot feature, not
the workbench.

## Boot order on device

    libnode.so          (reused from ~/dsh-android/rt/x/node, /system/bin/linker64)
    + its .so deps      libc++_shared, libssl/libcrypto, icu78, sqlite3, zlib, cares, ffi
    + the four modules  built with the NDK against the same node headers
    → `libnode.so out/node/entry.js --bind-addr 127.0.0.1:8080 --auth none`

Executables cannot live in `filesDir` on Android (noexec), so the node binary has to be
installed as a jniLib and executed from `nativeLibraryDir` — the Octave port hit exactly
this and worked around it the same way.

## The release tarball ships prebuilts only — no sources

Two things discovered while trying to start the cross-compile:

1. `lib/vscode/node_modules/@vscode/spdlog/` contains only `build/`, `index.js` and
   `package.json` — **no `binding.gyp`, no `src/`**. The same is true of the other native
   modules in this tree. So there is nothing here to recompile: each of the four modules has
   to be fetched from its own upstream (npm tarball or GitHub repo) at a version matching the
   one VS Code pins, then built against the Android node headers.
2. There is no `npm` and no `node-gyp` on this machine — the DSH runtime ships the `node`
   binary only. Their absence is not fatal (a Node-API addon is just a shared object, so
   clang can compile it directly), but any build script must not assume them.

Also: `nodejs.org/dist/.../node-v26.4.0-headers.tar.gz` answers a HEAD request but the ~10 MB
download times out through this network, so the headers have to be fetched during a window
when the link is healthy (or taken from the Android runtime package itself).

Practical order for the next session:
1. fetch the four module sources at VS Code's pinned versions;
2. fetch node v26.4.0 headers;
3. build each with the NDK `aarch64-linux-android31-clang++ -shared -fPIC -I<node>/include/node`;
4. `node-pty` will need the Termux-style patch: bionic has no `forkpty`, so open the pty with
   `posix_openpt`/`grantpt`/`unlockpt` and fork by hand, or replace it with a pipes-based
   `/system/bin/sh` spawn (which is what the user actually asked for: a shell like Termux's).

## The embedded terminal is a real Termux environment

code-server's terminal is a real PTY (`node-pty` needs no patch — bionic has `forkpty` since
API 23), but by default it spawns `/system/bin/sh`, which is toybox and has no bash, no
coreutils worth the name and no package manager. The app therefore carries Termux's own
bootstrap in its assets and unpacks it into `files/usr`.

### It only works because targetSdk is 28

Android 10 and later refuse `execve` on anything under an app's data directory. That is why
dsh's shell had to live in `nativeLibraryDir` as a shared object. Termux itself runs bash from
`/data/data/com.termux/files/usr/bin` and gets away with it because it targets API 28, below
the cutoff. This app does the same, and it is the single decision the whole feature rests on —
verified by writing an ELF to `files/`, chmod +x, exec'ing it and getting
`rc=0 out=EXEC_FROM_DATA_OK`. `lintVitalRelease` treats targetSdk 28 as fatal, so release lint
is disabled; Play would reject the APK, which does not matter for sideloading.

### Unpacking the bootstrap

`bootstrap-aarch64.zip` (33 MB, 3774 entries) is an asset. A plain unzip produces an unusable
prefix for two reasons:

* zip stores no POSIX permission bits, so nothing comes out executable;
* zip stores no symlinks, so Termux ships `SYMLINKS.txt` listing them as `target←./path`
  (U+2190), 1213 of them.

`TermuxBootstrap.ensure()` sets the executable bit under `bin/` and `lib/` and recreates every
link with `Os.symlink`. Termux's bash still looks for
`/data/data/com.termux/files/usr/etc/bash.bashrc`, which is compiled in and cannot exist here,
so the terminal profile passes `--norc` and supplies `PREFIX`/`HOME`/`TMPDIR`/`LD_LIBRARY_PATH`
itself.

### apt works

Termux's apt has `/data/data/com.termux/files/usr/etc/apt` compiled in, but it reads
`$APT_CONFIG` first, so a generated `apt.conf` redirects everything with `Dir::Etc`,
`Dir::State`, `Dir::Cache` and `Dir::Log`. Four more settings were each found by its own
failure:

| setting | without it |
|---|---|
| `Dir::Bin::methods` | `The method driver .../lib/apt/methods/https could not be found` |
| `Acquire::https::CaInfo` | `No system certificates available` |
| `Dir::Bin::apt-key` | `Couldn't execute /data/data/com.termux/.../apt-key` |
| script shebangs rewritten | same, because those scripts start with `#!/data/data/com.termux/...` |

Binaries are relocatable (`DT_RUNPATH=$ORIGIN`); scripts are not. Anything under `bin/`,
`libexec/` and `lib/apt/methods` that starts with `#!` gets the Termux prefix rewritten — a
survey found 43 such scripts and 364 ELF binaries carrying the string, and only the scripts can
be rewritten safely.

Keys were the last puzzle: apt reported `NO_PUBKEY 5A897D96E57CF20C` although that key *is* in
the bundled keyring. Its entry in `trusted.gpg.d` is a symlink into Termux's own absolute
prefix, so it dangled while the real file sat in `share/termux-keyring`. Re-pointing those links
fixed it, and `apt-get update` now verifies the signature properly.

### dpkg cannot run, so a shim stands in

```
dpkg: error: error opening configuration directory
  '/data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d': Permission denied
```

That path is compiled in and no argument changes it — `dpkg --help` dies before parsing. The
binary is preserved as `dpkg.real` and `bin/dpkg` is a script that unpacks with `dpkg-deb`,
relocates the Termux prefix, runs the maintainer scripts it extracted with
`dpkg-deb --control`, records the package in dpkg's status file and replays the `--status-fd`
protocol apt listens for.

`apt-get install` works end to end. `apt-get remove` does not go through apt: it leaves the
files behind and reports `APT had planned for dpkg to do more than it reported back`, even
though `dpkg --remove <pkg>` and `echo "<pkg> deinstall" | dpkg --set-selections` both remove
them when called directly.

### adb and ssh are included

`packages-aarch64.zip` (26 MB) carries openssh and android-tools with their whole dependency
tree, resolved and extracted on the build machine because apt's signature could not be trusted
at that point. `adb version` reports 1.0.41 and `ssh -V` reports OpenSSH_10.6p1. Note that adb
cannot control *this* device: connecting to localhost needs an adbd that accepts on the wireless
port, and this one takes the TCP connection and never answers (ro.adb.secure=1 without an
authorised key, and authorising needs a tap on a system dialog).

### ssh into the separate Termux app

The app can also ssh into the Termux installation you have next to it, and land in *its*
environment — `$PREFIX` comes back as `/data/data/com.termux/files/usr`.

Setup, all automatic once the permission is granted:

1. `adb shell pm grant com.vscode.android com.termux.permission.RUN_COMMAND` — **required after
   every install**; without it the dispatch reports `granted=false` and nothing happens.
2. The app generates an ed25519 key with the bundled `ssh-keygen`.
3. It asks Termux, via RUN_COMMAND, to append the public key to `~/.ssh/authorized_keys` and
   start `sshd`.

That last script has two non-obvious requirements. Termux's base install has **no procps**, so
a `pgrep` guard silently prevented sshd from ever starting; and once sshd runs, it still accepts
nothing, because **Android freezes cached apps** — the listener stays in LISTEN while every
thread is stopped, so `connect()` hangs instead of being refused. That also explains why this
device's adbd takes connections and never answers. The fix is to keep the dispatched command
alive:

```sh
sshd 2>/dev/null || true
while true; do sleep 300; done
```

RUN_COMMAND keeps executing in Termux's service context, so Termux is never treated as a cached
app and sshd can accept. Verified: the connection returns `SSH_INTO_TERMUX_OK` and Termux's own
prefix.

### Files you may want

| path | what |
|---|---|
| `files/usr` | the Termux prefix (bash, coreutils, apt, openssh, adb) |
| `files/usr/etc/apt/apt.conf` | the redirection written on every start |
| `files/usr/bin/pkg-install` | download-and-relocate installer used when dpkg cannot run |
| `files/ssh/termux_ed25519` | the key used for the Termux ssh profile |
| `.local/share/code-server/User/settings.json` | the terminal profiles, regenerated on every start |

## A toolchain without proot

The terminal is a real Termux prefix, so ordinary Termux packages work directly — no container
needed. `pkg-install` (see above) downloads with apt and unpacks with `dpkg-deb`, relocating the
Termux paths, which is enough for the compilers and interpreters because Termux binaries use
relative `$ORIGIN` library paths.

One command installs the common set:

```sh
install-dev-env          # python, clang, make, git
```

Verified on device from inside the prefix:

```
python: Python 3.14.8
clang:  clang version 21.1.8
make:   GNU Make 4.4.1
```

and a compiled program actually runs:

```sh
$ clang hello.c -o hello && ./hello
HELLO_FROM_CLANG
```

Anything else is `pkg-install <name>`; `apt-get update` keeps the index current. What does *not*
work is dpkg's own `--configure` step, so postinst scripts are skipped — packages that need them
(ca-certificates does its own thing, python does not) may need a manual step.

A proot container remains useful for the cases this cannot cover: software that hard-codes
`/bin`, `/usr/lib` or a glibc-only ABI. Everything else is simpler here.

## Agent extensions: what actually installs on Android

code-server asks Open VSX for a build matching this platform, and several popular agents ship
only one platform's bundle, so the marketplace path rejects them with a misleading message:

```
The 'moonshot-ai.kimi-code' extension is not available in code-server for the Web platform.
```

Checked against Open VSX directly:

| Extension | Version | Target | Installable here |
|---|---|---|---|
| `saoudrizwan.claude-dev` (Cline) | 4.1.25 | universal | **yes — installed** |
| `RooVeterinaryInc.roo-cline` | 3.54.0 | universal | yes |
| `codeium.codeium` | 1.48.2 | universal | yes |
| `moonshot-ai.kimi-code` (Kimi Code) | 0.8.3 | darwin-arm64 | no — macOS binary inside |
| `Continue.continue` | 2.1.0 | alpine-x64 | no — wrong platform bundle |

Kimi Code is real and official (also on the Microsoft marketplace as `moonshot-ai.kimi-code`), but
its VSIX carries a macOS binary and there is no linux/Android build, so it cannot run here. The
same is true of Continue.

### Installing one that works

`--install-extension <id>` is refused for platform-locked extensions, and for universal ones the
marketplace path still goes through the same filter. Downloading the VSIX and installing from the
file works:

```sh
# fetch the universal VSIX (node's fetch works where the bundled curl's TLS does not)
node -e "fetch('https://open-vsx.org/api/saoudrizwan/claude-dev/4.1.25/file/saoudrizwan.claude-dev-4.1.25.vsix').then(r=>r.arrayBuffer()).then(b=>require('fs').writeFileSync(process.env.HOME+'/cline.vsix',Buffer.from(b)))"

cd $FILES/cs && $NATIVE/libnode.so out/node/entry.js --install-extension ~/cline.vsix --force
```

`--list-extensions` then reports `saoudrizwan.claude-dev`.

### Pointing an agent at a dsh/opencode-go endpoint

Cline and Roo Code both speak the OpenAI-compatible API, which is what dsh's provider block
exposes. In the extension's settings choose "OpenAI Compatible" and enter:

- Base URL: `https://opencode.ai/zen/go/v1`
- API key: the `OPENCODE_GO_2_API_KEY` value from the dsh profile
- Model ID: `deepseek-v4.1-flash`

There is no official VS Code extension for dsh itself -- dsh is the harness, not an editor plugin
-- so an OpenAI-compatible client like Cline is the way to drive the same models from the editor.

### Installing extensions that the marketplace refuses

`vsix-install` (in `$PREFIX/bin`) downloads a VSIX from Open VSX and installs it from the file,
which is the only route that works for anything code-server rejects:

```sh
vsix-install RooVeterinaryInc.roo-cline
# extension: RooVeterinaryInc.roo-cline 3.54.0
# downloaded 30837353 bytes
# Extension 'RooVeterinaryInc_roo-cline.vsix' was successfully installed.
```

Two details it handles. The download streams to disk instead of buffering, because a single
arrayBuffer() read was being cut off on the larger extensions (Roo Code is 30 MB). And the native
library directory comes from `files/native-lib-dir`, which the app writes at boot -- the app's own
uid cannot glob /data/app to find its own libs, so the path has to be recorded from Java.

Installed and verified this way: Cline 4.1.25, Roo Code 3.54.0.

## proot Ubuntu: the rootfs works, proot does not

Installed `proot` (5.1.107.98) from the Termux repo and unpacked the official
`ubuntu-base-26.04-base-arm64` rootfs into `files/ubuntu` (25.7 MB tarball, 18 top-level entries).

Three problems had to be solved just to get a usable tree, and each one is worth knowing:

1. **`--no-same-permissions` strips the exec bit**, so every binary lands non-executable. Extract
   with `--no-same-owner` only.
2. **Hardlinks fail**: "Cannot hard link to 'usr/bin/coreutils': Permission denied". Ubuntu's
   coreutils is a multi-call binary and every applet is a hardlink to it, so those entries become
   zero-byte files. 114 of them had to be filled by copying `usr/bin/coreutils` over each (with a
   `chmod` first, since they arrive mode 0000).
3. `lib` is a symlink to `usr/lib`, and `/lib/ld-linux-aarch64.so.1` was missing entirely; it now
   exists as a real copy of `usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1`.

Even so, proot cannot run anything from inside the rootfs, and the decisive test is that it cannot
run a plain device binary either:

```sh
proot -0 /system/bin/sh -c 'echo PROOT_BASIC_OK'
# the loader was not found or doesn't work
```

With no rootfs involved, this isolates the failure to proot itself rather than to the Ubuntu tree.
Tried `--link2symlink`, `PROOT_NO_SECCOMP=1`, an explicit `-b $U/usr/lib:$U/lib` bind, and a
`PROOT_TMP_DIR` that exists and is world-writable; none changed the outcome.

So the Ubuntu container is prepared but not runnable yet. What does work for a Linux userland is
the Termux prefix itself, which already has apt, python, clang, cmake, ninja, go, rust and java.

## Ubuntu 26.04, borrowed from Termux

The container ships in the real Termux app rather than inside this APK, because the two proot
copies are not equivalent:

```sh
# a second proot installed into this app's prefix
proot -0 /system/bin/sh -c 'echo ok'
#   the loader was not found or doesn't work      <- no rootfs involved

# the one Termux already has
proot -0 /system/bin/sh -c 'echo ok'
#   ok
```

Since the failure is in proot itself and not in the Ubuntu tree, the container is borrowed:

```sh
proot-distro install ubuntu        # inside Termux
proot-distro login ubuntu -- cat /etc/os-release
# PRETTY_NAME="Ubuntu 26.04.1 LTS"
# VERSION_ID="26.04"
# aarch64, uid 0
```

VS Code gets an `ubuntu` terminal profile that is the ssh hop into Termux followed by
`proot-distro login ubuntu`, so the editor's terminal opens straight into the container.

A rootfs also lives in this app's storage (files/ubuntu, unpacked from
ubuntu-base-26.04-base-arm64) with a launcher at `$PREFIX/bin/ubuntu`; it is kept because the
extraction notes are worth having, but it cannot run until proot does.

## What the shipped APK does out of the box

Start the app and the terminal is already a working Termux userland:

```
bash 5.3   apt (update / install / remove)   PROOT_TMP_DIR set   VSIX installer
```

The heavier toolchain is one command, because bundling it would add roughly 1.5 GB to an APK that
is already 197 MB:

```sh
install-dev-env      # python 3.14.8, clang 21.1.8, make 4.4.1, git
```

and anything else is `pkg-install <name>` — verified working for cmake 4.4.4, ninja 1.13.2,
go 1.27.3, rust 1.99.1 (rustc + cargo) and openjdk-21 (21.0.13, javac included).

### The Ubuntu profile and proot

A fourth terminal profile, `ubuntu`, ssh's into the real Termux app and runs
`proot-distro login ubuntu`, which is where Ubuntu 26.04.1 LTS actually runs on this device.

proot shipped inside this APK cannot execute anything — from the app's own process it fails with
`execve("/system/bin/sh"): Permission denied`, and the same happens from adb. Termux's copy does
not. Missing pieces were ruled out along the way (`libtalloc.so.2` was genuinely absent and is now
bundled, `PROOT_NO_SECCOMP=1`, `--link2symlink`, a writable PROOT_TMP_DIR, and an explicit /lib
bind all made no difference), so the container is borrowed rather than duplicated.

## The Extensions marketplace works

The bundle ships `extensionsGallery: null`, so the gallery had to be supplied: `GalleryPatch`
writes the Open VSX gallery into `files/cs/lib/vscode/product.json` on every start, and it has to
run *before* the server starts because the server reads that file once.

With that in place the Extensions view is a real marketplace. Verified on the device:

- searching `python` returns the full catalogue (ms-python 45M installs, debugpy 62.4M, ...)
- installing `ms-python.vscode-python-envs` from the GUI completed and reappeared under Installed
- the publisher-trust dialog appears as it should

One correction worth recording: an earlier note in this file claimed the panel was empty and the
marketplace unusable. That was wrong -- the default view only lists installed and recommended
extensions, so it looks empty until you search. Searching is what proves the gallery is live.

`vsix-install <publisher.name>` still exists for the extensions the marketplace refuses on
platform grounds (Kimi Code ships darwin-arm64 only, Continue alpine-x64 only); Cline and Roo Code
were installed that way and both run.


## Entering Ubuntu: one command, not a profile

The container is one command away in the default terminal:

```sh
ubuntu
# ssh into the real Termux app, then proot-distro login ubuntu
# PRETTY_NAME="Ubuntu 26.04.1 LTS"
```

Verified end to end from this app's prefix: the ssh hop answers `SSH_OK` and the login prints
Ubuntu 26.04.1 LTS.

It is a command rather than a terminal profile because the profile route does not work here.
`writeTerminalProfiles` writes a valid five-profile `settings.json` to
`<user-data-dir>/User/settings.json` -- and the server's own log confirms that is the directory it
uses (`Using user-data-dir /data/user/0/com.vscode.android/files/.local/share/code-server`) -- yet
the workbench ignores the file: the picker lists three auto-detected `bash` entries, the terminal
calls itself `bash` rather than `termux`, and a control setting (`workbench.startupEditor: none`)
changes nothing either. The whole user-settings file goes unread, not just the terminal keys.

A second proot inside this APK cannot execve on this device (`execve("/system/bin/sh"): Permission
denied`, from the app's own process, with libtalloc present, seccomp disabled, `--link2symlink` on
and a writable PROOT_TMP_DIR), while Termux's copy works fine. So the container lives in Termux and
`ubuntu` is the hop into it.

