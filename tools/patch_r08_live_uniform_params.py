#!/usr/bin/env python3
"""Patch the exact R07 vo=gpu + PARAM backport for true live uniform updates.

Upstream vo=gpu PARAM support stores user parameter values in each parsed hook,
but glsl-shader-opts changes currently flow through the generic renderer option
cache and call reinit_from_options(), which tears down rendering and rebuilds the
hook. That is acceptable for occasional option changes, but not for a 120 Hz
interactive Shader Lab.

R08 keeps the shader program resident. A glsl-shader-opts-only update now:
  * updates the option cache field,
  * refreshes parameter values in already-parsed user hooks,
  * invalidates the output frame,
  * avoids uninit_rendering()/shader-list churn.

Any other vo=gpu option change still takes the normal full reinit path.
"""

from __future__ import annotations

import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one patch anchor, found {count}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_r08_live_uniform_params.py <mpv-source-dir>")

    root = Path(sys.argv[1])
    video = root / "video/out/gpu/video.c"
    header = root / "video/out/gpu/user_shaders.h"
    if not video.is_file() or not header.is_file():
        raise SystemExit(f"not an mpv source tree: {root}")

    replace_once(
        header,
        """    struct gl_user_shader_param params[SHADER_MAX_PARAMS];\n    int num_params;\n    struct bstr save_tex;\n""",
        """    struct gl_user_shader_param params[SHADER_MAX_PARAMS];\n    int num_params;\n    // R08: retain source identity so live option refresh preserves shader/name scoping.\n    char *source_path;\n    struct bstr save_tex;\n""",
    )

    replace_once(
        video,
        """static void update_user_shader_opts(struct gl_video *p, const char *path,\n                                    struct gl_user_shader_hook *shader)\n{\n""",
        """static void update_user_shader_opts(struct gl_video *p,\n                                    struct gl_user_shader_hook *shader)\n{\n""",
    )

    replace_once(
        video,
        """    const char *basename = mp_basename(path);\n""",
        """    mp_assert(shader->source_path);\n    const char *basename = mp_basename(shader->source_path);\n""",
    )

    add_hook_anchor = """static bool add_user_hook(void *priv, const char *path,\n                          const struct gl_user_shader_hook *hook)\n{\n"""
    live_refresh = """static void refresh_live_user_shader_opts(struct gl_video *p)\n{\n    // p->opts is normally refreshed only by reinit_from_options(). For the\n    // PARAM-only fast path copy just the key/value list pointer from the\n    // fine-grained option cache, then update the already-resident hook copies.\n    struct gl_video_opts *cached = p->opts_cache->opts;\n    p->opts.user_shader_opts = cached->user_shader_opts;\n\n    for (int n = 0; n < p->num_tex_hooks; n++) {\n        struct tex_hook *hook = &p->tex_hooks[n];\n        if (hook->hook != user_hook || !hook->priv)\n            continue;\n        update_user_shader_opts(p, hook->priv);\n    }\n\n    // Uniform values are uploaded by user_hook() when the next frame is built.\n    // Do not tear down rendering or rebuild the shader program.\n    p->output_tex_valid = false;\n    MP_TRACE(p, \"R08 live user shader PARAM update\\n\");\n}\n\n""" + add_hook_anchor
    replace_once(video, add_hook_anchor, live_refresh)

    replace_once(
        video,
        """    struct gl_video *p = priv;\n    struct gl_user_shader_hook *copy = talloc_dup(p, (struct gl_user_shader_hook *)hook);\n    update_user_shader_opts(p, path, copy);\n    struct tex_hook texhook = {\n""",
        """    struct gl_video *p = priv;\n    struct gl_user_shader_hook *copy = talloc_dup(p, (struct gl_user_shader_hook *)hook);\n    copy->source_path = talloc_strdup(copy, path);\n    update_user_shader_opts(p, copy);\n    struct tex_hook texhook = {\n""",
    )

    replace_once(
        video,
        """static void gl_video_update_options(struct gl_video *p)\n{\n    if (m_config_cache_update(p->opts_cache)) {\n        gl_lcms_update_options(p->cms);\n        reinit_from_options(p);\n    }\n\n    if (mp_csp_equalizer_state_changed(p->video_eq))\n        p->output_tex_valid = false;\n}\n""",
        """static void gl_video_update_options(struct gl_video *p)\n{\n    bool full_reinit = false;\n    bool shader_params_changed = false;\n    void *changed = NULL;\n    struct gl_video_opts *cached = p->opts_cache->opts;\n\n    // Use fine-grained option updates so glsl-shader-opts can behave like a\n    // native live control. A PARAM-only change must not call uninit_rendering().\n    while (m_config_cache_get_next_changed(p->opts_cache, &changed)) {\n        if (changed == &cached->user_shader_opts)\n            shader_params_changed = true;\n        else\n            full_reinit = true;\n    }\n\n    if (full_reinit) {\n        gl_lcms_update_options(p->cms);\n        reinit_from_options(p);\n    } else if (shader_params_changed) {\n        refresh_live_user_shader_opts(p);\n    }\n\n    if (mp_csp_equalizer_state_changed(p->video_eq))\n        p->output_tex_valid = false;\n}\n""",
    )

    # Hard postconditions: fail the parity build instead of silently falling
    # back to renderer teardown semantics if upstream context ever drifts.
    text = video.read_text()
    required = [
        "refresh_live_user_shader_opts",
        "m_config_cache_get_next_changed(p->opts_cache, &changed)",
        "changed == &cached->user_shader_opts",
        "copy->source_path = talloc_strdup(copy, path)",
        "R08 live user shader PARAM update",
    ]
    for marker in required:
        if marker not in text:
            raise SystemExit(f"missing live PARAM marker after patch: {marker}")

    if "if (m_config_cache_update(p->opts_cache))" in text:
        raise SystemExit("generic gl_video option-cache reinit path still present")

    print("R08 live uniform PARAM patch applied")


if __name__ == "__main__":
    main()
