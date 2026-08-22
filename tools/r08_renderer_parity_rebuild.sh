#!/bin/bash -e
set -Eeuo pipefail

# R08 parity wrapper.
#
# Keep the already-proven exact-R07 native rebuild recipe byte-for-byte frozen
# at the commit below. This wrapper materializes that script, injects only the
# separately-audited R08 live-uniform PARAM patch, and executes it.
#
# IMPORTANT: the R08 patch touches mpv's vo=gpu renderer only. The runtime APK
# must therefore keep the proven R07 FFmpeg shared libraries byte-for-byte.
# Rebuilding FFmpeg is still required while compiling libmpv, but those freshly
# built FFmpeg .so files are NOT allowed to leak into the packaged parity AAR.
#
# R07 also statically links Shaderc (and its bundled Glslang/SPIR-V stack) into
# libmpv through libplacebo. A parity build without that compiler backend can
# initialize playback while Vulkan renders only black frames. Force Shaderc on
# and verify the final ELF carries the same compiler surface as accepted R07.

ROOT_DIR="${GITHUB_WORKSPACE:-$(pwd)}"
BASE_SCRIPT_COMMIT=9a941321285cc2dcf47b3a24aaf5765575815936
BASE_SCRIPT_PATH=tools/r08_renderer_parity_rebuild.sh
TMP_SCRIPT="$(mktemp -t r08-renderer-parity-live.XXXXXX.sh)"
TMP_R07_AAR="$(mktemp -d -t r08-r07-aar.XXXXXX)"
trap 'rm -f "$TMP_SCRIPT"; rm -rf "$TMP_R07_AAR"' EXIT

if ! git -C "$ROOT_DIR" cat-file -e "$BASE_SCRIPT_COMMIT^{commit}" 2>/dev/null; then
    git -C "$ROOT_DIR" fetch --no-tags --depth=1 origin "$BASE_SCRIPT_COMMIT"
fi

git -C "$ROOT_DIR" show "$BASE_SCRIPT_COMMIT:$BASE_SCRIPT_PATH" > "$TMP_SCRIPT"

python3 - "$TMP_SCRIPT" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

anchor = '''r08_phase static_audit\n'''
assert s.count(anchor) == 1, s.count(anchor)
inject = '''r08_phase live_uniform_param_patch\npython3 "$ROOT_DIR/tools/patch_r08_live_uniform_params.py" deps/mpv\n\n'''
s = s.replace(anchor, inject + anchor, 1)

# The frozen parity recipe built Android NDK's libshaderc_combined archive, but
# exposed it through the wrong pkg-config identity. libplacebo requests
# dependency('shaderc', version: '>=2019.1'); shaderc_combined.pc Version:r29
# therefore failed auto-detection and silently removed the only SPIR-V compiler.
replacements = {
    'lib/pkgconfig/shaderc_combined.pc': 'lib/pkgconfig/shaderc.pc',
    'Name: shaderc_combined': 'Name: shaderc',
    'Version: r29': 'Version: 2025.1',
    'pkg-config --exists shaderc_combined': 'pkg-config --exists shaderc',
    'pkg-config --cflags shaderc_combined': 'pkg-config --cflags shaderc',
    'pkg-config --libs shaderc_combined': 'pkg-config --libs shaderc',
}
for old, new in replacements.items():
    assert old in s, old
    s = s.replace(old, new)

# Make Shaderc availability a build requirement rather than an optional Meson
# auto-feature. shaderc_combined already contains the matching Glslang/SPIR-V
# implementation, so disable libplacebo's second direct Glslang backend.
old = "-Dvk-proc-addr=enabled -Ddemos=false"
new = "-Dvk-proc-addr=enabled -Dshaderc=enabled -Dglslang=disabled -Ddemos=false"
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

anchor = '''grep -F '#define SHADER_MAX_PARAMS 64' deps/mpv/video/out/gpu/user_shaders.h\n'''
assert s.count(anchor) == 1, s.count(anchor)
audit = '''grep -F 'char *source_path;' deps/mpv/video/out/gpu/user_shaders.h\ngrep -F 'refresh_live_user_shader_opts' deps/mpv/video/out/gpu/video.c\ngrep -F 'm_config_cache_get_next_changed(p->opts_cache, &changed)' deps/mpv/video/out/gpu/video.c\ngrep -F 'changed == &cached->user_shader_opts' deps/mpv/video/out/gpu/video.c\ngrep -F 'R08 live user shader PARAM update' deps/mpv/video/out/gpu/video.c\ngrep -F -- '-Dshaderc=enabled' scripts/libplacebo.sh\ngrep -F -- '-Dglslang=disabled' scripts/libplacebo.sh\npkg_config_file="$prefix_dir/usr/local/lib/pkgconfig/shaderc.pc"\n'''
s = s.replace(anchor, anchor + audit, 1)

