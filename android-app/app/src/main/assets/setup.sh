#!/data/data/com.vscode.android/files/usr/bin/bash
# One-shot environment setup for this app.
#
# The APK ships the container and the package manager, not the developer toolchain: that would add
# hundreds of megabytes to every download for something most people install once. This script is
# what turns a fresh install into a usable one, and it is deliberately re-runnable -- every step
# checks before it acts, so running it twice costs a few seconds, not a second download.
#
#   setup              everything
#   setup shell        zsh + oh-my-zsh only
#   setup dev          python/clang/go/rust/java/git/ssh/adb
#   setup brew         Linuxbrew inside the Ubuntu container
set -uo pipefail

# This script lives in $PREFIX/bin, so the app's files directory is two levels up. One level up is
# $PREFIX itself, and using that as FILES made PREFIX=$FILES/usr point at $PREFIX/usr -- a path
# that does not exist -- so every probe in the first run reported FAIL.
FILES="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PREFIX="$FILES/usr"
export PREFIX
export LD_LIBRARY_PATH="$FILES/rt:$PREFIX/lib"
export PATH="$PREFIX/bin:/system/bin:/system/xbin"
export HOME="$FILES/home"
export TMPDIR="$FILES/tmp"
export APT_CONFIG="$PREFIX/etc/apt/apt.conf"
mkdir -p "$HOME" "$TMPDIR" 2>/dev/null

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }

# Which command proves a package is already there. Everything here is also installed by the APK's
# own bootstrap on most builds, so "already present" is the normal case, not an error.
probe_for() {
  case "$1" in
    python)        echo python3 ;;
    golang)        echo go ;;
    rust)          echo rustc ;;
    openjdk-17)    echo java ;;
    openssh)       echo ssh ;;
    android-tools) echo adb ;;
    *)             echo "$1" ;;
  esac
}

pkg_install() {
  for p in "$@"; do
    local probe; probe="$(probe_for "$p")"
    if command -v "$probe" >/dev/null 2>&1; then
      echo "  ok   $p (already present)"
      continue
    fi
    if "$PREFIX/bin/pkg-install" "$p" >/dev/null 2>&1 && command -v "$probe" >/dev/null 2>&1; then
      echo "  ok   $p"
    else
      echo "  FAIL $p"
    fi
  done
}

setup_shell() {
  say "shell (zsh + oh-my-zsh)"
  pkg_install zsh git curl
  # Fetch a tarball rather than clone: git over https does not work in this prefix (the CA path
  # libcurl was built with points at another app's directory), so `git clone` fails here while
  # curl with our own bundle succeeds.
  local ca="$PREFIX/etc/tls/cert.pem"
  fetch_tree() {
    local url="$1" dest="$2" tmp="$TMPDIR/fetch.tar.gz"
    rm -rf "$tmp"
    if curl -fsSL --cacert "$ca" "$url" -o "$tmp" 2>/dev/null && tar -xzf "$tmp" -C "$TMPDIR" 2>/dev/null; then
      rm -rf "$dest"
      mv "$TMPDIR/${url##*/}"* "$dest" 2>/dev/null || mv "$TMPDIR/"*ohmyzsh* "$dest" 2>/dev/null
      rm -f "$tmp"
      return 0
    fi
    rm -f "$tmp"
    return 1
  }
  if [ ! -d "$HOME/.oh-my-zsh" ]; then
    if fetch_tree "https://codeload.github.com/ohmyzsh/ohmyzsh/tar.gz/refs/heads/master" "$HOME/.oh-my-zsh"; then
      echo "  ok   oh-my-zsh"
    else
      echo "  FAIL oh-my-zsh (no network or codeload unreachable)"
    fi
  else
    echo "  ok   oh-my-zsh (present)"
  fi
  local custom="$HOME/.oh-my-zsh/custom/plugins"
  mkdir -p "$custom"
  for repo in \
    "zsh-users/zsh-autosuggestions" \
    "zsh-users/zsh-syntax-highlighting" \
    "zsh-users/zsh-completions"; do
    local name="${repo##*/}"
    [ -d "$custom/$name" ] || fetch_tree "https://codeload.github.com/$repo/tar.gz/refs/heads/master" "$custom/$name"
  done
  if [ ! -f "$HOME/.zshrc" ]; then
    cat > "$HOME/.zshrc" <<'ZRC'
export ZSH="$HOME/.oh-my-zsh"
ZSH_THEME="robbyrussell"
plugins=(git zsh-autosuggestions zsh-syntax-highlighting zsh-completions)
source "$ZSH/oh-my-zsh.sh"
export PATH="$PREFIX/bin:$PATH"
ZRC
    echo "  ok   .zshrc"
  else
    echo "  ok   .zshrc (kept)"
  fi
}

