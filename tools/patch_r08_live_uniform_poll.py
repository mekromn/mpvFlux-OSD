#!/usr/bin/env python3
"""Make R08 resident PARAM consumption frame-authoritative.

The first R08 live-PARAM patch refreshed parsed hook copies when the primary
vo=gpu option cache reported glsl-shader-opts as changed. Pixel device proof
showed that Android/mpv property read-back could PASS while rendered pixels
still retained the old values. This second narrow patch removes that ambiguity:

* allocate an independent gl_video option cache used only as the authoritative
  live-PARAM source;
* poll that cache from the executing user hook before shader uniforms are
  emitted for the frame;
* refresh the already-parsed resident hook from the independent cache whenever
  the global option shadow changed;
* keep the primary renderer option cache/free-reinit logic unchanged from the
  first R08 patch.

The second cache is deliberate. m_config_cache_update() and
m_config_cache_get_next_changed() may not be mixed on one cache, so using a
separate cache lets the renderer retain fine-grained no-reinit handling while
also giving the actual executing hook a deterministic current-value source.
"""

from __future__ import annotations

import sys
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{path}: expected exactly one patch anchor, found {count}: {old[:120]!r}"
        )
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_r08_live_uniform_poll.py <mpv-source-dir>")

    root = Path(sys.argv[1])
    video = root / "video/out/gpu/video.c"
    if not video.is_file():
        raise SystemExit(f"not an mpv source tree: {root}")

    replace_once(
        video,
        """    struct gl_video_opts opts;\n    struct m_config_cache *opts_cache;\n    struct gl_lcms *cms;\n""",
        """    struct gl_video_opts opts;\n    struct m_config_cache *opts_cache;\n    // R08: independent cache polled by the executing resident user hook.\n    struct m_config_cache *shader_opts_cache;\n    struct gl_lcms *cms;\n""",
    )

    replace_once(
        video,
        """        .video_eq = mp_csp_equalizer_create(p, g),\n        .opts_cache = m_config_cache_alloc(p, g, &gl_video_conf),\n    };\n""",
        """        .video_eq = mp_csp_equalizer_create(p, g),\n        .opts_cache = m_config_cache_alloc(p, g, &gl_video_conf),\n        .shader_opts_cache = m_config_cache_alloc(p, g, &gl_video_conf),\n    };\n""",
    )

    replace_once(
        video,
        """static int update_user_shader_opts(struct gl_video *p,\n                                   struct gl_user_shader_hook *shader)\n{\n""",
        """static int update_user_shader_opts(struct gl_video *p,\n                                   char **user_shader_opts,\n                                   struct gl_user_shader_hook *shader)\n{\n""",
    )

    replace_once(
        video,
        """    if (!p->opts.user_shader_opts)\n        return 0;\n""",
        """    if (!user_shader_opts)\n        return 0;\n""",
    )

    replace_once(
        video,
        """    for (int n = 0; p->opts.user_shader_opts[n * 2]; n++) {\n        struct bstr key = bstr0(p->opts.user_shader_opts[n * 2 + 0]);\n        struct bstr val = bstr0(p->opts.user_shader_opts[n * 2 + 1]);\n""",
        """    for (int n = 0; user_shader_opts[n * 2]; n++) {\n        struct bstr key = bstr0(user_shader_opts[n * 2 + 0]);\n        struct bstr val = bstr0(user_shader_opts[n * 2 + 1]);\n""",
    )

    user_hook_anchor = """static void user_hook(struct gl_video *p, struct image img,\n                      struct gl_transform *trans, void *priv)\n{\n    struct gl_user_shader_hook *shader = priv;\n    mp_assert(shader);\n"""
    replace_once(
        video,
        user_hook_anchor,
        """static int refresh_live_user_shader_opts(struct gl_video *p);\n\n"""
        + user_hook_anchor
        + """\n    // Device-authoritative R08 path: the hook that is about to render the\n    // frame consumes the latest global glsl-shader-opts snapshot itself.\n    refresh_live_user_shader_opts(p);\n""",
    )

    old_refresh = """static int refresh_live_user_shader_opts(struct gl_video *p)\n{\n    // p->opts is normally refreshed only by reinit_from_options(). For the\n    // PARAM-only fast path copy just the key/value list pointer from the\n    // fine-grained option cache, then update the already-resident hook copies.\n    struct gl_video_opts *cached = p->opts_cache->opts;\n    p->opts.user_shader_opts = cached->user_shader_opts;\n\n    int matched = 0;\n    int user_hooks = 0;\n    for (int n = 0; n < p->num_tex_hooks; n++) {\n        struct tex_hook *hook = &p->tex_hooks[n];\n        if (hook->hook != user_hook || !hook->priv)\n            continue;\n        user_hooks++;\n        matched += update_user_shader_opts(p, hook->priv);\n    }\n\n    // Uniform values are uploaded by user_hook() when the next frame is built.\n    // Do not tear down rendering or rebuild the shader program. Trace-level\n    // telemetry is intentionally silent at normal log levels so dragging does\n    // not become a logging benchmark.\n    p->output_tex_valid = false;\n    MP_TRACE(p, \"R08 PARAM refresh matched=%d user_hooks=%d\\n\", matched, user_hooks);\n    return matched;\n}\n"""
    new_refresh = """static int refresh_live_user_shader_opts(struct gl_video *p)\n{\n    // Do not mix m_config_cache_update() with the fine-grained API used by the\n    // primary renderer cache. This independent cache tracks the same global\n    // gl_video options but is consumed only by resident PARAM rendering.\n    if (!m_config_cache_update(p->shader_opts_cache))\n        return 0;\n\n    struct gl_video_opts *cached = p->shader_opts_cache->opts;\n    int matched = 0;\n    int user_hooks = 0;\n    for (int n = 0; n < p->num_tex_hooks; n++) {\n        struct tex_hook *hook = &p->tex_hooks[n];\n        if (hook->hook != user_hook || !hook->priv)\n            continue;\n        user_hooks++;\n        matched += update_user_shader_opts(p, cached->user_shader_opts, hook->priv);\n    }\n\n    // The executing hook uploads these refreshed values as dynamic uniforms in\n    // the same frame. No shader source mutation or renderer teardown occurs.\n    p->output_tex_valid = false;\n    MP_TRACE(p, \"R08 PARAM frame refresh matched=%d user_hooks=%d\\n\",\n             matched, user_hooks);\n    return matched;\n}\n"""
    replace_once(video, old_refresh, new_refresh)

    replace_once(
        video,
        """    copy->source_path = talloc_strdup(copy, path);\n    update_user_shader_opts(p, copy);\n""",
        """    copy->source_path = talloc_strdup(copy, path);\n    update_user_shader_opts(p, p->opts.user_shader_opts, copy);\n""",
    )

    text = video.read_text()
    required = [
        "struct m_config_cache *shader_opts_cache;",
        ".shader_opts_cache = m_config_cache_alloc(p, g, &gl_video_conf)",
        "m_config_cache_update(p->shader_opts_cache)",
        "update_user_shader_opts(p, cached->user_shader_opts, hook->priv)",
        "refresh_live_user_shader_opts(p);",
        "R08 PARAM frame refresh matched=%d user_hooks=%d",
    ]
    for marker in required:
        if marker not in text:
            raise SystemExit(f"missing frame-authoritative PARAM marker: {marker}")

    print("R08 frame-authoritative live PARAM polling patch applied")


if __name__ == "__main__":
    main()
