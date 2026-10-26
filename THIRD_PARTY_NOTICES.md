# Third-party components

## terminal-emulator and terminal-view (directories `terminal-emulator/`, `terminal-view/`)
Derived from the Termux project (https://github.com/termux/termux-app, tag v0.118.0), which in turn is based on
"Terminal Emulator for Android" by Jack Palevich (https://github.com/jackpal/Android-Terminal-Emulator).
Licensed under the **Apache License 2.0** (`LICENSES/Apache-2.0.txt`). Local modification: in
`terminal-emulator/src/main/jni/termux.c` the `ReleaseStringUTFChars` call for `cwd` now uses the correct jstring.
The Termux *application* itself (GPLv3) is not included.

## proot (binaries in `app/src/main/jniLibs/*/libproot*.so`)
PRoot, licensed under **GPL-2.0-or-later** (`LICENSES/GPL-2.0-proot.txt`), statically linked with libtalloc (LGPL-3.0-or-later).
Prebuilt binaries come from https://github.com/green-green-avk/build-proot-android (packages/ directory, built 2023-06).
Corresponding source: https://github.com/green-green-avk/proot and https://github.com/green-green-avk/build-proot-android .
proot is executed as a separate process; it is not linked into the app.

## Alpine Linux
The root filesystem is downloaded at runtime from https://dl-cdn.alpinelinux.org and is subject to the licenses of its packages.
