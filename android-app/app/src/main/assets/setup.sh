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
    # `jeanp413.open-remote-ssh` rather than `ms-vscode-remote.remote-ssh`: Microsoft's Remote
    # extensions are proprietary and are not published to Open VSX, so code-server cannot install
    # them at all. This is the community implementation of the same protocol.
    for ext in ms-vscode.cpptools ms-python.python golang.go rust-lang.rust-analyzer \
               vscjava.vscode-java-pack vadimcn.vscode-lldb jeanp413.open-remote-ssh; do
      if "$vsix" "$ext" >/dev/null 2>&1; then echo "  ok   $ext"; else echo "  skip $ext (no build for this platform)"; fi
    done
  else
    echo "  skip extensions (vsix-install not present)"
  fi
}


# A host key the app owns, and its public half installed into Termux, so `Termux (ssh)` and the adb
# profile connect without a password. The app cannot write Termux's home directly (different uid),
# so the key is installed over the one connection that still needs the password.
setup_ssh() {
  say "ssh key for the Termux backend"
  local key="$FILES/ssh/termux_ed25519"
  mkdir -p "$FILES/ssh"
  [ -f "$key" ] || "$PREFIX/bin/ssh-keygen" -t ed25519 -N '' -f "$key" -C vscode-android >/dev/null 2>&1
  chmod 600 "$key" 2>/dev/null
  local pub; pub="$(cat "$key.pub" 2>/dev/null)"
  [ -n "$pub" ] || { echo "  FAIL could not create a key"; return 1; }
  echo "  ok   key at $key"

  # Termux's home, not $HOME: sshd reads /data/data/com.termux/files/home/.ssh.
  if "$PREFIX/bin/ssh" -p 8022 -i "$key" -o StrictHostKeyChecking=no \
       -o UserKnownHostsFile=/dev/null -o BatchMode=yes -o LogLevel=ERROR \
       -o ConnectTimeout=5 u0_a238@127.0.0.1 true 2>/dev/null; then
    echo "  ok   key already accepted by Termux"
    return 0
  fi
  if [ -x "$PREFIX/bin/sshpass" ]; then
    if "$PREFIX/bin/sshpass" -p 20030120 "$PREFIX/bin/ssh" -p 8022 \
         -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
         -o ConnectTimeout=8 u0_a238@127.0.0.1 \
         "H=/data/data/com.termux/files/home; mkdir -p \$H/.ssh; chmod 700 \$H/.ssh; \
          grep -qxF '$pub' \$H/.ssh/authorized_keys 2>/dev/null || echo '$pub' >> \$H/.ssh/authorized_keys; \
          chmod 600 \$H/.ssh/authorized_keys; echo INSTALLED" >/dev/null 2>&1; then
      echo "  ok   public key installed into Termux"
    else
      echo "  FAIL could not install the key (is Termux sshd on 8022 running?)"
    fi
  else
    echo "  skip key install (sshpass absent)"
  fi
  if "$PREFIX/bin/ssh" -p 8022 -i "$key" -o StrictHostKeyChecking=no \
       -o UserKnownHostsFile=/dev/null -o BatchMode=yes -o LogLevel=ERROR \
       -o ConnectTimeout=5 u0_a238@127.0.0.1 "adb version" >/dev/null 2>&1; then
    echo "  ok   passwordless ssh works, and adb is reachable through it"
  else
    echo "  note key not accepted yet; the profile still falls back to a password prompt"
  fi
}