anchor = '''    echo "shader_max_params=64"\n'''
assert s.count(anchor) == 1, s.count(anchor)
s = s.replace(anchor, anchor + '    echo "r08_live_uniform_params=yes"\n', 1)

# Reject the exact failure that produced the black-screen Pixel build. The
# accepted R07 libmpv exports 6936 dynamic definitions and contains the static
# Shaderc/Glslang compiler stack. A compiler-less rebuild had only 1595.
anchor = '''strings "$LIB" | grep -F 'pl_vulkan_create'\n'''
assert s.count(anchor) == 1, s.count(anchor)
compiler_gate = '''strings "$LIB" | grep -F 'shaderc_compiler_initialize'\nstrings "$LIB" | grep -F 'shaderc_compile_into_spv'\nnm -D --defined-only "$LIB" | grep -F ' shaderc_compiler_initialize'\nnm -D --defined-only "$LIB" | grep -F ' shaderc_compile_into_spv'\nr08_dynsym_count="$(nm -D --defined-only "$LIB" | wc -l | tr -d ' ')"\necho "r08_libmpv_defined_dynsym_count=$r08_dynsym_count"\ntest "$r08_dynsym_count" -ge 6500\nr08_libmpv_size="$(stat -c %s "$LIB")"\necho "r08_libmpv_size=$r08_libmpv_size"\ntest "$r08_libmpv_size" -ge 11000000\n'''
s = s.replace(anchor, anchor + compiler_gate, 1)

anchor = '''    echo "r07_libplacebo_string=v7.360.0 (v7.360.0-3-gc93aa134)"\n'''
assert s.count(anchor) == 1, s.count(anchor)
s = s.replace(anchor, anchor + '    echo "spirv_compiler=shaderc_static_r07"\n    echo "libmpv_dynsym_gate_ge=6500"\n    echo "libmpv_size_gate_ge=11000000"\n', 1)

p.write_text(s)
PY

chmod +x "$TMP_SCRIPT"
bash "$TMP_SCRIPT"

# The device regression from the first R08 parity APK proved that source/config
# fingerprints alone are insufficient. The actual user-tested R07 APK contains
# the hashes below. The repository base AAR carries the same R07 FFmpeg stack.
# Verify that fact, then overwrite the freshly rebuilt FFmpeg runtime libraries
# in the prefix so downstream parity packaging can only replace libmpv itself.
BASE_AAR="$ROOT_DIR/app/libs/mpv-android-lib-v0.0.1.aar"
PREFIX="$ROOT_DIR/native/mpv-android/buildscripts/prefix/arm64/lib"
test -f "$BASE_AAR"
test -f "$PREFIX/libmpv.so"
unzip -q "$BASE_AAR" 'jni/arm64-v8a/*.so' -d "$TMP_R07_AAR"
R07_LIB_DIR="$TMP_R07_AAR/jni/arm64-v8a"

declare -A R07_SHA256=(
  [libavcodec.so]=877386be91178e01997cee480ac1a7adbb77a9367408a88216b5ff87e02f01b4
  [libavdevice.so]=6416eae02515326805377891ded2bdec9677a7a183f4c18aa4555dfb7680da6a
  [libavfilter.so]=c42af455f845636fa003184d3034aeb8fdeff378ecbce8a7403953cee0457acb
  [libavformat.so]=d814e70c446c2aef56197cfd61370a880f78359f5ece2ee713214b379975cfbd
  [libavutil.so]=b7a08a5d228ac2f6e95a65a41e1bdf62b2fde7003ec73dd2b62e4e99cb05eaaa
  [libswresample.so]=35b37154205f60f2692a250367f30f5711d7749ddbbe52710c3ed2bd15a4bff9
  [libswscale.so]=757d7b7db9aed9f6e5bb1fcf50b4c3ac6d00ae4d3a7166718c2c097dcf38c043
)

for name in libavcodec.so libavdevice.so libavfilter.so libavformat.so libavutil.so libswresample.so libswscale.so; do
  src="$R07_LIB_DIR/$name"
  test -f "$src"
  actual="$(sha256sum "$src" | awk '{print $1}')"
  expected="${R07_SHA256[$name]}"
  if [[ "$actual" != "$expected" ]]; then
    echo "R07 runtime hash mismatch: $name expected=$expected actual=$actual" >&2
    exit 1
  fi
  cp -f "$src" "$PREFIX/$name"
  restored="$(sha256sum "$PREFIX/$name" | awk '{print $1}')"
  test "$restored" = "$expected"
  echo "R07_RUNTIME_RESTORED $name $restored"
done

# Preserve this proof in the parity artifact's existing renderer fingerprint.
{
  echo "runtime_ffmpeg_stack=r07_base_aar_exact"
  echo "runtime_ffmpeg_hash_gate=yes"
} >> /tmp/r08-renderer-parity-fingerprint.txt

echo 'R08 runtime isolation PASS: patched libmpv + R07 Shaderc + byte-exact R07 FFmpeg stack'