setup_dev() {
  say "toolchain"
  pkg_install python clang make git golang rust openjdk-17 openssh android-tools sshpass
  # openjdk unpacks under lib/jvm and relies on a post-install script this installer does not run
  for b in java javac jar; do
    [ -f "$PREFIX/lib/jvm/java-17-openjdk/bin/$b" ] \
      && ln -sf "$PREFIX/lib/jvm/java-17-openjdk/bin/$b" "$PREFIX/bin/$b"
  done
  [ -f "$PREFIX/etc/ssh/ssh_host_ed25519_key" ] || "$PREFIX/bin/ssh-keygen" -A >/dev/null 2>&1
  say "versions"
  echo "  python  $("$PREFIX/bin/python3" --version 2>&1)"
  echo "  clang   $("$PREFIX/bin/clang" --version 2>&1 | head -1)"
  echo "  go      $("$PREFIX/bin/go" version 2>&1)"
  echo "  rust    $("$PREFIX/bin/rustc" --version 2>&1)"
  echo "  java    $("$PREFIX/bin/java" -version 2>&1 | head -1)"
  echo "  ssh     $("$PREFIX/bin/ssh" -V 2>&1 | head -1)"
  echo "  adb     $("$PREFIX/bin/adb" version 2>&1 | head -1)"
}

setup_brew() {
  say "Linuxbrew (inside the Ubuntu container)"
  local ubuntu="$FILES/usr/bin/ubuntu"
  [ -x "$ubuntu" ] || { echo "  no container launcher"; return 1; }
  "$ubuntu" -c '
    if command -v brew >/dev/null 2>&1; then echo "  ok   brew (present)"; exit 0; fi
    export NONINTERACTIVE=1
    if /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)" >/dev/null 2>&1; then
      eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)" 2>/dev/null
      echo "  ok   brew $(brew --version 2>/dev/null | head -1)"
    else
      echo "  FAIL brew installer refused (expected on some Android kernels)"
    fi
  '
}