# The C/C++ extension is not on Open VSX, and its native binaries cannot run on bionic at all, so it
# takes three steps: fetch the platform VSIX from Microsoft's marketplace, put the binaries where the
# container can run them, and replace the host-side binaries with wrappers that re-enter the container.
setup_cpptools() {
  say "C/C++ extension (cpptools)"
  local ext="$FILES/.local/share/code-server/extensions"
  local version="${1:-1.35.4}"
  local work="$FILES/tmp"

  if [ -d "$ext/ms-vscode.cpptools-${version}-linux-arm64" ]; then
    echo "  ok   already installed ($version)"
  else
    local url="https://marketplace.visualstudio.com/_apis/public/gallery/publishers/ms-vscode/vsextensions/cpptools/${version}/vspackage?targetPlatform=linux-arm64"
    echo "  ..   downloading $version (about 115 MB)"
    if ! curl -fsSL --cacert "$PREFIX/etc/tls/cert.pem" "$url" -o "$work/cpp.gz" 2>/dev/null; then
      echo "  FAIL could not download the VSIX"
      return 1
    fi
    # The gallery serves the VSIX gzip-wrapped, so it is not a zip until it is decompressed.
    gunzip -c "$work/cpp.gz" > "$work/cpp.zip" 2>/dev/null || cp "$work/cpp.gz" "$work/cpp.zip"
    rm -rf "$work/cppx"; mkdir -p "$work/cppx"
    "$PREFIX/bin/python3" -c "
import zipfile,sys
zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])
" "$work/cpp.zip" "$work/cppx" || { echo "  FAIL could not extract"; return 1; }
    mkdir -p "$ext"
    cp -a "$work/cppx/extension" "$ext/ms-vscode.cpptools-${version}-linux-arm64"
    rm -f "$work/cpp.gz" "$work/cpp.zip"; rm -rf "$work/cppx"
    echo "  ok   installed $version"
  fi

  local bin="$ext/ms-vscode.cpptools-${version}-linux-arm64/bin"
  # The ELF interpreter is the relative path "./libc.so", so the whole directory has to move
  # together and it only runs from that directory.
  mkdir -p "$FILES/home/.cppbin"
  cp -a "$bin/." "$FILES/home/.cppbin/" 2>/dev/null
  chmod -R 755 "$FILES/home/.cppbin" 2>/dev/null
  "$PREFIX/bin/ubuntu" -c 'mkdir -p /opt/cppbin && cp -a /workspace/.cppbin/. /opt/cppbin/ && chmod -R 755 /opt/cppbin' >/dev/null 2>&1 \
    && echo "  ok   binaries are in the container at /opt/cppbin" \
    || echo "  FAIL could not copy the binaries into the container"

  # Wrappers written with printf, not a heredoc: `sh` needs a writable /tmp for heredocs and this
  # process runs under run-as, which has none.
  for b in cpptools cpptools-srv cpptools-srv2 cpptools-wordexp; do
    [ -f "$FILES/home/.cppbin/$b" ] || continue
    printf '%s\n' \
      '#!/system/bin/sh' \
      "# cpptools is a glibc build whose interpreter is \"./libc.so\"; it cannot run on bionic." \
      "# It runs inside the container, from the directory that holds libc.so." \
      "exec $PREFIX/bin/ubuntu -c 'cd /opt/cppbin && exec ./$b \"\$@\"' sh \"\$@\"" \
      > "$bin/$b"
    chmod 755 "$bin/$b"
  done
  echo "  ok   host-side wrappers installed"
  "$bin/cpptools" --version 2>/dev/null | head -1
}


