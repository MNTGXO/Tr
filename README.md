# MN Terminal (`com.mntgxo.terminal`)

A terminal emulator for Android built on a **real PTY** and a full **xterm-256color** emulator
(the Apache-2.0 `terminal-emulator` / `terminal-view` libraries from Termux, vendored in this repo),
plus an optional **full Alpine Linux** environment (apk package manager, gcc, python, node, git, ...)
that runs without root through the bundled `proot`.

## What you get

- Real pseudo-terminal sessions: `vim`, `nano`, `htop`, `tmux`, `less`, colors, mouse, resize, UTF-8, 256 colors / truecolor.
- Multiple sessions with a tab bar (long-press a tab to close). Sessions keep running in the background
  (foreground service + notification with an Exit action).
- **Android shell** session (`/system/bin/sh`) and **Linux (Alpine)** sessions.
- One-tap Alpine install: downloads the official mini root filesystem, verifies its SHA-256, unpacks it
  with a built-in safe tar reader. Menu -> "Install dev tools" runs `apk add` for bash, git, curl, vim, python3,
  nodejs, build-base and more.
- Extra keys row (ESC, TAB, CTRL, ALT, arrows with key-repeat, HOME/END/PGUP/PGDN, `- / | ~`),
  Volume-Down as Ctrl, pinch-to-zoom, text selection/copy/paste, `~/storage` and `/sdcard` access.

## Build and release

```bash
git init && git add . && git commit -m "MN Terminal"
git branch -M main
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
git tag v2.0.0 && git push origin v2.0.0   # triggers .github/workflows/release.yml
```

The workflow builds `app-debug.apk`, uploads it as a workflow artifact and attaches it to the GitHub Release
for the pushed tag. Local build: `./gradlew assembleDebug` (JDK 17, Android SDK 34, NDK 25.1).

## Using it

- Android shell: type `linux` to enter Alpine, or `linux <command>` to run a single command in it.
- Inside Linux: `apk add <package>`, `apk search <name>`, `apk upgrade`.
- If Linux crashes right after start on your device, open a "Linux session (compat mode)" (disables proot's seccomp acceleration).
- Android 12+ may kill background child processes ("phantom process killer"). If long jobs die in the
  background, see the Termux wiki for the `adb shell device_config` workaround.

## Design notes

- `targetSdk` is **28** on purpose (as in Termux): Android 10+ forbids `execve()` of files in an app's data
  directory for apps targeting 29+. `compileSdk` is 34. proot itself ships as `lib*.so` in `jniLibs/` and is executed from the
  native library directory.
- Linux is **proot-based**: no real root, no systemd, some syscall overhead. It is a full distro userland, not a VM.
- The app is not distributed through Google Play (targetSdk 28 is not accepted there).

See `THIRD_PARTY_NOTICES.md` for licenses of the bundled components.
