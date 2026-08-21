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

HARNESS_SHA=fd17fb02cfb8c8b5c12621c2c90769685d635d91
MPV_BASE_SHA=d54bad5636924ab3f39cb6e397b94b6aa8a7c433
MPV_PARAM_HELPER_SHA=91ceffce42534a45705617036b6b2a392a32fc57
MPV_PARAM_SHA=0d655fe66590009e1d77a17581257d677286531a
FFMPEG_SHA=5ba2525c7affc29cbd99e6266946b382d3fffe8b
LIBPLACEBO_SHA=c93aa134ab62365ce1177efff99b8e1e66a818e7
LIBPLACEBO_TAG=v7.360.0
LIBPLACEBO_DESCRIBE=v7.360.0-3-gc93aa134

r08_phase harness_checkout
rm -rf "$HARNESS_DIR"
git clone https://github.com/Secozzi/mpv-android.git "$HARNESS_DIR"
git -C "$HARNESS_DIR" checkout "$HARNESS_SHA"

cd "$BUILDSCRIPTS"
mkdir -p deps

# Pre-create the exact FFmpeg tree so the harness cannot substitute its own default.
r08_phase ffmpeg_pin
git clone --filter=blob:none --no-checkout --shallow-since='2026-02-20T00:00:00Z' \
  https://github.com/FFmpeg/FFmpeg.git deps/ffmpeg
git -C deps/ffmpeg checkout --detach "$FFMPEG_SHA"

r08_phase harness_download
./download.sh

# Replace the harness's floating libplacebo clone with the exact R07 renderer revision.
# Fetch a small ancestor window plus the release tag because libplacebo embeds
# `git describe --dirty` in its version string. Code SHA and version fingerprint
# must both reproduce R07 exactly. Pin the local abbreviation width because the
# deliberately tiny shallow object database otherwise chooses seven hex digits,
# while the proven R07 build embedded eight.
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

# Reproduce the Android force_mpegts delta against the exact shipped FFmpeg source.
r08_phase ffmpeg_android_delta
python3 - <<'PY'
from pathlib import Path
path = Path('deps/ffmpeg/libavformat/hls.c')
text = path.read_text()

old = '    int extension_picky;\n    int max_reload;\n    int http_persistent;'
new = '    int extension_picky;\n    int max_reload;\n    int force_mpegts;\n    int http_persistent;'
assert text.count(old) == 1, f'struct anchor count={text.count(old)}'
text = text.replace(old, new, 1)

old = '            in_fmt = av_find_input_format(pls->audio_setup_info.codec_id == AV_CODEC_ID_AAC ? "aac" :\n                                          pls->audio_setup_info.codec_id == AV_CODEC_ID_AC3 ? "ac3" : "eac3");\n        } else {\n            pls->ctx->probesize = s->probesize > 0 ? s->probesize : 1024 * 4;'
new = '            in_fmt = av_find_input_format(pls->audio_setup_info.codec_id == AV_CODEC_ID_AAC ? "aac" :\n                                          pls->audio_setup_info.codec_id == AV_CODEC_ID_AC3 ? "ac3" : "eac3");\n        } else if (c->force_mpegts > 0) {\n            in_fmt = av_find_input_format("mpegts");\n        } else {\n            pls->ctx->probesize = s->probesize > 0 ? s->probesize : 1024 * 4;'
assert text.count(old) == 1, f'probe anchor count={text.count(old)}'
text = text.replace(old, new, 1)

old = '    {"m3u8_hold_counters", "The maximum number of times to load m3u8 when it refreshes without new segments",\n        OFFSET(m3u8_hold_counters), AV_OPT_TYPE_INT, {.i64 = 1000}, 0, INT_MAX, FLAGS},\n    {"http_persistent", "Use persistent HTTP connections",'
new = '    {"m3u8_hold_counters", "The maximum number of times to load m3u8 when it refreshes without new segments",\n        OFFSET(m3u8_hold_counters), AV_OPT_TYPE_INT, {.i64 = 1000}, 0, INT_MAX, FLAGS},\n    {"force_mpegts", "Force use of mpegts format for hls segments",\n        OFFSET(force_mpegts), AV_OPT_TYPE_BOOL, {.i64 = 0}, 0, 1, FLAGS},\n    {"http_persistent", "Use persistent HTTP connections",'
assert text.count(old) == 1, f'option anchor count={text.count(old)}'
text = text.replace(old, new, 1)
path.write_text(text)
PY