# opencode has no Android build: npm declares os darwin/linux/win32, and its linux-arm64
# binary is glibc and cannot run on bionic. It works inside the container, so install it
# there and leave a wrapper on the host side. The wrapper exists for a second reason:
# opencode 1.19.4's TUI (Bun 1.4.4 + OpenTUI) segfaults whenever the pty is narrower than
# 38 columns AND shorter than 26 rows, and VS Code's "Open opencode" opens a Beside column
# that is ~29 columns wide. The compact TUI survives that, so narrow terminals get --mini.
setup_opencode() {
  say "opencode (inside the Ubuntu container)"
  local ubuntu="$FILES/usr/bin/ubuntu"
  [ -x "$ubuntu" ] || { echo "  no container launcher"; return 1; }
  "$ubuntu" -c '
    if [ ! -x /opt/opencode/opencode ]; then
      mkdir -p /opt/opencode
      url=https://github.com/sst/opencode/releases/latest/download/opencode-linux-arm64.zip
      if curl -fsSL "$url" -o /tmp/oc.zip 2>/dev/null; then
        (command -v unzip >/dev/null && unzip -oq /tmp/oc.zip -d /opt/opencode) || \
          (command -v tar >/dev/null && tar -xf /tmp/oc.zip -C /opt/opencode) || true
        rm -f /tmp/oc.zip
      fi
    fi
    chmod +x /opt/opencode/opencode 2>/dev/null
    ln -sf /opt/opencode/opencode /usr/local/bin/opencode 2>/dev/null
    /usr/local/bin/opencode --version 2>/dev/null || echo "  opencode not installed"
  '
  for dir in "$PREFIX/bin" "$FILES/bin"; do
    mkdir -p "$dir"
    cat > "$dir/opencode" <<'WRAP'
#!/system/bin/sh
F="$(cd "$(dirname "$0")/.." && pwd)"
exec "$F/usr/bin/ubuntu" -c '
cols=$(stty size 2>/dev/null | cut -d" " -f2)
mode=tui
case "${1:-}" in
  completion|acp|mcp|attach|run|debug|providers|auth|agent|upgrade|uninstall|serve|web|models|stats|export|import|github|pr|session|plugin|db) mode=cli ;;
esac
for a in "$@"; do case "$a" in --version|-v|--help|-h) mode=cli ;; esac; done
case "$cols" in ""|*[!0-9]*) cols=80 ;; esac
if [ "$mode" = tui ] && [ "$cols" -lt 38 ]; then set -- --mini "$@"; fi
exec /usr/local/bin/opencode "$@"
'
WRAP
    chmod 755 "$dir/opencode"
  done
  echo "  ok   wrapper at $PREFIX/bin/opencode and $FILES/bin/opencode"
}


