#!/usr/bin/env python3
"""Patch the exact R07 vo=gpu + PARAM backport for true live uniform updates.

Upstream vo=gpu PARAM support stores user parameter values in each parsed hook,
but glsl-shader-opts changes normally flow through the generic renderer option
cache and call reinit_from_options(), which tears down rendering and rebuilds the
hook. That is acceptable for occasional option changes, but not for an
interactive Shader Lab.

R08 keeps the shader program resident. A glsl-shader-opts update now:
  * consumes fine-grained option-cache changes,
  * refreshes parameter values in already-parsed user hooks,
  * marks PARAM uniforms dynamic so Vulkan prefers push constants/global
    uniforms instead of repeatedly synchronizing mostly-static UBO storage,
  * invalidates the cached output frame,
  * avoids uninit_rendering()/shader-list churn for PARAM-only edits.

Any other vo=gpu option change still takes the normal full reinit path. If a
mixed update contains glsl-shader-opts as well, the newly rebuilt hook is also
refreshed from the authoritative option cache before rendering resumes.
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
        """static int update_user_shader_opts(struct gl_video *p,\n                                   struct gl_user_shader_hook *shader)\n{\n    int matched = 0;\n""",
    )

    replace_once(
        video,
        """    if (!p->opts.user_shader_opts)\n        return;\n\n    const char *basename = mp_basename(path);\n""",
        """    if (!p->opts.user_shader_opts)\n        return 0;\n\n    mp_assert(shader->source_path);\n    const char *basename = mp_basename(shader->source_path);\n""",
    )

    replace_once(
        video,
        """            parse_shader_param_value(p->log, param, val, &param->value);\n        }\n    }\n}\n\nstatic bool get_param_dynamic""",
        """            if (parse_shader_param_value(p->log, param, val, &param->value))\n                matched++;\n        }\n    }\n\n    return matched;\n}\n\nstatic bool get_param_dynamic""",
    )

    # Shader Lab changes these values continuously. The upstream initial PARAM
    # support treats them as ordinary uniforms because it assumes occasional
    # option changes followed by renderer reinit. R08 keeps the renderpass
    # resident, so explicitly select the shader-cache dynamic-uniform path.
    replace_once(
        video,
        """        switch (param->type) {\n        case GL_USER_SHADER_PARAM_FLOAT:\n            gl_sc_uniform_f_bstr(p->sc, param->name, value);\n            break;\n        case GL_USER_SHADER_PARAM_INT:\n            gl_sc_uniform_i_bstr(p->sc, param->name, lrint(value));\n            break;\n        }\n""",
        """        switch (param->type) {\n        case GL_USER_SHADER_PARAM_FLOAT:\n            gl_sc_uniform_dynamic(p->sc);\n            gl_sc_uniform_f_bstr(p->sc, param->name, value);\n            break;\n        case GL_USER_SHADER_PARAM_INT:\n            gl_sc_uniform_dynamic(p->sc);\n            gl_sc_uniform_i_bstr(p->sc, param->name, lrint(value));\n            break;\n        }\n""",
    )

    add_hook_anchor = """static bool add_user_hook(void *priv, const char *path,\n                          const struct gl_user_shader_hook *hook)\n{\n"""
    live_refresh = """static int refresh_live_user_shader_opts(struct gl_video *p)\n{\n    // p->opts is normally refreshed only by reinit_from_options(). For the\n    // PARAM-only fast path copy just the key/value list pointer from the\n    // fine-grained option cache, then update the already-resident hook copies.\n    struct gl_video_opts *cached = p->opts_cache->opts;\n    p->opts.user_shader_opts = cached->user_shader_opts;\n\n    int matched = 0;\n    int user_hooks = 0;\n    for (int n = 0; n < p->num_tex_hooks; n++) {\n        struct tex_hook *hook = &p->tex_hooks[n];\n        if (hook->hook != user_hook || !hook->priv)\n            continue;\n        user_hooks++;\n        matched += update_user_shader_opts(p, hook->priv);\n    }\n\n    // Uniform values are uploaded by user_hook() when the next frame is built.\n    // Do not tear down rendering or rebuild the shader program.\n    p->output_tex_valid = false;\n    MP_INFO(p, \"R08 PARAM refresh matched=%d user_hooks=%d\\n\", matched, user_hooks);\n    return matched;\n}\n\n""" + add_hook_anchor
    replace_once(video, add_hook_anchor, live_refresh)

    replace_once(
        video,
        """    struct gl_video *p = priv;\n    struct gl_user_shader_hook *copy = talloc_dup(p, (struct gl_user_shader_hook *)hook);\n    update_user_shader_opts(p, path, copy);\n    struct tex_hook texhook = {\n""",
        """    struct gl_video *p = priv;\n    struct gl_user_shader_hook *copy = talloc_dup(p, (struct gl_user_shader_hook *)hook);\n    copy->source_path = talloc_strdup(copy, path);\n    update_user_shader_opts(p, copy);\n    struct tex_hook texhook = {\n""",
    )

    replace_once(
        video,
        """static void gl_video_update_options(struct gl_video *p)\n{\n    if (m_config_cache_update(p->opts_cache)) {\n        gl_lcms_update_options(p->cms);\n        reinit_from_options(p);\n    }\n\n    if (mp_csp_equalizer_state_changed(p->video_eq))\n        p->output_tex_valid = false;\n}\n""",
        """static void gl_video_update_options(struct gl_video *p)\n{\n    bool full_reinit = false;\n    bool shader_params_changed = false;\n    void *changed = NULL;\n    struct gl_video_opts *cached = p->opts_cache->opts;\n\n    // Use fine-grained option updates so glsl-shader-opts can behave like a\n    // native live control. A PARAM-only change must not call uninit_rendering().\n    while (m_config_cache_get_next_changed(p->opts_cache, &changed)) {\n        if (changed == &cached->user_shader_opts)\n            shader_params_changed = true;\n        else\n            full_reinit = true;\n    }\n\n    if (full_reinit) {\n        MP_INFO(p, \"R08 full renderer option reinit\\n\");\n        gl_lcms_update_options(p->cms);\n        reinit_from_options(p);\n    }\n\n    // Refresh even after a mixed/full option update. reinit_from_options() has\n    // rebuilt the hook in that case, and this guarantees the authoritative\n    // glsl-shader-opts cache is applied to the new resident copy as well.\n    if (shader_params_changed)\n        refresh_live_user_shader_opts(p);\n\n    if (mp_csp_equalizer_state_changed(p->video_eq))\n        p->output_tex_valid = false;\n}\n""",
    )

    # Hard postconditions: fail the parity build instead of silently falling
    # back to renderer teardown semantics if upstream context ever drifts.
    text = video.read_text()
    required = [
        "refresh_live_user_shader_opts",
        "m_config_cache_get_next_changed(p->opts_cache, &changed)",
        "changed == &cached->user_shader_opts",
        "copy->source_path = talloc_strdup(copy, path)",
        "gl_sc_uniform_dynamic(p->sc)",
        "R08 PARAM refresh matched=%d user_hooks=%d",
        "R08 full renderer option reinit",
    ]
    for marker in required:
        if marker not in text:
            raise SystemExit(f"missing live PARAM marker after patch: {marker}")

    if "if (m_config_cache_update(p->opts_cache))" in text:
        raise SystemExit("generic gl_video option-cache reinit path still present")

    print("R08 live uniform PARAM patch applied")


if __name__ == "__main__":
    main()