test "$(git -C deps/ffmpeg rev-parse HEAD)" = "$FFMPEG_SHA"
git -C deps/ffmpeg diff --check

# Reset mpv to the proven R07 base and layer only the resident PARAM backport.
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
cd ../..

# Apply the Android Vulkan build topology used by mpv-android's Vulkan support branch,
# while keeping our exact R07 source revisions.
r08_phase vulkan_topology_patch
python3 - <<'PY'
from pathlib import Path

# Native API level required by the Android Vulkan build path.
buildall = Path('buildall.sh')
text = buildall.read_text()
old = 'local apilvl=21'
assert text.count(old) == 1, text.count(old)
text = text.replace(old, 'local apilvl=24', 1)
old = 'export LDFLAGS="-Wl,-O1,--icf=safe -Wl,-z,max-page-size=16384"'
assert text.count(old) == 1, text.count(old)
text = text.replace(old, 'export LDFLAGS="-Wl,-O1,--icf=safe -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"', 1)
buildall.write_text(text)

application = Path('../lib/src/main/jni/Application.mk')
text = application.read_text()
old = 'APP_PLATFORM := android-21'
assert text.count(old) == 1, text.count(old)
application.write_text(text.replace(old, 'APP_PLATFORM := android-24', 1))

# Make shaderc a libplacebo dependency.
depinfo = Path('include/depinfo.sh')
text = depinfo.read_text()
old = 'dep_libplacebo=()'
assert text.count(old) == 1, text.count(old)
depinfo.write_text(text.replace(old, 'dep_shaderc=()\ndep_libplacebo=(shaderc)', 1))

# Enable libplacebo's Vulkan proc-address path instead of compiling Vulkan out.
placebo = Path('scripts/libplacebo.sh')
text = placebo.read_text()
old = '-Dvulkan=disabled -Ddemos=false'
assert text.count(old) == 1, text.count(old)
placebo.write_text(text.replace(old, '-Dvk-proc-addr=enabled -Ddemos=false', 1))

# Provide Android Vulkan discovery and force mpv Vulkan support on.
mpv = Path('scripts/mpv.sh')
text = mpv.read_text()
anchor = 'unset CC CXX # meson wants these unset\n\nmeson setup $build --cross-file "$prefix_dir"/crossfile.txt \\\n'
assert text.count(anchor) == 1, text.count(anchor)
vulkan_pc = '''# Android provides Vulkan but no pkg-config file.\nmkdir -p "$prefix_dir"/lib/pkgconfig\ncat >"$prefix_dir"/lib/pkgconfig/vulkan.pc <<"END"\nName: Vulkan\nDescription: Android Vulkan loader\nVersion: 1.3.275\nLibs: -lvulkan\nCflags:\nEND\n\n'''
text = text.replace(anchor, vulkan_pc + anchor, 1)
old = '-Diconv=disabled -Dlua=enabled \\\n'
assert text.count(old) == 1, text.count(old)
text = text.replace(old, '-Diconv=disabled -Dlua=enabled -Dvulkan=enabled \\\n', 1)
mpv.write_text(text)
PY

r08_phase shaderc_topology
mkdir -p deps/shaderc
cat > deps/shaderc/README <<'EOF'
shaderc sources are supplied by the Android NDK.
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
cp -vr include/* "$prefix_dir/include"
cp -v libs/*/$abi/libshaderc.a "$prefix_dir/lib/libshaderc_combined.a"
mkdir -p "$prefix_dir"/lib/pkgconfig
cat >"$prefix_dir"/lib/pkgconfig/shaderc_combined.pc <<"END"
Name: shaderc_combined
Description: Android NDK shaderc
Version: 2022.3-unknown
Libs: -L/usr/local/lib -lshaderc_combined
Cflags: -I/usr/local/include
END

