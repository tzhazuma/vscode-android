# What is in the APK, what is downloaded, and how to finish by hand

The APK carries the container, the package manager and the workbench. It does not carry the
developer toolchain: that would add hundreds of megabytes to every download for something installed
once. The gap is closed on the first start, and anything that cannot be automated is written down
here.

## Automatic

On the first start after a fresh install the app runs `$PREFIX/bin/setup all` in the background and
writes `files/setup-first-run.log`. It is detached on purpose — the download takes minutes and the
workbench must not wait for it.

| Step | What it does |
|---|---|
| `setup shell` | zsh, oh-my-zsh, and the autosuggestions / syntax-highlighting / completions plugins, fetched as codeload tarballs because git over https does not work in this prefix |
| `setup dev` | python, clang, make, git, go, rust, java, openssh, adb, sshpass |
| `setup ssh` | generates `files/ssh/termux_ed25519` and installs its public half into Termux, so the `Termux (ssh)` and `adb` terminal profiles connect without a password |
| `setup brew` | Linuxbrew inside the Ubuntu container |
| `setup vscode` | `.vscode/tasks.json` + `launch.json` for C/C++/Rust/Go/Java/Python through the container, and the language extensions |
| `setup opencode` | the opencode CLI inside the container plus the wrapper that avoids the narrow-terminal crash |

Run any step again at any time; each one checks before acting. `setup` with no argument runs all of
them.

## Terminal profiles

`terminal.integrated.profiles.linux` **and** `.android` both carry the same set, because the
workbench picks the set from `process.platform`, which is `android` here:

| Profile | What you get |
|---|---|
| `termux` | the app's own prefix, the default |
| `android` | `/system/bin/sh`, no container |
| `termux-app-ssh` | the Termux app over ssh, key-based |
| `termux-app-ssh-ubuntu` | Termux → `proot-distro login ubuntu`, via sshpass |
| `ubuntu` | the Ubuntu container shipped in the APK |
| `adb` | a Termux shell with `adb` on PATH |

`adb` is a Termux shell rather than a direct call because `adb` lives in the Termux app's data
directory, which this app cannot execute from — different uid, execute bit cleared for others. Acting
as a different user is not something an app can do, but Termux's sshd runs it fine.

## Editing and running code

The workbench is a host process; every compiler is inside Ubuntu. `.vscode/run-in-ubuntu.sh` re-enters
the container with `/workspace` (a bind mount of `files/home`) as the working directory, and the tasks
use it:

```
C: gcc build          C++: g++ build        C/C++: clang build
C/C++: cmake build    C/C++: make           Rust: cargo run
Go: go run            Java: javac           Java: java <class>
Python: run with Ubuntu python
```

The Python extension can also use the container's interpreter directly: select
`files/usr/bin/ubuntu-python`, which is a wrapper that execs the container's `python3`.

## What has to be done by hand

**Remote SSH.** Microsoft's Remote extensions are proprietary and are not published to Open VSX, so
code-server cannot install them. `setup vscode` installs `jeanp413.open-remote-ssh` instead, which
speaks the same protocol. To reach another machine, put an entry in `~/.ssh/config` inside the home
the workbench sees (`files/home/.ssh/config`):

```
Host mac
    HostName 192.168.0.1
    User azuma
    IdentityFile ~/.ssh/id_ed25519
    StrictHostKeyChecking no
```

then copy your key to the server (`ssh-copy-id`) from a `termux` terminal, and connect with
"Remote-SSH: Connect to Host…". This has **not** been verified end to end on the device — the
extension installs, the client side is untested.

**Store access.** The app does not have all-files access, so `/sdcard` is not visible inside the
container and is deliberately not bind-mounted: proot refuses it with `can't sanitize binding
/sdcard: Permission denied` and then prepends that warning to the output of every command. Open
documents through the workbench's own pickers.

**Debuggers.** The `Run` button uses `launch.json` configurations that run a file without a debugger.
Real breakpoints through proot need a debug adapter talking to a gdb inside the container, which is
not wired up.

## Known device quirks

- `uiautomator dump` returns `ERROR: null root node returned by UiTestAutomationBridge` while the
  vivo installer dialog is up, so an install cannot be driven from a dump. Drive it from a screenshot
  instead: the checkbox is a 45x45 grey square at `y=1748` with the 身份验证 footer and `y=1809`
  without (`x=1304`), 继续安装 is RGB 39,133,255, and the dialog goes inert the moment its `pm install`
  client dies, so the install and the taps must stay in one live shell.
