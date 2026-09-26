#!/usr/bin/env bash
# 交叉编译 Android 版 ffmpeg（arm64-v8a），供 yt-dlp 做 DASH 音视频合流。
#
# 为什么自己编：此前用的是第三方预编译的 libffmpeg.so，来源不可追溯。
# 这个二进制带着完整的文件读写和网络能力，会随 APK 装进用户手机，
# 来源不明的二进制不该进最终产物 —— 所以改成用本机 NDK 自编。
#
# 为什么只编 ffmpeg 一个程序、不要 ffprobe / ffplay：
# 静态链接下每多一个程序就多一整份 libav* 代码，体积近乎翻倍。
# 而 yt-dlp 只有在找不到 ffprobe 时打一条警告，功能不受影响
# （阶段 1c 实测：原生库目录里只有 libffmpeg.so，B站 DASH 合流正常）。
#
# 许可证：--disable-gpl --disable-nonfree，产物为 LGPL，可随闭源 App 分发。
#
# 用法（必须在 Git Bash 里跑，ffmpeg 的 configure 是 POSIX sh 脚本）：
#   "D:/Dev-env/Git/usr/bin/bash.exe" android/tools/build-ffmpeg.sh
set -euo pipefail

# 非登录模式下 bash 不会加载 /etc/profile，PATH 里可能没有 coreutils
# （表现为 dirname: command not found），所以先自己补上。
export PATH="/usr/bin:/bin:$PATH"

NDK="/d/Dev-env/AndroidSDK/ndk-r30"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
API=26
ABI=arm64-v8a

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"
SRC="$ANDROID_DIR/.build-tmp/ffmpeg-9.0.2"
OUT="$ANDROID_DIR/app/src/main/jniLibs/$ABI"

# NDK 的 make 不在 PATH 上，且必须是它 —— 系统里没有别的 make
export PATH="$TOOLCHAIN/bin:$NDK/prebuilt/windows-x86_64/bin:$PATH"
# 路径一律用 POSIX 形式（/d/...）。本脚本里所有路径都由 pwd / BASH_SOURCE 推导，
# 天然是 POSIX 形式；MSYS 会在把这些参数交给原生程序（clang、make）时自动转换。
# 千万不要设 MSYS_NO_PATHCONV=1 来「避免改写」—— 那会让原生 clang 收到
# 未转换的 /d/... 路径，连测试程序都链接不出来（表现为 "C compiler test failed"）。

CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"

for f in "$SRC/configure" "$CC"; do
  [ -e "$f" ] || { echo "缺少必需文件：$f" >&2; exit 1; }
done

# 刻意做「源码树内构建」，不用 out-of-tree：
# out-of-tree 时生成的 Makefile 里会写 include /d/.../ffmpeg-9.0.2/Makefile，
# 而 NDK 的 make.exe 是原生 Windows 程序 —— MSYS 的路径转换只作用于**进程参数**，
# 读文件内容时不会转换，于是 make 把 /d/... 当成相对路径，直接报
# "Makefile:1: /d/.../Makefile: No such file or directory" 而整个构建失败。
# 源码树内构建时 configure 会把 source_path 置为 "."，产出的引用全是相对路径，
# 原生 make 就能正常解析。
cd "$SRC"
if [ -f ffbuild/config.mak ]; then
  echo "==> 清理上一次的构建产物"
  make distclean >/dev/null 2>&1 || rm -rf ffbuild/config.mak ffbuild/config.sh config.h config_components.h
fi

echo "==> configure"
"./configure" \
  --prefix="$ANDROID_DIR/.build-tmp/ffmpeg-out-$ABI" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$TOOLCHAIN/bin/llvm-ar" \
  --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
  --nm="$TOOLCHAIN/bin/llvm-nm" \
  --strip="$TOOLCHAIN/bin/llvm-strip" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --enable-static --disable-shared \
  --disable-programs --enable-ffmpeg \
  --disable-doc --disable-htmlpages --disable-manpages --disable-podpages --disable-txtpages \
  --disable-avdevice \
  --disable-gpl --disable-nonfree \
  --disable-autodetect \
  --disable-vulkan --disable-v4l2-m2m \
  --disable-debug \
  --enable-small \
  --enable-runtime-cpudetect \
  --extra-cflags="-fPIE" \
  --extra-ldflags="-pie -Wl,-z,max-page-size=16384 -static-libstdc++"

echo "==> make"
# 并行度不用拉满：Windows 上 make 调 sh 有命令行长度上限，
# 线程给太多反而更容易撞上 "command line too long"
JOBS="$(nproc 2>/dev/null || echo 4)"
[ "$JOBS" -gt 8 ] && JOBS=8
make -j"$JOBS"

echo "==> strip"
"$TOOLCHAIN/bin/llvm-strip" --strip-all ffmpeg

echo "==> install"
mkdir -p "$OUT"
# 装成 .so 后缀是安卓的规矩：只有 nativeLibraryDir 下的文件才允许被执行，
# 而 AGP 只把 jniLibs/*.so 解包到那里（见 app/build.gradle.kts 的 useLegacyPackaging）。
cp ffmpeg "$OUT/libffmpeg.so"

echo "==> done"
ls -l "$OUT/libffmpeg.so"
"$TOOLCHAIN/bin/llvm-readelf" -h "$OUT/libffmpeg.so" | grep -E "Class|Machine|Type"
"$TOOLCHAIN/bin/llvm-readelf" -l "$OUT/libffmpeg.so" | grep -E "LOAD" || true