if [ -z "$(pkg-config --cflags shaderc_combined)" ]; then
    echo "shaderc pkg-config sanity check failed" >&2
    exit 1
fi
EOF
chmod +x scripts/shaderc.sh

# Increase only the fixed vo=gpu PARAM table ceiling.
r08_phase param64_patch
python3 - <<'PY'
from pathlib import Path
header = Path('deps/mpv/video/out/gpu/user_shaders.h')
text = header.read_text()
old = '#define SHADER_MAX_PARAMS 16'
assert text.count(old) == 1, text.count(old)
header.write_text(text.replace(old, '#define SHADER_MAX_PARAMS 64', 1))
PY

# Static source/config audit before the expensive build.
r08_phase static_audit
grep -F 'local apilvl=24' buildall.sh
grep -F -- '-Wl,-z,common-page-size=16384' buildall.sh
grep -F 'APP_PLATFORM := android-24' ../lib/src/main/jni/Application.mk
grep -F 'dep_libplacebo=(shaderc)' include/depinfo.sh
grep -F -- '-Dvk-proc-addr=enabled' scripts/libplacebo.sh
grep -F -- '-Dvulkan=enabled' scripts/mpv.sh
grep -F '#define SHADER_MAX_PARAMS 64' deps/mpv/video/out/gpu/user_shaders.h

r08_phase native_build
build_rc=0
./buildall.sh --arch arm64 mpv || build_rc=$?
if (( build_rc != 0 )); then
    echo '=== R08 renderer parity compact failure ===' >&2
    grep -nEi '(^|[^[:alpha:]])(error|fatal|failed|meson|shaderc|ndk|vulkan)([^[:alpha:]]|$)' \
      "$TRACE_LOG" 2>/dev/null | tail -n 120 >&2 || true
    echo '=== R08 native log tail ===' >&2
    tail -n 100 "$TRACE_LOG" >&2 2>/dev/null || true
    exit "$build_rc"
fi

r08_phase renderer_fingerprint_gates
LIB=prefix/arm64/lib/libmpv.so
test -f "$LIB"

# Hard renderer fingerprint gates copied from the proven R07 APK characteristics.
strings "$LIB" | grep -F 'N-122998-g5ba2525c7a'
strings "$LIB" | grep -F 'v7.360.0 (v7.360.0-3-gc93aa134)'
strings "$LIB" | grep -F -- '-Dvulkan=enabled'
strings "$LIB" | grep -F 'pl_vulkan_create'
readelf -dW "$LIB" | tee /tmp/r08-renderer-parity-dynamic.txt
readelf -dW "$LIB" | grep -F 'Shared library: [libvulkan.so]'
readelf -lW "$LIB" | tee /tmp/r08-renderer-parity-program-headers.txt

python3 - <<'PY'
from pathlib import Path
text = Path('/tmp/r08-renderer-parity-program-headers.txt').read_text()
loads = [line for line in text.splitlines() if line.lstrip().startswith('LOAD')]
assert loads, 'no LOAD segments found in rebuilt libmpv'
bad = [line for line in loads if int(line.split()[-1], 16) < 0x4000]
assert not bad, 'libmpv LOAD segments below 16 KB alignment:\n' + '\n'.join(bad)
print('R08 renderer-parity libmpv 16 KB alignment PASS')
PY

{
    echo "harness=$HARNESS_SHA"
    echo "mpv_base=$MPV_BASE_SHA"
    echo "mpv_param_helper=$MPV_PARAM_HELPER_SHA"
    echo "mpv_param=$MPV_PARAM_SHA"
    echo "ffmpeg=$FFMPEG_SHA"
    echo "libplacebo=$LIBPLACEBO_SHA"
    echo "libplacebo_describe=$LIBPLACEBO_DESCRIBE"
    echo "shader_max_params=64"
    echo "vulkan_build_flag=enabled"
    echo "dt_needed_libvulkan=yes"
    echo "r07_libplacebo_string=v7.360.0 (v7.360.0-3-gc93aa134)"
} | tee /tmp/r08-renderer-parity-fingerprint.txt

r08_phase complete
