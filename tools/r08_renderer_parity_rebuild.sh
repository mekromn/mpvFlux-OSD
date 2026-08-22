#!/bin/bash -e
set -Eeuo pipefail

TRACE_LOG=/tmp/r08-renderer-parity-native-build.log
rm -f "$TRACE_LOG"
: > "$TRACE_LOG"
exec > >(tee -a "$TRACE_LOG") 2>&1

R08_PHASE=bootstrap
r08_phase() {
    R08_PHASE="$1"
    printf '=== R08 phase: %s ===\n' "$R08_PHASE"
}
trap 'rc=$?; cmd=$BASH_COMMAND; line=$LINENO; trap - ERR; printf "R08_FAIL phase=%s rc=%d line=%d command=%q\n" "$R08_PHASE" "$rc" "$line" "$cmd"; exit "$rc"' ERR

ROOT_DIR="${GITHUB_WORKSPACE:-$(pwd)}"
HARNESS_DIR="$ROOT_DIR/native/mpv-android"
BUILDSCRIPTS="$HARNESS_DIR/buildscripts"

# This is the pre-PR1 Secozzi harness base from 2026-02-21. It already carries
# the exact R07 NDK r29 toolchain and predates the later force_mpegts/fontconfig/
# shared-libxml2 changes that polluted the first R08 native rebuilds.
HARNESS_SHA=18e41158e1ad24c1819598be15f51c898397e04f
MPV_BASE_SHA=d54bad5636924ab3f39cb6e397b94b6aa8a7c433
MPV_PARAM_HELPER_SHA=91ceffce42534a45705617036b6b2a392a32fc57
MPV_PARAM_SHA=0d655fe66590009e1d77a17581257d677286531a
FFMPEG_SHA=5ba2525c7affc29cbd99e6266946b382d3fffe8b
FFMPEG_DESCRIBE=N-122998-g5ba2525c7a
LIBPLACEBO_SHA=c93aa134ab62365ce1177efff99b8e1e66a818e7
LIBPLACEBO_TAG=v7.360.0
LIBPLACEBO_DESCRIBE=v7.360.0-3-gc93aa134
R07_NDK_TAG=r29
R07_NDK_REV=29.0.14206865
R07_NDK_BUILD=14206865

r08_phase harness_checkout
rm -rf "$HARNESS_DIR"
git clone https://github.com/Secozzi/mpv-android.git "$HARNESS_DIR"
git -C "$HARNESS_DIR" checkout "$HARNESS_SHA"
cd "$BUILDSCRIPTS"
mkdir -p deps

grep -Fx 'v_ndk=r29' include/depinfo.sh
grep -Fx 'v_ndk_n=29.0.14206865' include/depinfo.sh

# Keep libxml2 out of the actual native dependency build. The accepted R07 APK
# contains no libxml2.so, its FFmpeg configuration does not enable libxml2, and
# neither libmpv.so nor libavformat.so has DT_NEEDED libxml2.so.
r08_phase dependency_topology
python3 - <<'PY'
from pathlib import Path
p = Path('include/depinfo.sh')
s = p.read_text()
old = 'dep_ffmpeg=(mbedtls dav1d libxml2)'
assert s.count(old) == 1, s.count(old)
s = s.replace(old, 'dep_ffmpeg=(mbedtls dav1d)', 1)
old = 'dep_libplacebo=()'
assert s.count(old) == 1, s.count(old)
s = s.replace(old, 'dep_shaderc=()\ndep_libplacebo=(shaderc)', 1)
p.write_text(s)
PY

# Pre-create the exact FFmpeg tree so the harness cannot substitute another tag
# or floating HEAD. FFmpeg's version generator requires the full commit/tag
# graph to reproduce the accepted R07 N-122998-g5ba2525c7a identity; shallow
# clones deliberately fall back to a git-YYYY-MM-DD-hash version string.
r08_phase ffmpeg_pin
git clone --filter=blob:none --no-checkout --single-branch --branch master \
  https://github.com/FFmpeg/FFmpeg.git deps/ffmpeg
