#!/usr/bin/env bash
# 交叉编译 QuickJS-NG（arm64-v8a），给 yt-dlp 当 JS 运行时用。
#
# 为什么必须有它：YouTube 的播放地址带 n-sig / PO token 挑战，要用 JS 解。
# 没有 JS 运行时时 yt-dlp 拿不到完整画质档，表现就是「只能解析出 360p」——
# 这正是 Seal / YTDLnis 那类 App 解析失败的根因。
#
# 为什么选 quickjs-ng 而不是 Node/Deno：它只有一个可执行文件、体积 6MB
# 上下、不依赖任何外部运行时，是唯一适合塞进 APK 的选择。
#
# 产物必须叫 libqjs.so：安卓只允许 nativeLibraryDir 下的文件被执行，
# 而 AGP 只把 jniLibs/*.so 解包到那里（见 app/build.gradle.kts 的 useLegacyPackaging）。
# 名字变了，所以要把完整路径显式告诉 yt-dlp（见 ytdlp_engine._base_opts）。
#
# 用法（Git Bash）：
#   "D:/Dev-env/Git/usr/bin/bash.exe" android/tools/build-quickjs.sh
set -euo pipefail

export PATH="/usr/bin:/bin:$PATH"

NDK="/d/Dev-env/AndroidSDK/ndk-r30"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
API=26
ABI=arm64-v8a
VER="0.17.0"

CMAKE="/d/Dev-env/Cpp/mingw64/bin/cmake.exe"
NINJA="/d/Dev-env/Cpp/mingw64/bin/ninja.exe"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"
TMP="$ANDROID_DIR/.build-tmp"
SRC="$TMP/quickjs-$VER"
BUILD="$TMP/qjs-build-$ABI"
OUT="$ANDROID_DIR/app/src/main/jniLibs/$ABI"
TARBALL="$TMP/qjs-ng-v$VER.tar.gz"

for f in "$CMAKE" "$NINJA" "$TOOLCHAIN/bin/llvm-strip"; do
  [ -e "$f" ] || { echo "缺少必需文件：$f" >&2; exit 1; }
done

if [ ! -d "$SRC" ]; then
  if [ ! -f "$TARBALL" ]; then
    echo "==> 下载 quickjs-ng v$VER"
    # curl 不走系统代理会超时，所以用 PowerShell 下载
    powershell.exe -NoProfile -Command \
      "\$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri \
'https://github.com/quickjs-ng/quickjs/archive/refs/tags/v$VER.tar.gz' \
-OutFile '$(cygpath -w "$TARBALL")' -UseBasicParsing"
  fi
  echo "==> 解压"
  mkdir -p "$TMP"
  tar -xf "$TARBALL" -C "$TMP"
fi

echo "==> cmake configure"
rm -rf "$BUILD"
"$CMAKE" -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$API" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_MAKE_PROGRAM="$NINJA"

echo "==> build"
"$CMAKE" --build "$BUILD" --target qjs

echo "==> strip"
"$TOOLCHAIN/bin/llvm-strip" --strip-all "$BUILD/qjs"

echo "==> install"
mkdir -p "$OUT"
cp "$BUILD/qjs" "$OUT/libqjs.so"

echo "==> done"
ls -l "$OUT/libqjs.so"
"$TOOLCHAIN/bin/llvm-readelf" -h "$OUT/libqjs.so" | grep -E "Class|Machine|Type"
"$TOOLCHAIN/bin/llvm-readelf" -l "$OUT/libqjs.so" | grep -E "LOAD" || true