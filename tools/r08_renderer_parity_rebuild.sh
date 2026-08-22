#!/bin/bash -e
set -Eeuo pipefail

# R08 parity wrapper.
#
# Keep the already-proven exact-R07 native rebuild recipe byte-for-byte frozen
# at the commit below. This wrapper materializes that script, injects only the
# separately-audited R08 live-uniform PARAM patch, and executes it. That makes
# the new realtime behavior explicit without allowing the native parity recipe
# to drift while Shader Lab UI work continues.

ROOT_DIR="${GITHUB_WORKSPACE:-$(pwd)}"
BASE_SCRIPT_COMMIT=9a941321285cc2dcf47b3a24aaf5765575815936
BASE_SCRIPT_PATH=tools/r08_renderer_parity_rebuild.sh
TMP_SCRIPT="$(mktemp -t r08-renderer-parity-live.XXXXXX.sh)"
trap 'rm -f "$TMP_SCRIPT"' EXIT

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

anchor = '''grep -F '#define SHADER_MAX_PARAMS 64' deps/mpv/video/out/gpu/user_shaders.h\n'''
assert s.count(anchor) == 1, s.count(anchor)
audit = '''grep -F 'char *source_path;' deps/mpv/video/out/gpu/user_shaders.h\ngrep -F 'refresh_live_user_shader_opts' deps/mpv/video/out/gpu/video.c\ngrep -F 'm_config_cache_get_next_changed(p->opts_cache, &changed)' deps/mpv/video/out/gpu/video.c\ngrep -F 'changed == &cached->user_shader_opts' deps/mpv/video/out/gpu/video.c\ngrep -F 'R08 live user shader PARAM update' deps/mpv/video/out/gpu/video.c\n'''
s = s.replace(anchor, anchor + audit, 1)

anchor = '''    echo "shader_max_params=64"\n'''
assert s.count(anchor) == 1, s.count(anchor)
s = s.replace(anchor, anchor + '    echo "r08_live_uniform_params=yes"\n', 1)

p.write_text(s)
PY

chmod +x "$TMP_SCRIPT"
exec bash "$TMP_SCRIPT"