git -C deps/ffmpeg checkout --detach "$FFMPEG_SHA"
test "$(git -C deps/ffmpeg rev-parse HEAD)" = "$FFMPEG_SHA"
actual_ffmpeg_describe="$(git -C deps/ffmpeg describe --tags --match N)"
echo "ffmpeg_describe_actual=$actual_ffmpeg_describe"
test "$actual_ffmpeg_describe" = "$FFMPEG_DESCRIBE"

r08_phase harness_download
./download.sh

# Replace the floating libplacebo clone with the exact R07 renderer revision.
r08_phase libplacebo_pin
rm -rf deps/libplacebo
git init deps/libplacebo
git -C deps/libplacebo remote add origin https://github.com/haasn/libplacebo.git
git -C deps/libplacebo fetch --depth=8 origin "$LIBPLACEBO_SHA"
git -C deps/libplacebo fetch --depth=1 origin "refs/tags/$LIBPLACEBO_TAG:refs/tags/$LIBPLACEBO_TAG"
git -C deps/libplacebo checkout --detach "$LIBPLACEBO_SHA"
git -C deps/libplacebo config core.abbrev 8
git -C deps/libplacebo submodule update --init --recursive --depth=1

test "$(git -C deps/libplacebo rev-parse HEAD)" = "$LIBPLACEBO_SHA"
actual_libplacebo_describe="$(git -C deps/libplacebo describe --dirty)"
echo "libplacebo_describe_actual=$actual_libplacebo_describe"
test "$actual_libplacebo_describe" = "$LIBPLACEBO_DESCRIBE"

# The accepted R07 FFmpeg binary embeds this exact option topology. Reconstruct
# it directly instead of inheriting a later harness recipe.
r08_phase ffmpeg_recipe
cat > scripts/ffmpeg.sh <<'EOF'
#!/bin/bash -e
. ../../include/path.sh

if [ "$1" == "build" ]; then
    true
elif [ "$1" == "clean" ]; then
    rm -rf _build$ndk_suffix
    exit 0
else
    exit 255
fi

mkdir -p _build$ndk_suffix
cd _build$ndk_suffix

cpu=armv7-a
[[ "$ndk_triple" == "aarch64"* ]] && cpu=armv8-a
[[ "$ndk_triple" == "x86_64"* ]] && cpu=generic
[[ "$ndk_triple" == "i686"* ]] && cpu="i686 --disable-asm"

cpuflags=
[[ "$ndk_triple" == "arm"* ]] && cpuflags="$cpuflags -mfpu=neon -mcpu=cortex-a8"

args=(
    --target-os=android --enable-cross-compile
    --cross-prefix=$ndk_triple- --cc=$CC --pkg-config=pkg-config --nm=llvm-nm
    --arch=${ndk_triple%%-*} --cpu=$cpu
    --extra-cflags="-I$prefix_dir/include $cpuflags" --extra-ldflags="-L$prefix_dir/lib"
    --enable-jni --enable-mediacodec --enable-mbedtls --enable-libdav1d --disable-vulkan
    --disable-static --enable-shared --enable-gpl --enable-version3
    --disable-stripping --disable-doc --disable-programs
    --disable-muxers --disable-encoders --disable-devices
    --enable-encoder=mjpeg,png
    --enable-muxer=mov,matroska,mpegts
)
../configure "${args[@]}"
make -j$cores
make DESTDIR="$prefix_dir" install
EOF
chmod +x scripts/ffmpeg.sh

test "$(git -C deps/ffmpeg rev-parse HEAD)" = "$FFMPEG_SHA"
git -C deps/ffmpeg diff --quiet