# Language servers live in the container (glibc); the extensions that spawn them run on the host
# (bionic). Every one therefore needs a wrapper that re-enters the container and rewrites host paths
# to the /workspace bind mount.
setup_langs() {
  say "language extensions and servers"
  local ext="$FILES/.local/share/code-server/extensions"
  mkdir -p "$ext"

  fetch_ext() {
    local id="$1"; local pub="${id%%.*}"; local name="${id#*.}"
    [ -d "$ext/$pub.$name-"* ] && { echo "  ok   $id (present)"; return 0; }
    "$PREFIX/bin/python3" - "$id" "$pub" "$name" "$ext" <<'PYEXT'
import json, os, shutil, sys, urllib.request, zipfile
eid, pub, name, ext = sys.argv[1:5]
F = "/data/user/0/com.vscode.android/files"
hdr = {"User-Agent": "curl/8"}
try:
    meta = json.load(urllib.request.urlopen(urllib.request.Request(
        "https://open-vsx.org/api/%s/%s" % (pub, name), headers=hdr), timeout=60))
    ver = meta["version"]
    url = "https://open-vsx.org/api/%s/%s/%s/file/%s.%s-%s.vsix" % (pub, name, ver, pub, name, ver)
    vsix = F + "/tmp/" + name + ".vsix"
    with urllib.request.urlopen(urllib.request.Request(url, headers=hdr), timeout=300) as r, open(vsix, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    d = F + "/tmp/" + name + "_x"
    if os.path.isdir(d): shutil.rmtree(d)
    os.makedirs(d); zipfile.ZipFile(vsix).extractall(d)
    pkg = json.load(open(os.path.join(d, "extension", "package.json")))
    target = os.path.join(ext, "%s.%s-%s" % (pkg["publisher"], pkg["name"], pkg["version"]))
    if os.path.isdir(target): shutil.rmtree(target)
    shutil.copytree(os.path.join(d, "extension"), target)
    os.remove(vsix); shutil.rmtree(d)
    print("  ok   %s %s" % (eid, ver))
except Exception as e:
    print("  FAIL %s (%s)" % (eid, str(e)[:50]))
PYEXT
  }

  for e in llvm-vs-code-extensions.vscode-clangd golang.go rust-lang.rust-analyzer \
           redhat.java vscjava.vscode-java-debug jeanp413.open-remote-ssh; do
    fetch_ext "$e"
  done

  # clangd and gopls, both inside the container.
  "$PREFIX/bin/ubuntu" -c 'export DEBIAN_FRONTEND=noninteractive; command -v clangd >/dev/null || apt-get install -y clangd >/dev/null 2>&1; command -v clangd' >/dev/null 2>&1 \
    && echo "  ok   clangd in the container" || echo "  FAIL clangd"
  "$PREFIX/bin/ubuntu" -c 'export HOME=/root GOPROXY=https://goproxy.cn,direct GOTOOLCHAIN=local; [ -x /root/go/bin/gopls ] || /usr/bin/go install golang.org/x/tools/gopls@v0.21.0 >/dev/null 2>&1; [ -x /root/go/bin/gopls ]' >/dev/null 2>&1 \
    && echo "  ok   gopls in the container" || echo "  FAIL gopls"

  for pair in "clangd:/usr/bin/clangd" "gopls:/root/go/bin/gopls"; do
    local n="${pair%%:*}"; local b="${pair#*:}"
    printf '%s\n' \
      '#!/system/bin/sh' \
      "# $n is a glibc binary in the container; this re-enters it and maps the workspace path." \
      "F=$FILES" \
      'H="$F/home"; args=""' \
      'for a in "$@"; do case "$a" in "$H"*) a="/workspace${a#$H}" ;; esac; args="$args $a"; done' \
      "exec \"\$F/usr/bin/ubuntu\" -c 'export HOME=/root PATH=/root/go/bin:/usr/local/bin:/usr/bin:/bin; exec $b \"\$@\"' sh \$args" \
      > "$PREFIX/bin/$n"
    chmod 755 "$PREFIX/bin/$n"
  done
  echo "  ok   wrappers at $PREFIX/bin/{clangd,gopls}"
  "$PREFIX/bin/clangd" --version 2>/dev/null | head -1
  "$PREFIX/bin/gopls" version 2>/dev/null | head -1
}

case "${1:-all}" in
  shell) setup_shell ;;
  dev)   setup_dev ;;
  brew)  setup_brew ;;
  opencode) setup_opencode ;;
  vscode) setup_vscode ;;
  ssh)    setup_ssh ;;
  cpptools) setup_cpptools ;;
  langs)  setup_langs ;;
  all)   setup_shell; setup_dev; setup_ssh; setup_brew; setup_vscode; setup_langs; setup_opencode ;;
  *) echo "usage: setup [all|shell|dev|ssh|brew|vscode|cpptools|langs|opencode]" >&2; exit 2 ;;
esac
say "done"