- Screenshots go stale for apps that draw through a SurfaceView.
- `run-as` runs in the restricted `runas_app` domain: it can execute the prefix binaries but cannot
  read `/sdcard`, so it must never be used to test storage access.
- The command centre is hidden in compact mode. Measured: `.command-center` is laid out but carries
  `visibility: hidden`, and `.command-center-center` is `display: none`, so the white strip at the top
  is the title bar background and not an input. The injected stylesheet forces it visible; without
  that, taps on the "search box" hit nothing.

### Choosing a terminal profile

Two different controls, and they are easy to confuse:

- **The `+` button** creates a terminal with the *default* profile
  (`terminal.integrated.defaultProfile.linux`, `termux`).
- **The chevron next to it** is a *terminal switcher*: on this build it lists the terminals that are
  already open (two `bash` entries if two are running), which reads as "it only makes bash". It is
  not the profile picker.
- **The profile picker** is the command palette: `Ctrl+Shift+P` → `Terminal: Select Default Profile`
  lists all six (verified on the device: termux, android, termux-app-ssh, ubuntu, adb,
  termux-app-ssh-ubuntu). `Terminal: Create New Terminal (With Profile)` starts one with a chosen
  profile without changing the default.

The first two iterations of this document claimed the chevron was broken. It was not: the settings
file had been corrupted by a bad edit, so `terminal.integrated.profiles.*` was unparseable and the
workbench fell back to auto-detected shells. After that was fixed the profile list came back, but the
chevron still shows terminals, because that is what it lists.

## The C/C++ extension

`ms-vscode.cpptools` is not on Open VSX, and its binaries cannot run on Android even when it is
installed: `file` reports `interpreter ./libc.so`, a relative interpreter path, so it needs the
directory that holds `libc.so` and a glibc userland. Neither exists on bionic.

`setup cpptools` does what works:

1. download the platform VSIX from Microsoft's gallery
   (`.../vsextensions/cpptools/<ver>/vspackage?targetPlatform=linux-arm64`) -- the gallery serves it
   **gzip-wrapped**, so the payload is not a zip until it is decompressed; `code-server
   --install-extension` fails on it with `code: 'Extract'` for exactly that reason;
2. unpack `extension/` into the extensions directory by hand;
3. copy the whole `bin/` directory into the container at `/opt/cppbin` (whole directory: the relative
   interpreter resolves against the working directory);
4. replace the four binaries with wrappers that `exec ubuntu -c 'cd /opt/cppbin && exec ./<name> "$@"'`.

Verified on the device: `bin/cpptools --version` reports `1.35.4.0` from the host side, the extension
activates (`ExtensionService#_doActivateExtension ms-vscode.cpptools, activationEvent: 'onLanguage:c'`),
and `bin/cpptools` works inside the container. **Not verified**: the language server's JSON-RPC over
stdio across the proot hop -- after opening a `.c` file no `cpptools` process stays alive, so
IntelliSense may not actually be serving. Treat the install as done and the language server as
unproven.

## Remote SSH

`setup vscode` installs `jeanp413.open-remote-ssh`, the community implementation of the Remote-SSH
protocol. Microsoft's own `ms-vscode-remote.*` extensions are proprietary, are not published to Open
VSX, and cannot be installed by code-server.

The client side works: `$PREFIX/bin/ssh` connects out. What it connects *to* needs a running sshd --
the machine used for testing had Remote Login off and answered `Connection refused` on port 22. Turn
on Remote Login on the target, put an entry in `files/home/.ssh/config`, and it should work; that
last hop has not been exercised.

## DeepSeek Harness

The Android build runs; what it cannot do is start without `--expose-internals`, because
`node-addon-require-builtin` has no Android build. Two facts make an upstream contribution plausible
and one makes it premature:

- `deepseek-ai/deepseek-harness` is the real source repository (314 MB, TypeScript monorepo), so it
  takes PRs.
- `native/system/scripts/build.ts` refuses any host that is not linux or darwin:
  `if (process.platform !== 'linux' && process.platform !== 'darwin')`. Android reports `android`.
- But the script also derives a libc of `glibc` or `musl` from `glibcVersionRuntime`, and Android is
  neither; and the addon that actually blocks startup lives in a separate repository
  (`deepseek-ai/dsh-node-addon-require-builtin`) which **has issues disabled**, leaving a PR as the
  only channel.

No PR was opened: a patch that adds `android` without a libc story and without offsets verified
against a real Android Node build would be a guess, and that repository's own README requires failing
closed rather than guessing.

### cpptools is blocked by its own licence check

Installing it is not enough, and rebuilding it is not the answer: after the extension activates, the
workbench shows

> The C/C++ extension may be used only with Microsoft Visual Studio Code