# Exact R07 mpv plus only the resident PARAM prerequisite backports.
r08_phase mpv_param_backport
cd deps/mpv
git config gc.auto 0
git fetch --no-tags --depth=3 origin \
  "$MPV_BASE_SHA" "$MPV_PARAM_HELPER_SHA" "$MPV_PARAM_SHA"
git checkout --detach "$MPV_BASE_SHA"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git cherry-pick "$MPV_PARAM_HELPER_SHA"
git cherry-pick "$MPV_PARAM_SHA"
test "$(git rev-list --count "$MPV_BASE_SHA"..HEAD)" = "2"
git log --oneline --decorate -3
cd ../..

r08_phase vulkan_topology_patch
python3 - <<'PY'
from pathlib import Path

# Accepted R07 native ELFs are Android API 24 and NDK r29.
p = Path('buildall.sh')
s = p.read_text()
old = 'local apilvl=21'
assert s.count(old) == 1, s.count(old)
s = s.replace(old, 'local apilvl=24', 1)
old = 'export LDFLAGS="-Wl,-O1,--icf=safe -Wl,-z,max-page-size=16384"'
assert s.count(old) == 1, s.count(old)
s = s.replace(old, 'export LDFLAGS="-Wl,-O1,--icf=safe -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"', 1)
p.write_text(s)

p = Path('../lib/src/main/jni/Application.mk')
s = p.read_text()
old = 'APP_PLATFORM := android-21'
assert s.count(old) == 1, s.count(old)
p.write_text(s.replace(old, 'APP_PLATFORM := android-24', 1))

# Enable the libplacebo Vulkan proc-address path.
p = Path('scripts/libplacebo.sh')
s = p.read_text()
old = '-Dvulkan=disabled -Ddemos=false'
assert s.count(old) == 1, s.count(old)
p.write_text(s.replace(old, '-Dvk-proc-addr=enabled -Ddemos=false', 1))

# Android supplies Vulkan through the platform loader but not pkg-config.
p = Path('scripts/mpv.sh')
s = p.read_text()
anchor = 'unset CC CXX # meson wants these unset\n\nmeson setup $build --cross-file "$prefix_dir"/crossfile.txt \\\n'
assert s.count(anchor) == 1, s.count(anchor)
vulkan_pc = '''# Android platform Vulkan loader discovery.\nmkdir -p "$prefix_dir"/lib/pkgconfig\ncat >"$prefix_dir"/lib/pkgconfig/vulkan.pc <<"END"\nName: Vulkan\nDescription: Android Vulkan loader\nVersion: 1.3.275\nLibs: -lvulkan\nCflags:\nEND\n\n'''
s = s.replace(anchor, vulkan_pc + anchor, 1)
old = '-Diconv=disabled -Dlua=enabled \\\n'
assert s.count(old) == 1, s.count(old)
s = s.replace(old, '-Diconv=disabled -Dlua=enabled -Dvulkan=enabled \\\n', 1)
# Preserve the exact accepted R07 mpv configuration fingerprint. Newer
# Meson normalizes --default-library to the front of the embedded option
# string; spelling it as -Ddefault_library=shared at the end preserves
# identical build semantics and the accepted R07 metadata ordering.
old = '\t--default-library shared \\\n'
assert s.count(old) == 1, s.count(old)
s = s.replace(old, '', 1)
old = '\t-Dmanpage-build=disabled\n'
assert s.count(old) == 1, s.count(old)
s = s.replace(old, '\t-Dmanpage-build=disabled -Ddefault_library=shared\n', 1)
p.write_text(s)
PY

r08_phase shaderc_topology
mkdir -p deps/shaderc
cat > deps/shaderc/README <<'EOF'
shaderc sources are supplied by the exact Android NDK selected by the parity build.
EOF
cat > scripts/shaderc.sh <<'EOF'
#!/bin/bash -e
. ../../include/path.sh

if [ "$1" == "build" ]; then
    true
elif [ "$1" == "clean" ]; then
    rm -rf local include libs
    exit 0
else
    exit 255
fi