# The workbench itself is a host process, but every compiler lives inside the container. A task
# that runs `gcc` directly would look for it on bionic and fail, so each task re-enters Ubuntu with
# `ubuntu -c` and works in /workspace (the bind mount of $FILES/home). That makes the Run/Build
# commands use the same toolchain as a container shell.
setup_vscode() {
  say "editor tasks and language extensions"
  local home="$FILES/home"
  local ub="$PREFIX/bin/ubuntu"
  mkdir -p "$home/.vscode"

  # A tiny runner keeps the JSON readable and gives every language the same behaviour.
  cat > "$home/.vscode/run-in-ubuntu.sh" <<'RUN'
#!/system/bin/sh
# Run a command inside the Ubuntu container with /workspace as the working directory.
exec "$(dirname "$0")/../../usr/bin/ubuntu" -c 'cd /workspace && "$@"' sh "$@"
RUN
  chmod 755 "$home/.vscode/run-in-ubuntu.sh"

  cat > "$home/.vscode/tasks.json" <<'TASKS'
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "C: gcc build",
      "type": "shell",
      "command": "${workspaceFolder}/.vscode/run-in-ubuntu.sh",
      "args": ["gcc", "-O2", "-g", "-o", "${fileBasenameNoExtension}", "${fileBasename}", "-lm"],
      "group": { "kind": "build", "isDefault": true },
      "problemMatcher": ["$gcc"]
    },
    {
      "label": "C++: g++ build",
      "type": "shell",
      "command": "${workspaceFolder}/.vscode/run-in-ubuntu.sh",
      "args": ["g++", "-std=c++20", "-O2", "-g", "-o", "${fileBasenameNoExtension}", "${fileBasename}"],
      "group": "build",
      "problemMatcher": ["$gcc"]
    },
    {
      "label": "C/C++: clang build",
      "type": "shell",
      "command": "${workspaceFolder}/.vscode/run-in-ubuntu.sh",
      "args": ["clang", "-O2", "-g", "-o", "${fileBasenameNoExtension}", "${fileBasename}"],
      "group": "build",
      "problemMatcher": ["$gcc"]
    },
    {
      "label": "C/C++: cmake build",
      "type": "shell",
      "command": "${workspaceFolder}/.vscode/run-in-ubuntu.sh",
      "args": ["sh", "-c", "cmake -S . -B build -G Ninja && cmake --build build"],
      "group": "build",
      "problemMatcher": ["$gcc"]
    },
    {
      "label": "C/C++: make",
      "type": "shell",
      "command": "${workspaceFolder}/.vscode/run-in-ubuntu.sh",
      "args": ["make"],
      "group": "build",
      "problemMatcher": ["$gcc"]
    },
    {
      "label": "Rust: cargo run",
      "type": "shell",
      "command": "${workspaceFolder}/.vscode/run-in-ubuntu.sh",
      "args": ["cargo", "run", "--release"],
      "group": { "kind": "test", "isDefault": true },
      "problemMatcher": ["$rustc"]
    },
    {
      "label": "Go: go run",
      "type": "shell",
      "command": "${workspaceFolder}/.vscode/run-in-ubuntu.sh",
      "args": ["go", "run", "."],
      "group": { "kind": "test", "isDefault": true },
      "problemMatcher": ["$go"]
    },
    {
      "label": "Java: javac",
      "type": "shell",
      "command": "${workspaceFolder}/.vscode/run-in-ubuntu.sh",
      "args": ["javac", "-d", "out", "${fileBasename}"],
      "group": "build",
      "problemMatcher": ["$javac"]
    },
    {
      "label": "Java: java <current class>",
      "type": "shell",
      "command": "${workspaceFolder}/.vscode/run-in-ubuntu.sh",
      "args": ["sh", "-c", "java -cp out \"${fileBasenameNoExtension}\""],
      "group": "test",
      "problemMatcher": []
    },
    {
      "label": "Python: run with Ubuntu python",
      "type": "shell",
      "command": "${workspaceFolder}/.vscode/run-in-ubuntu.sh",
      "args": ["python3", "${fileBasename}"],
      "group": { "kind": "test", "isDefault": true },
      "problemMatcher": []
    }
  ]
}
TASKS

  # The Run (play) button needs a launch configuration, not a task. Debugging through proot is not
  # reliable, but running a file is, so these configurations are ready-use "run without debugging".
  cat > "$home/.vscode/launch.json" <<'LAUNCH'
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "Run in Ubuntu (no debugger)",
      "type": "node",
      "request": "launch",
      "program": "${workspaceFolder}/.vscode/run-in-ubuntu.sh",
      "args": ["sh", "-c", "\"$1\"", "sh", "${fileBasename}"],
      "cwd": "${workspaceFolder}",
      "console": "integratedTerminal",
      "skipFiles": ["<node_internals>/**"]
    }
  ]
}
LAUNCH
  echo "  ok   .vscode/{tasks,launch}.json and run-in-ubuntu.sh"

  # Language extensions. Best effort: Open VSX does not carry an arm64 build for everything, and the
  # workbench works without any of them.
  local vsix="$PREFIX/bin/vsix-install"
  if [ -f "$vsix" ]; then
    for ext in ms-vscode.cpptools ms-python.python golang.go rust-lang.rust-analyzer \
               vscjava.vscode-java-pack vadimcn.vscode-lldb; do
      if "$vsix" "$ext" >/dev/null 2>&1; then echo "  ok   $ext"; else echo "  skip $ext (no build for this platform)"; fi
    done
  else
    echo "  skip extensions (vsix-install not present)"
  fi
}

case "${1:-all}" in
  shell) setup_shell ;;
  dev)   setup_dev ;;
  brew)  setup_brew ;;
  opencode) setup_opencode ;;
  vscode) setup_vscode ;;
  all)   setup_shell; setup_dev; setup_brew; setup_vscode; setup_opencode ;;
  *) echo "usage: setup [all|shell|dev|brew|vscode|opencode]" >&2; exit 2 ;;
esac
say "done"