so the extension refuses to serve outside Microsoft's own builds regardless of whether its binaries
can run. That check is inside the extension and is not something a port should try to defeat; it is
also why the extension is absent from Open VSX entirely.

If C/C++ IntelliSense is wanted here, the honest route is `llvm-vs-code-extensions.vscode-clangd` --
open source, on Open VSX, and `clangd` is already installed in the Ubuntu container by `setup dev`.
That combination has not been wired up yet.

### The terminal chevron

Measured, twice, in both layouts:

| Layout | Control | Result of a tap |
|---|---|---|
| tabs enabled | chevron beside `+` | lists the open terminals (two entries both named bash) |
| tabs disabled | chevron beside `+` | creates a terminal, same as `+` |

`terminal.integrated.tabs.enabled` is now `false`, which restores the classic `1: bash` selector and
separates `+` (38 px) from the chevron (24 px, 11 px gap), but tapping the chevron still starts a
terminal rather than offering the profile list a desktop build shows.

Profiles themselves are fine and reachable: `Terminal: Select Default Profile` lists all six, and
`+` starts `termux` because `defaultProfile.linux` and `defaultProfile.android` both name it.

## Language support

| Language | Extension | Server | State |
|---|---|---|---|
| C / C++ | `llvm-vs-code-extensions.vscode-clangd` | `clangd` 21.1.8 in the container | **working** — the log shows live `textDocument/codeAction`, `semanticTokens`, `inlayHint` replies at 0 ms |
| Go | `golang.go` 0.58.0 | `gopls` v0.21.0 in the container | server verified (`gopls version`), extension installed; activation not yet observed in a test |
| Rust | `rust-lang.rust-analyzer` 0.4.3095 | `rust-analyzer` 0.3.3089 in the container, plus `rust-src` | **working** — `analysis-stats` loads the crate and the standard library (no more "can't load standard library") |
| Java | `redhat.java`, `vscjava.vscode-java-debug`, `vscjava.vscode-java-dependency` | JDK 25 in the container, via a `$PREFIX/jdk` home | **working** — the JDT server starts; the "does not point to a JDK" error is gone |
| Python | `ms-python.python` | container `python3` via `ubuntu-python` | already in place |
| Remote | `jeanp413.open-remote-ssh` | — | installed; client works, target needs a running sshd |

Every language server is a glibc binary in the container, and every extension runs on bionic, so each
one needs a host-side wrapper that re-enters the container **and rewrites host paths to `/workspace`**
— a server handed `/data/user/0/<pkg>/files/home/foo` cannot find it. `setup langs` does that for
`clangd` and `gopls` and writes the matching `clangd.path` / `go.goplsPath` settings.

Two settings in the generated document exist only because of this split:

- `clangd.path` — without it the extension reports "The clangd language server is not installed" even
  though `$PREFIX/bin/clangd` runs, because it looks for `clangd` on the host PATH;
- `go.toolsManagement.autoUpdate: false` — `go install` fetching a newer toolchain **segfaults under
  proot** ("Segmentation fault ... talloc report on 'null_context'"), so gopls is pinned to v0.21.0,
  the newest that the container's Go 1.26.8 will build with `GOTOOLCHAIN=local`.

`ms-vscode.cpptools` is kept on disk as `ms-vscode.cpptools-1.35.4-linux-arm64.disabled` for
reference; it is unusable for the licence reason below.

### Why every language needs three things

A language is only usable here when all three hold, and each failed at first:

1. **The extension** must be in `extensions.json` — copying a directory into `extensions/` is not
   enough. Eight extensions were installed by hand and none of them activated until entries were
   added to that index; the extension host simply ignored the directories.
2. **The server** is a glibc binary and lives in the container, so a host-side wrapper has to
   re-enter it.
3. **The paths** must resolve. Wrappers can rewrite argv, but a language server gets its root from
   the LSP `initialize` message, which argv rewriting cannot touch: rust-analyzer reported
   `failed to find any projects in [/data/user/0/<pkg>/files]` while the files sat in `/workspace`.
   The fix is a bind mount of the app's data directory **at its own path**
   (`-b "$F":"$F"`), after which every path the editor sends already resolves — for every server,
   with no translation.

The Java extension adds a fourth: it validates the JDK home. A directory containing only `bin/java`
is rejected with "does not point to a JDK"; `bin/javac` and a `release` file have to be there too.

`rust-src` is separate from `rustc`: without it rust-analyzer logs
`can't load standard library, try installing rust-src`, and standard-library symbols resolve to
nothing.

Remote SSH and the terminal chevron remain as described above.