builddir=$PWD
application_mk=$PWD/../../../lib/src/main/jni/Application.mk
abi=armeabi-v7a
[[ "$ndk_triple" == "aarch64"* ]] && abi=arm64-v8a
[[ "$ndk_triple" == "x86_64"* ]] && abi=x86_64
[[ "$ndk_triple" == "i686"* ]] && abi=x86

shaderc_root="$(dirname "$(which ndk-build)")/sources/third_party/shaderc"
test -d "$shaderc_root" || { echo "NDK shaderc source missing: $shaderc_root" >&2; exit 1; }
cd "$shaderc_root"
ndk-build -j$cores \
    NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk \
    NDK_APPLICATION_MK="$application_mk" APP_ABI=$abi \
    NDK_APP_OUT="$builddir" NDK_APP_LIBS_OUT="$builddir/libs" \
    libshaderc_combined

cd "$builddir"
install_root="$prefix_dir/usr/local"
mkdir -p "$install_root/include" "$install_root/lib/pkgconfig"
cp -vr include/* "$install_root/include"
cp -v libs/*/$abi/libshaderc.a "$install_root/lib/libshaderc_combined.a"
cat >"$install_root/lib/pkgconfig/shaderc_combined.pc" <<"END"
Name: shaderc_combined
Description: Android NDK shaderc
Version: r29
Libs: -L/usr/local/lib -lshaderc_combined
Cflags: -I/usr/local/include
END
pkg-config --exists shaderc_combined
pkg-config --cflags shaderc_combined | grep -F -- '-I'
pkg-config --libs shaderc_combined | grep -F -- '-lshaderc_combined'
EOF
chmod +x scripts/shaderc.sh

r08_phase param64_patch
python3 - <<'PY'
from pathlib import Path
p = Path('deps/mpv/video/out/gpu/user_shaders.h')
s = p.read_text()
old = '#define SHADER_MAX_PARAMS 16'
assert s.count(old) == 1, s.count(old)
p.write_text(s.replace(old, '#define SHADER_MAX_PARAMS 64', 1))
PY

r08_phase static_audit
grep -Fx 'v_ndk=r29' include/depinfo.sh
grep -Fx 'v_ndk_n=29.0.14206865' include/depinfo.sh
grep -Fx 'dep_ffmpeg=(mbedtls dav1d)' include/depinfo.sh
grep -Fx 'dep_libplacebo=(shaderc)' include/depinfo.sh
grep -F 'local apilvl=24' buildall.sh
grep -F -- '-Wl,-z,common-page-size=16384' buildall.sh
grep -F 'APP_PLATFORM := android-24' ../lib/src/main/jni/Application.mk
grep -F -- '--disable-muxers --disable-encoders --disable-devices' scripts/ffmpeg.sh
grep -F -- '--enable-muxer=mov,matroska,mpegts' scripts/ffmpeg.sh
! grep -Fq 'libxml2' scripts/ffmpeg.sh
! grep -Fq 'force_mpegts' deps/ffmpeg/libavformat/hls.c
grep -F -- '-Dvk-proc-addr=enabled' scripts/libplacebo.sh
grep -F -- '-Dvulkan=enabled' scripts/mpv.sh
grep -F 'install_root="$prefix_dir/usr/local"' scripts/shaderc.sh
grep -F 'lib/pkgconfig/shaderc_combined.pc' scripts/shaderc.sh
grep -F '#define SHADER_MAX_PARAMS 64' deps/mpv/video/out/gpu/user_shaders.h

r08_phase native_build
build_rc=0
./buildall.sh --arch arm64 mpv || build_rc=$?
if (( build_rc != 0 )); then
    echo '=== R08 renderer parity compact failure ===' >&2
    grep -nEi '(^|[^[:alpha:]])(error|fatal|failed|meson|shaderc|ndk|vulkan)([^[:alpha:]]|$)' \
      "$TRACE_LOG" 2>/dev/null | tail -n 160 >&2 || true
    echo '=== R08 native log tail ===' >&2
    tail -n 120 "$TRACE_LOG" >&2 2>/dev/null || true
    exit "$build_rc"
