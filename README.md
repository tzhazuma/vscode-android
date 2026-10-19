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
