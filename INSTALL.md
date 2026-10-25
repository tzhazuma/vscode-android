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