fi

r08_phase renderer_fingerprint_gates
PREFIX=prefix/arm64/lib
LIB="$PREFIX/libmpv.so"
FF="$PREFIX/libavformat.so"
for name in libmpv.so libavcodec.so libavdevice.so libavfilter.so libavformat.so libavutil.so libswresample.so libswscale.so; do
    test -f "$PREFIX/$name"
done

# Exact renderer/source fingerprints recovered from the accepted R07 APK.
strings "$LIB" | grep -F "$FFMPEG_DESCRIBE"
strings "$LIB" | grep -F 'v7.360.0 (v7.360.0-3-gc93aa134)'
strings "$LIB" | grep -F -- '-Diconv=disabled -Dlua=enabled -Dvulkan=enabled -Dlibmpv=true -Dcplayer=false -Dmanpage-build=disabled -Ddefault_library=shared'
strings "$LIB" | grep -F 'pl_vulkan_create'
readelf -dW "$LIB" | tee /tmp/r08-renderer-parity-dynamic.txt
readelf -dW "$LIB" | grep -F 'Shared library: [libvulkan.so]'
if readelf -dW "$LIB" | grep -Fq 'Shared library: [libxml2.so]'; then
    echo 'R08 parity failure: accepted R07 libmpv has no DT_NEEDED libxml2.so' >&2
    exit 1
fi

ffcfg="$(strings "$FF" | grep '^--target-os=android ' | head -n1)"
printf '%s\n' "$ffcfg" | tee /tmp/r08-renderer-parity-ffmpeg-config.txt
grep -F -- '--cc=aarch64-linux-android24-clang' /tmp/r08-renderer-parity-ffmpeg-config.txt
grep -F -- '--enable-jni --enable-mediacodec --enable-mbedtls --enable-libdav1d --disable-vulkan' /tmp/r08-renderer-parity-ffmpeg-config.txt
grep -F -- '--disable-static --enable-shared --enable-gpl --enable-version3' /tmp/r08-renderer-parity-ffmpeg-config.txt
grep -F -- '--disable-stripping --disable-doc --disable-programs --disable-muxers --disable-encoders --disable-devices' /tmp/r08-renderer-parity-ffmpeg-config.txt
grep -F -- "--enable-encoder='mjpeg,png'" /tmp/r08-renderer-parity-ffmpeg-config.txt
grep -F -- "--enable-muxer='mov,matroska,mpegts'" /tmp/r08-renderer-parity-ffmpeg-config.txt
if grep -Fq -- '--enable-libxml2' /tmp/r08-renderer-parity-ffmpeg-config.txt; then
    echo 'R08 parity failure: accepted R07 FFmpeg did not enable libxml2' >&2
    exit 1
fi
if strings "$FF" | grep -Fq 'force_mpegts'; then
    echo 'R08 parity failure: accepted R07 FFmpeg did not contain force_mpegts' >&2
    exit 1
fi
if readelf -dW "$FF" | grep -Fq 'Shared library: [libxml2.so]'; then
    echo 'R08 parity failure: accepted R07 libavformat has no DT_NEEDED libxml2.so' >&2
    exit 1
fi

# All rebuilt renderer/FFmpeg ELFs must reproduce the R07 Android API 24 +
# NDK r29 identity. Parse the canonical .note.android.ident payload directly
# instead of depending on host file(1) prose, which caused parity runs #22/#24
# to false-negative after the native build itself completed successfully.
: > /tmp/r08-renderer-parity-program-headers.txt
: > /tmp/r08-renderer-parity-android-ident.txt
for name in libmpv.so libavcodec.so libavdevice.so libavfilter.so libavformat.so libavutil.so libswresample.so libswscale.so; do
    so="$PREFIX/$name"
    file "$so" | tee -a /tmp/r08-renderer-parity-android-ident.txt
    python3 - "$so" "$R07_NDK_TAG" "$R07_NDK_BUILD" <<'PY'
import re
import struct
import subprocess
import sys

so, expected_ndk, expected_build = sys.argv[1:]
text = subprocess.check_output(
    ['readelf', '-x', '.note.android.ident', so],
    text=True,
    stderr=subprocess.STDOUT,
)
words = []
for line in text.splitlines():
    match = re.match(r'\s*0x[0-9a-fA-F]+\s+((?:[0-9a-fA-F]{8}(?:\s+|$)){1,4})', line)
    if match:
        words.extend(match.group(1).split())
raw = bytes.fromhex(''.join(words))
assert len(raw) >= 12, f'{so}: malformed .note.android.ident'
namesz, descsz, note_type = struct.unpack_from('<III', raw, 0)
off = 12
name = raw[off:off + namesz].rstrip(b'\0')
off = (off + namesz + 3) & ~3
desc = raw[off:off + descsz]
assert name == b'Android' and note_type == 1, (
    f'{so}: unexpected Android note header name={name!r} type={note_type}'
)
assert len(desc) >= 132, f'{so}: Android note descriptor too short: {len(desc)}'
api = struct.unpack_from('<I', desc, 0)[0]
ndk = desc[4:68].split(b'\0', 1)[0].decode('ascii')
build = desc[68:132].split(b'\0', 1)[0].decode('ascii')
assert api == 24, f'{so}: expected Android API 24, got {api}'
assert ndk == expected_ndk, f'{so}: expected NDK {expected_ndk}, got {ndk}'
assert build == expected_build, f'{so}: expected NDK build {expected_build}, got {build}'
print(f'{so}: Android API {api}, NDK {ndk} ({build})')
PY
    echo "===== $name =====" >> /tmp/r08-renderer-parity-program-headers.txt
    readelf -lW "$so" >> /tmp/r08-renderer-parity-program-headers.txt
    python3 - "$so" <<'PY'
import subprocess, sys
so = sys.argv[1]
text = subprocess.check_output(['readelf', '-lW', so], text=True)
loads = [line for line in text.splitlines() if line.lstrip().startswith('LOAD')]
assert loads, f'{so}: no LOAD segments'
bad = [line for line in loads if int(line.split()[-1], 16) < 0x4000]
assert not bad, f'{so}: LOAD alignment below 16 KB:\n' + '\n'.join(bad)
PY
done

{
    echo "harness=$HARNESS_SHA"
    echo "mpv_base=$MPV_BASE_SHA"
    echo "mpv_param_helper=$MPV_PARAM_HELPER_SHA"
    echo "mpv_param=$MPV_PARAM_SHA"
    echo "ffmpeg=$FFMPEG_SHA"
    echo "ffmpeg_describe=$FFMPEG_DESCRIBE"
    echo "libplacebo=$LIBPLACEBO_SHA"
    echo "libplacebo_describe=$LIBPLACEBO_DESCRIBE"
    echo "ndk=$R07_NDK_TAG"
    echo "ndk_revision=$R07_NDK_REV"
    echo "ndk_build=$R07_NDK_BUILD"
    echo "android_api=24"
    echo "shader_max_params=64"
    echo "vulkan_build_flag=enabled"
    echo "dt_needed_libvulkan=yes"
    echo "dt_needed_libxml2=no"
    echo "ffmpeg_libxml2=no"
    echo "ffmpeg_force_mpegts=no"
    echo "ffmpeg_disable_muxers=yes"
    echo "post_r07_miner_patches=none"
    echo "native_stack=libmpv+ffmpeg"
    echo "r07_libplacebo_string=v7.360.0 (v7.360.0-3-gc93aa134)"
} | tee /tmp/r08-renderer-parity-fingerprint.txt

r08_phase complete
