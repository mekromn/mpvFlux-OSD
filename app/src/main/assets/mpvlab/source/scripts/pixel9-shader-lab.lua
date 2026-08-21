-- Pixel 9 Pro XL - V3.1 Shader Lab Workstation v6.1 Studio
--
-- Proven foundation:
--   - vo=gpu
--   - Vulkan
--   - V3.1 perceptual expansion math
--   - current-mpv sampling helper: HOOKED_tex(HOOKED_pos)
--
-- Safety architecture:
--   * startup slot A is a known-good working shader
--   * this script performs NO shader mutation on startup
--   * live edits are rendered into the INACTIVE runtime slot
--   * only after the inactive file is completely written do we swap shaders
--   * if the swap command fails, the last known-good slot is restored
--   * A and B below are parameter banks, separate from runtime slot A/B

local mp = require("mp")
local msg = require("mp.msg")

local ROOT = "/storage/emulated/0/mpv"

local TEMPLATE_PATH = ROOT .. "/shaders/pixel9-perceptual-expansion-template.glsl.txt"
local SLOT_A = ROOT .. "/shaders/pixel9-perceptual-expansion-runtime-a.glsl"
local SLOT_B = ROOT .. "/shaders/pixel9-perceptual-expansion-runtime-b.glsl"

local STATE_PATH = ROOT .. "/state/pixel9-shader-lab-state.txt"
local VALUES_PATH = ROOT .. "/state/pixel9-shader-lab-values.txt"
local EXPORT_B_PATH = ROOT .. "/state/pixel9-shader-lab-B.glsl"

-- Two convenient virtual master controls are implemented by scaling existing
-- V3.1 numeric literals. They add NO new GLSL math or processing stages.
local items = {
    {group="DIAGNOSTIC", kind="virtual", key="SHADER_PROOF", label="Shader reload proof (MAGENTA)", d=0, min=0, max=1, steps={1,1,1}, fmt="%.0f", integer=true},
    {group="CONTROL", kind="granularity", key="TOUCH_GRANULARITY", label="Adjustment granularity", d=2, min=1, max=3, steps={1,1,1}, fmt="%.0f", integer=true},
    {group="MASTER", kind="virtual", key="LUMA_MASTER", label="Luma master", d=1.0, min=0.0, max=2.0, steps={0.0025,0.025,0.10}, fmt="%.4f"},
    {group="MASTER", kind="virtual", key="CHROMA_MASTER", label="Chroma master", d=1.0, min=0.0, max=3.0, steps={0.0025,0.025,0.10}, fmt="%.4f"},

    {group="MPV", kind="property", key="sdr-intensity", label="SDR intensity", d=4.16, min=0.10, max=12.0, steps={0.01,0.05,0.25}, fmt="%.2f"},
    {group="MPV", kind="property", key="brightness", label="mpv brightness", d=0, min=-100, max=100, steps={0.25,1,5}, fmt="%.2f"},
    {group="MPV", kind="property", key="contrast", label="mpv contrast", d=0, min=-100, max=100, steps={0.25,1,5}, fmt="%.2f"},
    {group="MPV", kind="property", key="gamma", label="mpv gamma", d=0, min=-100, max=100, steps={0.25,1,5}, fmt="%.2f"},
    {group="MPV", kind="property", key="saturation", label="mpv saturation", d=0, min=-100, max=100, steps={0.25,1,5}, fmt="%.2f"},
    {group="MPV", kind="property", key="hue", label="mpv hue", d=0, min=-100, max=100, steps={0.25,1,5}, fmt="%.2f"},

    {group="LUMA", kind="shader", key="LUMA_PIVOT", label="Luma pivot", d=0.18, min=0.05, max=0.50, steps={0.0005,0.0025,0.01}, fmt="%.4f"},
    {group="LUMA", kind="shader", key="LUMA_CONTRAST", label="Curve contrast", d=0.28, min=-1.0, max=1.5, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="LUMA", kind="shader", key="LUMA_HIGHLIGHT_START", label="Highlight gate start", d=0.22, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="LUMA", kind="shader", key="LUMA_HIGHLIGHT_END", label="Highlight gate full", d=0.92, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="LUMA", kind="shader", key="LUMA_HIGHLIGHT", label="Highlight lift", d=0.129, min=-0.5, max=1.0, steps={0.0005,0.0025,0.01}, fmt="%.4f"},

    {group="CHROMA GATES", kind="shader", key="SAT_L_FLOOR", label="Sat L floor", d=0.080, min=0.001, max=0.50, steps={0.0005,0.0025,0.01}, fmt="%.4f"},
    {group="CHROMA GATES", kind="shader", key="SAT_GATE_START", label="Sat gate start", d=0.025, min=0.0, max=1.0, steps={0.0005,0.0025,0.01}, fmt="%.4f"},
    {group="CHROMA GATES", kind="shader", key="SAT_GATE_FULL", label="Sat gate full", d=0.260, min=0.0, max=1.5, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="CHROMA GATES", kind="shader", key="SHADOW_GATE_START", label="Shadow gate start", d=0.025, min=0.0, max=0.50, steps={0.0005,0.0025,0.01}, fmt="%.4f"},
    {group="CHROMA GATES", kind="shader", key="SHADOW_GATE_FULL", label="Shadow gate full", d=0.120, min=0.0, max=0.75, steps={0.0005,0.0025,0.01}, fmt="%.4f"},

    {group="COLOR VOLUME", kind="shader", key="MIDTONE_START", label="Midtone start", d=0.10, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="COLOR VOLUME", kind="shader", key="MIDTONE_FULL", label="Midtone full", d=0.30, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="COLOR VOLUME", kind="shader", key="MIDTONE_FADE_START", label="Midtone fade start", d=0.56, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="COLOR VOLUME", kind="shader", key="MIDTONE_FADE_END", label="Midtone fade end", d=0.80, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="COLOR VOLUME", kind="shader", key="BRIGHT_START", label="Bright gate start", d=0.34, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="COLOR VOLUME", kind="shader", key="BRIGHT_FULL", label="Bright gate full", d=0.90, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="COLOR VOLUME", kind="shader", key="BASE_CHROMA", label="Base chroma", d=0.0129, min=-0.50, max=1.50, steps={0.00025,0.001,0.005}, fmt="%.5f"},
    {group="COLOR VOLUME", kind="shader", key="MID_CHROMA", label="Mid chroma", d=0.05375, min=-0.50, max=2.00, steps={0.00025,0.001,0.005}, fmt="%.5f"},
    {group="COLOR VOLUME", kind="shader", key="BRIGHT_CHROMA", label="Bright chroma", d=0.252625, min=-0.50, max=3.00, steps={0.0005,0.0025,0.01}, fmt="%.6f"},

    {group="SKIN", kind="shader", key="SKIN_RETAIN", label="Skin boost retained", d=0.22, min=0.0, max=1.0, steps={0.0025,0.01,0.05}, fmt="%.4f"},
    {group="SKIN", kind="shader", key="SKIN_CENTER", label="Skin hue center", d=0.87, min=-3.14159265, max=3.14159265, steps={0.0025,0.01,0.05}, fmt="%.4f"},
    {group="SKIN", kind="shader", key="SKIN_HUE_INNER", label="Skin hue inner", d=0.24, min=0.0, max=3.14159265, steps={0.0025,0.01,0.05}, fmt="%.4f"},
    {group="SKIN", kind="shader", key="SKIN_HUE_OUTER", label="Skin hue outer", d=0.72, min=0.0, max=3.14159265, steps={0.0025,0.01,0.05}, fmt="%.4f"},
    {group="SKIN", kind="shader", key="SKIN_L_LOW_START", label="Skin L low start", d=0.28, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="SKIN", kind="shader", key="SKIN_L_LOW_FULL", label="Skin L low full", d=0.46, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="SKIN", kind="shader", key="SKIN_L_HIGH_START", label="Skin L high start", d=0.82, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="SKIN", kind="shader", key="SKIN_L_HIGH_END", label="Skin L high end", d=0.96, min=0.0, max=1.0, steps={0.001,0.005,0.025}, fmt="%.4f"},
    {group="SKIN", kind="shader", key="SKIN_C_LOW_START", label="Skin C low start", d=0.018, min=0.0, max=0.50, steps={0.0005,0.0025,0.01}, fmt="%.4f"},
    {group="SKIN", kind="shader", key="SKIN_C_LOW_FULL", label="Skin C low full", d=0.050, min=0.0, max=0.50, steps={0.0005,0.0025,0.01}, fmt="%.4f"},
    {group="SKIN", kind="shader", key="SKIN_C_HIGH_START", label="Skin C high start", d=0.165, min=0.0, max=0.75, steps={0.0005,0.0025,0.01}, fmt="%.4f"},
    {group="SKIN", kind="shader", key="SKIN_C_HIGH_END", label="Skin C high end", d=0.255, min=0.0, max=0.75, steps={0.0005,0.0025,0.01}, fmt="%.4f"},

    {group="GAMUT", kind="shader", key="RGB_LOW", label="RGB low boundary", d=0.00005, min=0.0, max=0.02, steps={0.00001,0.00005,0.00025}, fmt="%.5f"},
    {group="GAMUT", kind="shader", key="RGB_HIGH", label="RGB high boundary", d=0.99995, min=0.98, max=1.0, steps={0.00001,0.00005,0.00025}, fmt="%.5f"},
    {group="GAMUT", kind="shader", key="GAMUT_MARGIN", label="Gamut margin", d=0.997, min=0.90, max=1.0, steps={0.0001,0.0005,0.0025}, fmt="%.4f"},
    {group="GAMUT", kind="shader", key="GAMUT_ITERATIONS", label="Gamut iterations", d=7, min=1, max=12, steps={1,1,1}, fmt="%.0f", integer=true},


    -- V6 workstation controls. Existing tuning keys above are unchanged.
    {group="OUTPUT", kind="shader", key="SDR_COMPRESS", label="HDR to SDR compression", d=0.0, min=0.0, max=1.0, steps={0.01,0.05,0.10}, fmt="%.3f", percent=true},

    {group="VIEW", kind="shader", key="DEBUG_VIEW", label="Clipping indicator", d=0, min=0, max=3, steps={1,1,1}, fmt="%.0f", integer=true, preset=false, choices={"OFF","GAMUT","LUMA","BOTH"}},
    {group="VIEW", kind="controller", key="GRAPH_VIEW", label="Curve graph", d=1, min=0, max=5, steps={1,1,1}, fmt="%.0f", integer=true, preset=false, choices={"OFF","AUTO","TONE","CHROMA","MORPH","HDR->SDR"}},

    {group="COMPARE", kind="action", key="BYPASS_ACTION", label="One-touch bypass comparison", d=0, min=0, max=0, steps={1,1,1}, fmt="%.0f", preset=false, action="bypass"},
    {group="COMPARE", kind="action", key="PREVIEW_ACTION", label="Original preview toggle (fallback)", d=0, min=0, max=0, steps={1,1,1}, fmt="%.0f", preset=false, action="preview-toggle"},

    {group="PRESETS", kind="controller", key="USER_SLOT", label="User preset slot", d=1, min=1, max=10, steps={1,1,1}, fmt="%.0f", integer=true, preset=false},
    {group="PRESETS", kind="action", key="LOAD_USER", label="Load selected user preset", d=0, min=0, max=0, steps={1,1,1}, fmt="%.0f", preset=false, action="load-user", destructive=true},
    {group="PRESETS", kind="action", key="SAVE_USER", label="Save current to user preset", d=0, min=0, max=0, steps={1,1,1}, fmt="%.0f", preset=false, action="save-user", destructive=true},
    {group="PRESETS", kind="action", key="CLEAR_USER", label="Clear selected user preset", d=0, min=0, max=0, steps={1,1,1}, fmt="%.0f", preset=false, action="clear-user", destructive=true},
    {group="PRESETS", kind="controller", key="BUILTIN_SLOT", label="Built-in preset", d=1, min=1, max=10, steps={1,1,1}, fmt="%.0f", integer=true, preset=false},
    {group="PRESETS", kind="action", key="LOAD_BUILTIN", label="Load selected built-in preset", d=0, min=0, max=0, steps={1,1,1}, fmt="%.0f", preset=false, action="load-builtin", destructive=true},

    {group="MORPH", kind="controller", key="MORPH_FROM", label="Morph from preset", d=1, min=1, max=20, steps={1,1,1}, fmt="%.0f", integer=true, preset=false},
    {group="MORPH", kind="controller", key="MORPH_TO", label="Morph to preset", d=2, min=1, max=20, steps={1,1,1}, fmt="%.0f", integer=true, preset=false},
    {group="MORPH", kind="morph", key="MORPH_AMOUNT", label="Preset morph", d=0.0, min=0.0, max=1.0, steps={0.01,0.05,0.10}, fmt="%.3f", percent=true, preset=false},

    {group="SYSTEM", kind="action", key="REVERT_VIDEO_START", label="Revert all to video-start state", d=0, min=0, max=0, steps={1,1,1}, fmt="%.0f", preset=false, action="revert-video-start", destructive=true},
    {group="SYSTEM", kind="action", key="RESET_ALL_MENU", label="Reset all tuning to V3.1 baseline", d=0, min=0, max=0, steps={1,1,1}, fmt="%.0f", preset=false, action="reset-all", destructive=true},
    {group="SYSTEM", kind="action", key="SAVE_STATE_MENU", label="Save complete Lab state", d=0, min=0, max=0, steps={1,1,1}, fmt="%.0f", preset=false, action="save-state"},
    {group="SYSTEM", kind="action", key="LOAD_STATE_MENU", label="Load complete Lab state", d=0, min=0, max=0, steps={1,1,1}, fmt="%.0f", preset=false, action="load-state", destructive=true},
}

local by_key = {}
local A, B = {}, {}

for i, it in ipairs(items) do
    it.index = i
    by_key[it.key] = it
    A[it.key] = it.d
    B[it.key] = it.d
end

local selected = 1
local edit_mode = false
local edit_original_value = nil
local edit_changed = false

-- Persistent Shader Lab UI. Visibility is the master input-enable switch.
local ui_visible = false
local ui_overlay = mp.create_osd_overlay("ass-events")
ui_overlay.res_x = 1280
ui_overlay.res_y = 720
ui_overlay.z = 1000

local graph_overlay = mp.create_osd_overlay("ass-events")
graph_overlay.res_x = 1280
graph_overlay.res_y = 720
graph_overlay.z = 999

-- v6.1.1 responsive ASS canvas.
-- res_x/res_y are ASS PlayResX/PlayResY for ass-events overlays.
-- Keep the layout at a stable 720 logical units high and adapt width
-- to the actual mpv OSD aspect ratio.
local LOGICAL_H = 720
local MIN_LOGICAL_W = 1280
local current_playres_x = 1280

local function sync_overlay_resolution()
    local ow, oh = mp.get_osd_size()
    if type(ow) ~= "number" or type(oh) ~= "number" or ow < 64 or oh < 64 then
        ow, oh = 1280, 720
    end

    local rx = math.floor(LOGICAL_H * (ow / oh) + 0.5)
    if rx < MIN_LOGICAL_W then rx = MIN_LOGICAL_W end

    current_playres_x = rx
    ui_overlay.res_x = rx
    ui_overlay.res_y = LOGICAL_H
    graph_overlay.res_x = rx
    graph_overlay.res_y = LOGICAL_H
end

sync_overlay_resolution()

local function publish_ui_visibility()
    mp.set_property("user-data/p9lab/ui-visible", ui_visible and "yes" or "no")
end

local function hide_ui_overlay()
    ui_overlay:remove()
    graph_overlay:remove()
end

local function overlay_escape(text)
    local escaped = mp.command_native({"escape-ass", tostring(text or "")})
    if not escaped then escaped = tostring(text or "") end
    return escaped:gsub("\n", "\\N")
end

local UI = {
    bg = "&H181818&",
    bg2 = "&H242424&",
    bg3 = "&H303030&",
    text = "&HF5F5F5&",
    muted = "&HB8B8B8&",
    accent = "&HFFBE50&",   -- RGB ~ 50/190/255, ASS is BGR
    accent2 = "&H8CFF65&",
    warn = "&H5656FF&",
    ok = "&H79E68B&",
}

local function ass_rect(x, y, w, h, color, alpha)
    return string.format(
        "{\\an7\\pos(%d,%d)\\p1\\bord0\\shad0\\1c%s\\1a&H%s&}m 0 0 l %d 0 l %d %d l 0 %d{\\p0}",
        x, y, color or UI.bg, alpha or "48", w, w, h, h
    )
end

local function ass_text(x, y, size, text, color, bold, align)
    return string.format(
        "{\\an%d\\pos(%d,%d)\\fs%d\\bord0.8\\shad0\\1c%s\\b%d}%s",
        align or 7, x, y, size, color or UI.text, bold and 1 or 0, overlay_escape(text)
    )
end

local function ass_line(x, y, w, h, color, alpha)
    return string.format(
        "{\\an7\\pos(%d,%d)\\p1\\bord0\\shad0\\1c%s\\1a&H%s&}m 0 0 l %d %d{\\p0}",
        x, y, color or UI.muted, alpha or "55", w, h
    )
end

local function render_persistent_ui(text)
    sync_overlay_resolution()
    if not ui_visible then
        hide_ui_overlay()
        return
    end
    local chunks = {
        ass_rect(30, 32, 660, 126, UI.bg, "35"),
        ass_rect(30, 32, 7, 126, UI.accent, "00"),
        ass_text(55, 50, 24, text, UI.text, false, 7),
    }
    ui_overlay.data = table.concat(chunks, "\n")
    ui_overlay:update()
end
local step_mode = 2 -- 1=fine, 2=normal, 3=coarse
local active_bank = "B"
local bypassed = false
local template_text = nil

-- mpv.conf always starts SDR with slot A.
local last_good_path = SLOT_A
local shader_apply_count = 0
local last_apply_slot = "STARTUP"

-- Workstation state. None of this changes existing A/B state-file compatibility.
local preview_active = false
local preview_was_bypassed = false
local video_start_snapshot = nil
local video_original_properties = {}
local confirmation_key = nil
local confirmation_deadline = 0
local confirmation_timer = nil
local nav_repeat_timer = nil
local nav_repeat_watchdog = nil
local user_slot_exists = {}

-- R07 observable native bridge state. The publisher is assigned after all
-- workstation helpers are defined; functions may safely reference it later.
local native_last_error = ""
local native_apply_busy = false
local native_serial = 0
local publish_native_state = function() end

local function clamp(v, lo, hi)
    if v < lo then return lo end
    if v > hi then return hi end
    return v
end

local function round_if_needed(v, integer)
    if integer then
        return math.floor(v + 0.5)
    end
    return v
end

local function bank()
    return active_bank == "A" and A or B
end

local function is_sdr()
    local g = mp.get_property("video-params/gamma", "") or ""
    return g ~= "" and g ~= "pq" and g ~= "hlg"
end

local function read_all(path)
    local f, err = io.open(path, "r")
    if not f then return nil, err end
    local s = f:read("*a")
    f:close()
    return s
end

local function write_all(path, data)
    local f, err = io.open(path, "w")
    if not f then return nil, err end

    local ok, werr = f:write(data)
    f:flush()
    f:close()

    if not ok then
        return nil, werr
    end
    return true
end

local function literal(it, value)
    if it and it.integer then
        return tostring(math.floor(value + 0.5))
    end
    return string.format("%.10g", value)
end

-- Keep smoothstep edge pairs and RGB boundaries ordered, avoiding undefined
-- edge ordering while tuning aggressively.
local ordered_pairs = {
    {"LUMA_HIGHLIGHT_START", "LUMA_HIGHLIGHT_END"},
    {"SAT_GATE_START", "SAT_GATE_FULL"},
    {"SHADOW_GATE_START", "SHADOW_GATE_FULL"},
    {"MIDTONE_START", "MIDTONE_FULL"},
    {"MIDTONE_FADE_START", "MIDTONE_FADE_END"},
    {"BRIGHT_START", "BRIGHT_FULL"},
    {"SKIN_HUE_INNER", "SKIN_HUE_OUTER"},
    {"SKIN_L_LOW_START", "SKIN_L_LOW_FULL"},
    {"SKIN_L_HIGH_START", "SKIN_L_HIGH_END"},
    {"SKIN_C_LOW_START", "SKIN_C_LOW_FULL"},
    {"SKIN_C_HIGH_START", "SKIN_C_HIGH_END"},
    {"RGB_LOW", "RGB_HIGH"},
}

local function enforce_order(values, changed_key)
    local gap = 0.000001

    for _, pair in ipairs(ordered_pairs) do
        local lo_key = pair[1]
        local hi_key = pair[2]
        local lo = values[lo_key]
        local hi = values[hi_key]

        if lo and hi and lo >= hi then
            if changed_key == lo_key then
                local it = by_key[lo_key]
                values[lo_key] = clamp(hi - gap, it.min, it.max)
            else
                local it = by_key[hi_key]
                values[hi_key] = clamp(lo + gap, it.min, it.max)
            end
        end
    end
end

local function effective_shader_value(values, key)
    local v = values[key]

    if key == "LUMA_CONTRAST" or key == "LUMA_HIGHLIGHT" then
        v = v * values["LUMA_MASTER"]
    elseif key == "BASE_CHROMA" or key == "MID_CHROMA" or key == "BRIGHT_CHROMA" then
        v = v * values["CHROMA_MASTER"]
    end

    return v
end

local function load_template()
    if template_text then
        return true
    end

    local err
    template_text, err = read_all(TEMPLATE_PATH)
    if not template_text then
        return nil, "Cannot read shader template: " .. tostring(err)
    end

    return true
end

local function generate_shader(values)
    local ok, err = load_template()
    if not ok then
        return nil, err
    end

    local out = template_text

    for key, it in pairs(by_key) do
        if it.kind == "shader" then
            local token = "@@" .. key .. "@@"
            local value = effective_shader_value(values, key)
            local replacement = literal(it, value)
            local count

            out, count = out:gsub(token, replacement)

            if count ~= 1 then
                return nil, "Template token count for " .. key .. " = " .. tostring(count)
            end
        end
    end

    if out:find("@@", 1, true) then
        return nil, "Unresolved template token remains"
    end

    -- Hard binary proof of the runtime shader reload path.
    -- This is diagnostic only and does not change normal shader math.
    if values["SHADER_PROOF"] and values["SHADER_PROOF"] >= 0.5 then
        local proof_count
        out, proof_count = out:gsub(
            "return vec4%(outRGB, src%.a%);",
            "return vec4(1.0, 0.0, 1.0, src.a);",
            1
        )
        if proof_count ~= 1 then
            return nil, "Could not inject MAGENTA shader proof"
        end
    end

    return out
end

local function remove_slot(path)
    -- An absent path may report an error. That is harmless here.
    mp.command_native({"change-list", "glsl-shaders", "remove", path}, {})
end

local function append_slot(path)
    local _, err = mp.command_native(
        {"change-list", "glsl-shaders", "append", path},
        nil
    )

    if err then
        return nil, err
    end
    return true
end

local function swap_to(path)
    local previous = last_good_path

    -- Remove only our two runtime shaders. Other user shaders are untouched.
    remove_slot(SLOT_A)
    remove_slot(SLOT_B)

    local ok, err = append_slot(path)
    if not ok then
        msg.error("Shader swap failed: " .. tostring(err))

        -- Best-effort restoration of the last shader known to have worked.
        local rok, rerr = append_slot(previous)
        if not rok then
            msg.error("Shader fallback also failed: " .. tostring(rerr))
        end

        return nil, err
    end

    last_good_path = path
    bypassed = false
    return true
end

local function apply_shader(values)
    if not is_sdr() then
        return nil, "Shader Lab is SDR-only; current source is HDR or not ready"
    end

    local target = (last_good_path == SLOT_A) and SLOT_B or SLOT_A
    local data, err = generate_shader(values)

    if not data then
        return nil, err
    end

    -- Critical safety property: target is the inactive slot. The shader
    -- currently visible on screen is not overwritten.
    local ok, werr = write_all(target, data)
    if not ok then
        return nil, "Unable to write inactive shader slot: " .. tostring(werr)
    end

    local sok, serr = swap_to(target)
    if not sok then
        return nil, "Unable to activate generated shader: " .. tostring(serr)
    end

    shader_apply_count = shader_apply_count + 1
    last_apply_slot = (target == SLOT_A) and "A" or "B"

    return true, target
end

local unsupported_properties = {}

local function apply_properties(values)
    if not is_sdr() then
        return nil, "Shader Lab is SDR-only; current source is HDR or not ready"
    end

    for _, it in ipairs(items) do
        if it.kind == "property" and not unsupported_properties[it.key] then
            local ok, err = mp.set_property_number(it.key, values[it.key])
            if not ok then
                unsupported_properties[it.key] = true
                msg.warn("Shader Lab property unavailable in this mpv build: " .. it.key .. " (" .. tostring(err) .. ")")
            end
        end
    end

    return true
end

local function apply_all()
    local values = bank()

    local ok, err = apply_shader(values)
    if not ok then
        mp.osd_message("Shader Lab error:\n" .. tostring(err), 4)
        return false
    end

    local pok, perr = apply_properties(values)
    if not pok then
        mp.osd_message("Shader Lab property error:\n" .. tostring(perr), 4)
        return false
    end

    return true
end

local function current_item()
    return items[selected]
end

local function fmt(it, v)
    if it.percent then
        return string.format("%.0f%%", (v or 0) * 100.0)
    end
    return string.format(it.fmt or "%.4f", v or 0)
end

local function wrap_index(i)
    if i < 1 then return #items end
    if i > #items then return 1 end
    return i
end

local function short_label(i)
    return items[wrap_index(i)].label
end

local function granularity_name()
    if step_mode == 1 then return "FINE" end
    if step_mode == 2 then return "NORMAL" end
    return "COARSE"
end

local function sync_granularity_item()
    A["TOUCH_GRANULARITY"] = step_mode
    B["TOUCH_GRANULARITY"] = step_mode
end

local function copy_table(src)
    local out = {}
    for k, v in pairs(src or {}) do out[k] = v end
    return out
end

local function copy_into(dst, src)
    for k in pairs(dst) do dst[k] = nil end
    for k, v in pairs(src or {}) do dst[k] = v end
end

local function is_preset_item(it)
    if it.preset == false then return false end
    if it.key == "SHADER_PROOF" or it.key == "TOUCH_GRANULARITY" then return false end
    return it.kind == "shader" or it.kind == "property" or it.kind == "virtual"
end

local tuning_keys = {}
for _, it in ipairs(items) do
    if is_preset_item(it) then tuning_keys[#tuning_keys + 1] = it.key end
end

local baseline_values = {}
for _, key in ipairs(tuning_keys) do baseline_values[key] = by_key[key].d end

local function preset_from_overrides(name, overrides)
    local p = {name = name, values = copy_table(baseline_values)}
    for k, v in pairs(overrides or {}) do
        if by_key[k] and is_preset_item(by_key[k]) then p.values[k] = v end
    end
    enforce_order(p.values, "")
    return p
end

-- Ten read-only built-in starting points. They never overwrite user slots.
local builtin_presets = {
    preset_from_overrides("V3.1 Reference", {}),
    preset_from_overrides("Natural Plus", {
        LUMA_MASTER=1.02, CHROMA_MASTER=1.04,
    }),
    preset_from_overrides("Vivid Clean", {
        LUMA_MASTER=1.04, CHROMA_MASTER=1.10,
        MID_CHROMA=0.058, BRIGHT_CHROMA=0.275, SKIN_RETAIN=0.18,
    }),
    preset_from_overrides("Cinema", {
        LUMA_MASTER=1.06, CHROMA_MASTER=0.98,
        LUMA_CONTRAST=0.31, LUMA_HIGHLIGHT=0.115,
        MID_CHROMA=0.050, BRIGHT_CHROMA=0.22,
    }),
    preset_from_overrides("Daylight Punch", {
        LUMA_MASTER=1.12, CHROMA_MASTER=1.06,
        LUMA_CONTRAST=0.30, LUMA_HIGHLIGHT=0.15,
        BRIGHT_CHROMA=0.27,
    }),
    preset_from_overrides("Dark Room", {
        LUMA_MASTER=0.86, CHROMA_MASTER=0.96,
        LUMA_CONTRAST=0.24, LUMA_HIGHLIGHT=0.085,
        BRIGHT_CHROMA=0.20,
    }),
    preset_from_overrides("Animation", {
        LUMA_MASTER=1.04, CHROMA_MASTER=1.18,
        BASE_CHROMA=0.015, MID_CHROMA=0.065,
        BRIGHT_CHROMA=0.30, SKIN_RETAIN=0.35,
    }),
    preset_from_overrides("Skin Priority", {
        CHROMA_MASTER=1.06, SKIN_RETAIN=0.08,
        SKIN_HUE_OUTER=0.82, BRIGHT_CHROMA=0.24,
    }),
    preset_from_overrides("Highlight Pop", {
        LUMA_MASTER=1.10, CHROMA_MASTER=1.08,
        LUMA_HIGHLIGHT=0.16, BRIGHT_CHROMA=0.31,
        BRIGHT_START=0.30,
    }),
    preset_from_overrides("SDR Safe", {
        LUMA_MASTER=0.98, CHROMA_MASTER=0.98,
        SDR_COMPRESS=0.70, GAMUT_MARGIN=0.995,
    }),
}

local USER_PRESET_PREFIX = ROOT .. "/state/pixel9-user-preset-"
local user_slot_names = {}

local function user_preset_path(slot)
    return USER_PRESET_PREFIX .. string.format("%02d", slot) .. ".txt"
end

local function atomic_write(path, data)
    local tmp = path .. ".tmp"
    local ok, err = write_all(tmp, data)
    if not ok then return nil, err end
    os.remove(path)
    local rok, rerr = os.rename(tmp, path)
    if not rok then
        os.remove(tmp)
        return nil, rerr or "rename failed"
    end
    return true
end

local function parse_preset_file(path)
    local f = io.open(path, "r")
    if not f then return nil end
    local values = {}
    local name = nil
    for line in f:lines() do
        local n = line:match("^name=(.*)$")
        if n then
            name = n
        else
            local key, value = line:match("^([%w_%-]+)=([%+%-%.%deE]+)$")
            local it = key and by_key[key] or nil
            local num = tonumber(value)
            if it and is_preset_item(it) and num then
                values[key] = round_if_needed(clamp(num, it.min, it.max), it.integer)
            end
        end
    end
    f:close()
    if next(values) == nil then return nil end
    for _, key in ipairs(tuning_keys) do
        if values[key] == nil then values[key] = baseline_values[key] end
    end
    enforce_order(values, "")
    return {name = name, values = values}
end

local function scan_user_slots()
    for i = 1, 10 do
        local p = parse_preset_file(user_preset_path(i))
        user_slot_exists[i] = p ~= nil
        user_slot_names[i] = p and p.name or nil
    end
end

local function preset_ref_name(ref)
    ref = math.floor((tonumber(ref) or 1) + 0.5)
    if ref <= 10 then
        local p = builtin_presets[clamp(ref, 1, 10)]
        return string.format("B%02d %s", ref, p.name)
    end
    local slot = clamp(ref - 10, 1, 10)
    if user_slot_exists[slot] then
        return string.format("U%02d %s", slot, user_slot_names[slot] or "Saved")
    end
    return string.format("U%02d EMPTY", slot)
end

local function preset_ref_values(ref)
    ref = math.floor((tonumber(ref) or 1) + 0.5)
    if ref <= 10 then return copy_table(builtin_presets[clamp(ref,1,10)].values) end
    local slot = clamp(ref - 10, 1, 10)
    local p = parse_preset_file(user_preset_path(slot))
    if not p then return nil, "User preset " .. tostring(slot) .. " is empty" end
    return copy_table(p.values)
end

local function apply_tuning_values(values)
    active_bank = "B"
    for _, key in ipairs(tuning_keys) do
        local it = by_key[key]
        local v = values[key]
        if v ~= nil then
            B[key] = round_if_needed(clamp(v, it.min, it.max), it.integer)
        end
    end
    enforce_order(B, "")
    bypassed = false
    return apply_all()
end

local function save_user_preset(slot)
    slot = clamp(math.floor(slot + 0.5), 1, 10)
    local lines = {
        "# Pixel 9 Shader Lab user preset v6",
        "name=User " .. tostring(slot),
    }
    for _, key in ipairs(tuning_keys) do
        lines[#lines + 1] = key .. "=" .. string.format("%.17g", B[key])
    end
    local ok, err = atomic_write(user_preset_path(slot), table.concat(lines, "\n") .. "\n")
    if not ok then
        mp.osd_message("Preset save failed:\n" .. tostring(err), 4)
        return false
    end
    user_slot_exists[slot] = true
    user_slot_names[slot] = "User " .. tostring(slot)
    mp.osd_message("Saved USER " .. tostring(slot), 2)
    return true
end

local function load_user_preset(slot)
    slot = clamp(math.floor(slot + 0.5), 1, 10)
    local p = parse_preset_file(user_preset_path(slot))
    if not p then
        mp.osd_message("USER " .. tostring(slot) .. " is empty", 2)
        return false
    end
    if apply_tuning_values(p.values) then
        mp.osd_message("Loaded USER " .. tostring(slot), 2)
        return true
    end
    return false
end

local function clear_user_preset(slot)
    slot = clamp(math.floor(slot + 0.5), 1, 10)
    os.remove(user_preset_path(slot))
    user_slot_exists[slot] = false
    user_slot_names[slot] = nil
    mp.osd_message("Cleared USER " .. tostring(slot), 2)
    return true
end

local function load_builtin_preset(slot)
    slot = clamp(math.floor(slot + 0.5), 1, 10)
    local p = builtin_presets[slot]
    if apply_tuning_values(p.values) then
        mp.osd_message("Loaded " .. p.name, 2)
        return true
    end
    return false
end

local function smoothstep(edge0, edge1, x)
    if edge1 <= edge0 then return x >= edge1 and 1.0 or 0.0 end
    local t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
end

local function tone_curve_value(values, y, force_compress)
    y = clamp(y, 0.0, 1.0)
    local pivot = values.LUMA_PIVOT
    local contrast = effective_shader_value(values, "LUMA_CONTRAST")
    local hi = effective_shader_value(values, "LUMA_HIGHLIGHT")
    local contrast_term = contrast * (y - pivot) * y * (1.0 - y)
    local hi_gate = smoothstep(values.LUMA_HIGHLIGHT_START, values.LUMA_HIGHLIGHT_END, y)
    local hi_term = hi * hi_gate * y * (1.0 - y)
    local tuned = clamp(y + contrast_term + hi_term, 0.0, 1.0)
    local c = force_compress
    if c == nil then c = values.SDR_COMPRESS or 0.0 end
    return tuned * (1.0 - c) + y * c
end

local function chroma_curve_value(values, y, force_compress)
    y = clamp(y, 0.0, 1.0)
    local shadow = smoothstep(values.SHADOW_GATE_START, values.SHADOW_GATE_FULL, y)
    local mid = smoothstep(values.MIDTONE_START, values.MIDTONE_FULL, y) *
        (1.0 - smoothstep(values.MIDTONE_FADE_START, values.MIDTONE_FADE_END, y))
    local bright = smoothstep(values.BRIGHT_START, values.BRIGHT_FULL, y)
    local boost = effective_shader_value(values, "BASE_CHROMA") +
        effective_shader_value(values, "MID_CHROMA") * mid +
        effective_shader_value(values, "BRIGHT_CHROMA") * bright
    local scale = 1.0 + boost * shadow
    local c = force_compress
    if c == nil then c = values.SDR_COMPRESS or 0.0 end
    return 1.0 + (scale - 1.0) * (1.0 - c)
end

local function ass_path(points, w, h, y_min, y_max)
    local parts = {}
    for i, p in ipairs(points) do
        local x = clamp(p[1], 0, 1) * w
        local yn = (p[2] - y_min) / math.max(y_max - y_min, 0.000001)
        local y = h - clamp(yn, 0, 1) * h
        parts[#parts + 1] = (i == 1 and "m" or "l") .. string.format(" %.1f %.1f", x, y)
    end
    return table.concat(parts, " ")
end

local function sample_curve(fn, n)
    local pts = {}
    for i = 0, n do
        local x = i / n
        pts[#pts + 1] = {x, fn(x)}
    end
    return pts
end

local function effective_graph_mode()
    local mode = math.floor((B.GRAPH_VIEW or 1) + 0.5)
    if mode ~= 1 then return mode end
    local group = current_item().group
    if group == "LUMA" then return 2 end
    if group == "CHROMA GATES" or group == "COLOR VOLUME" or group == "SKIN" or group == "GAMUT" then return 3 end
    if group == "MORPH" or group == "PRESETS" then return 4 end
    if group == "OUTPUT" then return 5 end
    return 2
end

local function render_graph()
    sync_overlay_resolution()
    if not ui_visible or preview_active then graph_overlay:remove(); return end
    local mode = effective_graph_mode()
    if mode == 0 then graph_overlay:remove(); return end

    local w, h = 360, 210
    local x0, y0 = 850, 300
    local axis = "m 0 210 l 360 210 m 0 210 l 0 0"
    local identity = "m 0 210 l 360 0"
    local title = "TONE"
    local paths = {}
    local y_min, y_max = 0.0, 1.0

    if mode == 2 then
        title = "TONE CURVE"
        paths[1] = ass_path(sample_curve(function(x) return tone_curve_value(B, x) end, 72), w, h, 0, 1)
    elseif mode == 3 then
        title = "CHROMA / COLOR VOLUME"
        y_min, y_max = 0.9, 1.45
        identity = ass_path({{0,1},{1,1}}, w, h, y_min, y_max)
        paths[1] = ass_path(sample_curve(function(x) return chroma_curve_value(B, x) end, 72), w, h, y_min, y_max)
    elseif mode == 4 then
        title = "PRESET MORPH"
        local av = preset_ref_values(B.MORPH_FROM or 1)
        local bv = preset_ref_values(B.MORPH_TO or 2)
        if av and bv then
            paths[1] = ass_path(sample_curve(function(x) return tone_curve_value(av, x) end, 56), w, h, 0, 1)
            paths[2] = ass_path(sample_curve(function(x) return tone_curve_value(bv, x) end, 56), w, h, 0, 1)
            paths[3] = ass_path(sample_curve(function(x) return tone_curve_value(B, x) end, 56), w, h, 0, 1)
        else
            paths[1] = identity
        end
    else
        title = "HDR -> SDR COMPRESSION"
        paths[1] = ass_path(sample_curve(function(x) return tone_curve_value(B, x, 0.0) end, 72), w, h, 0, 1)
        paths[2] = ass_path(sample_curve(function(x) return tone_curve_value(B, x) end, 72), w, h, 0, 1)
    end

    local chunks = {
        ass_rect(820, 235, 410, 330, UI.bg, "38"),
        ass_rect(820, 235, 410, 6, UI.accent, "00"),
        ass_text(845, 255, 22, title, UI.text, true, 7),
        ass_text(845, 278, 15, "INPUT  ->  OUTPUT", UI.muted, false, 7),
    }

    -- subtle 4x4 grid
    for i = 1, 3 do
        local gx = x0 + math.floor(w * i / 4)
        local gy = y0 + math.floor(h * i / 4)
        chunks[#chunks+1] = ass_line(gx, y0, 0, h, UI.muted, "B5")
        chunks[#chunks+1] = ass_line(x0, gy, w, 0, UI.muted, "B5")
    end

    chunks[#chunks+1] = string.format("{\\an7\\pos(%d,%d)\\p1\\bord1\\shad0\\1c%s\\1a&H55&}%s{\\p0}", x0, y0, UI.muted, axis)
    chunks[#chunks+1] = string.format("{\\an7\\pos(%d,%d)\\p1\\bord1\\shad0\\1c%s\\1a&H8A&}%s{\\p0}", x0, y0, UI.muted, identity)

    if paths[1] then chunks[#chunks+1] = string.format("{\\an7\\pos(%d,%d)\\p1\\bord2\\shad0\\1c%s\\1a&H00&}%s{\\p0}", x0, y0, UI.accent, paths[1]) end
    if paths[2] then chunks[#chunks+1] = string.format("{\\an7\\pos(%d,%d)\\p1\\bord1\\shad0\\1c%s\\1a&H55&}%s{\\p0}", x0, y0, UI.accent2, paths[2]) end
    if paths[3] then chunks[#chunks+1] = string.format("{\\an7\\pos(%d,%d)\\p1\\bord2\\shad0\\1c%s\\1a&H00&}%s{\\p0}", x0, y0, UI.text, paths[3]) end

    chunks[#chunks+1] = ass_text(x0, y0 + h + 10, 14, "0", UI.muted, false, 7)
    chunks[#chunks+1] = ass_text(x0 + w, y0 + h + 10, 14, "1", UI.muted, false, 9)
    graph_overlay.data = table.concat(chunks, "\n")
    graph_overlay:update()
end

local function percent_delta(it, value)
    if math.abs(it.d or 0) < 0.000000001 then return nil end
    return ((value / it.d) - 1.0) * 100.0
end

local function display_value(it)
    if it.kind == "action" then return "PRESS CENTER" end
    local value = B[it.key]
    if it.key == "TOUCH_GRANULARITY" then return granularity_name() end
    if it.key == "USER_SLOT" then
        local slot = math.floor(value + 0.5)
        return string.format("USER %02d  %s", slot, user_slot_exists[slot] and "SAVED" or "EMPTY")
    end
    if it.key == "BUILTIN_SLOT" then
        local slot = clamp(math.floor(value + 0.5),1,10)
        return string.format("B%02d %s", slot, builtin_presets[slot].name)
    end
    if it.key == "MORPH_FROM" or it.key == "MORPH_TO" then
        return preset_ref_name(value)
    end
    if it.choices then
        local idx = math.floor(value + 0.5) - (it.min or 0) + 1
        return it.choices[clamp(idx,1,#it.choices)]
    end
    return fmt(it, value)
end

local function cancel_confirmation()
    confirmation_key = nil
    confirmation_deadline = 0
    if confirmation_timer then confirmation_timer:kill(); confirmation_timer = nil end
end

local function unique_groups()
    local out, seen = {}, {}
    for _, it in ipairs(items) do
        if not seen[it.group] then
            seen[it.group] = true
            out[#out+1] = it.group
        end
    end
    return out
end

local groups_cache = unique_groups()

local function group_position(name)
    for i, g in ipairs(groups_cache) do if g == name then return i end end
    return 1
end

local function item_position_in_group(index)
    local g = items[index].group
    local n, pos = 0, 0
    for i, it in ipairs(items) do
        if it.group == g then
            n = n + 1
            if i == index then pos = n end
        end
    end
    return pos, n
end

local function normalized_value(it)
    if it.kind == "action" then return 0.5 end
    local lo, hi = it.min or 0, it.max or 1
    if hi <= lo then return 0.5 end
    return clamp(((B[it.key] or lo) - lo) / (hi - lo), 0, 1)
end

local function render_slider(chunks, it, x, y, w)
    if it.kind == "action" then return end
    local n = normalized_value(it)
    chunks[#chunks+1] = ass_rect(x, y, w, 9, UI.bg3, "25")
    chunks[#chunks+1] = ass_rect(x, y, math.max(5, math.floor(w*n)), 9, UI.accent, "00")
    local knob = x + math.floor(w*n)
    chunks[#chunks+1] = ass_rect(knob-3, y-4, 6, 17, UI.text, "00")
end

local function render_preview_badge()
    sync_overlay_resolution()
    graph_overlay:remove()
    local chunks = {
        ass_rect(36, 34, 250, 58, UI.bg, "30"),
        ass_rect(36, 34, 7, 58, UI.accent2, "00"),
        ass_text(58, 49, 21, "ORIGINAL  |  HOLD", UI.text, true, 7),
    }
    ui_overlay.data = table.concat(chunks, "\n")
    ui_overlay:update()
end

local function show(applied)
    sync_overlay_resolution()
    if not ui_visible then return end
    if preview_active then render_preview_badge(); return end

    local it = current_item()
    local state = bypassed and "BYPASS" or ("BANK " .. active_bank)
    local group_pos = group_position(it.group)
    local item_pos, item_count = item_position_in_group(selected)
    local prev_group = groups_cache[((group_pos - 2) % #groups_cache) + 1]
    local next_group_name = groups_cache[(group_pos % #groups_cache) + 1]

    if confirmation_key == it.key and mp.get_time() <= confirmation_deadline then
        local chunks = {
            ass_rect(305, 178, 670, 350, UI.bg, "22"),
            ass_rect(305, 178, 8, 350, UI.warn, "00"),
            ass_text(350, 220, 20, "CONFIRM DESTRUCTIVE ACTION", UI.warn, true, 7),
            ass_text(350, 267, 30, it.label, UI.text, true, 7),
            ass_text(350, 325, 19, "Press CENTER / OK again to confirm", UI.text, false, 7),
            ass_text(350, 360, 17, "LEFT / RIGHT / BACK cancels", UI.muted, false, 7),
            ass_rect(350, 418, 250, 58, UI.warn, "55"),
            ass_text(475, 433, 20, "OK  CONFIRM", UI.text, true, 8),
            ass_rect(625, 418, 250, 58, UI.bg3, "25"),
            ass_text(750, 433, 20, "CANCEL", UI.text, true, 8),
        }
        ui_overlay.data = table.concat(chunks, "\n")
        ui_overlay:update()
        graph_overlay:remove()
        return
    end

    local chunks = {
        -- main studio panel
        ass_rect(28, 35, 750, 650, UI.bg, "42"),
        ass_rect(28, 35, 8, 650, UI.accent, "00"),
        ass_text(55, 54, 17, "PIXEL 9 PRO XL", UI.muted, true, 7),
        ass_text(55, 80, 31, "SHADER LAB", UI.text, true, 7),
        ass_text(55, 117, 16, "WORKSTATION v6.1.1 STUDIO", UI.accent, true, 7),

        -- state badges
        ass_rect(545, 55, 105, 34, bypassed and UI.warn or UI.bg3, "20"),
        ass_text(597, 63, 15, state, UI.text, true, 8),
        ass_rect(660, 55, 92, 34, UI.bg3, "20"),
        ass_text(706, 63, 15, granularity_name(), UI.text, true, 8),

        -- group navigation strip
        ass_text(55, 157, 14, "GROUP", UI.muted, true, 7),
        ass_text(112, 157, 15, "< " .. prev_group, UI.muted, false, 7),
        ass_rect(265, 145, 270, 38, UI.bg3, "20"),
        ass_text(400, 153, 18, it.group .. string.format("  %d/%d", group_pos, #groups_cache), UI.accent, true, 8),
        ass_text(555, 157, 15, next_group_name .. " >", UI.muted, false, 7),

        -- previous/current/next item stack
        ass_text(58, 215, 16, "PREV", UI.muted, true, 7),
        ass_text(115, 215, 17, short_label(selected-1), UI.muted, false, 7),
        ass_rect(52, 250, 700, 180, UI.bg2, "28"),
        ass_rect(52, 250, 6, 180, edit_mode and UI.accent2 or UI.accent, "00"),
        ass_text(78, 269, 15, edit_mode and "EDITING" or "SELECTED", edit_mode and UI.accent2 or UI.accent, true, 7),
        ass_text(78, 300, 27, it.label, UI.text, true, 7),
        ass_text(78, 344, 38, display_value(it), UI.text, true, 7),
        ass_text(665, 273, 14, string.format("%d/%d", item_pos, item_count), UI.muted, true, 9),
        ass_text(58, 452, 16, "NEXT", UI.muted, true, 7),
        ass_text(115, 452, 17, short_label(selected+1), UI.muted, false, 7),
    }

    render_slider(chunks, it, 80, 401, 640)

    if applied then
        chunks[#chunks+1] = ass_text(735, 445, 14, string.format("GLSL #%d / %s", shader_apply_count, last_apply_slot), UI.ok, true, 9)
    elseif it.kind == "property" then
        chunks[#chunks+1] = ass_text(735, 445, 14, "MPV LIVE", UI.ok, true, 9)
    end

    local base_extra = ""
    if edit_mode and (it.kind == "shader" or it.kind == "property" or it.kind == "virtual") then
        local pct = percent_delta(it, B[it.key])
        if pct then base_extra = string.format("Baseline %s   |   %+0.2f%%", fmt(it, it.d), pct) end
    elseif it.kind == "action" then
        base_extra = "CENTER / OK activates"
    elseif it.choices then
        base_extra = "Discrete option"
    else
        base_extra = edit_mode and "LEFT -   RIGHT +   CENTER done" or "CENTER / OK to edit"
    end
    chunks[#chunks+1] = ass_text(78, 425, 14, base_extra, UI.muted, false, 7)

    -- control legend optimized for touch + TV remote
    chunks[#chunks+1] = ass_rect(52, 500, 700, 145, UI.bg2, "32")
    if edit_mode then
        chunks[#chunks+1] = ass_text(75, 520, 17, "EDIT", UI.accent2, true, 7)
        chunks[#chunks+1] = ass_text(130, 520, 17, "LEFT  -      CENTER/OK  DONE      RIGHT  +", UI.text, false, 7)
        chunks[#chunks+1] = ass_text(75, 555, 17, "STEP", UI.accent2, true, 7)
        chunks[#chunks+1] = ass_text(130, 555, 17, "UP  COARSER      DOWN  FINER", UI.text, false, 7)
    else
        chunks[#chunks+1] = ass_text(75, 520, 17, "NAV", UI.accent, true, 7)
        chunks[#chunks+1] = ass_text(130, 520, 17, "LEFT/RIGHT  ITEM      CENTER/OK  SELECT", UI.text, false, 7)
        chunks[#chunks+1] = ass_text(75, 555, 17, "GROUP", UI.accent, true, 7)
        chunks[#chunks+1] = ass_text(130, 555, 17, "UP  PREVIOUS      DOWN  NEXT", UI.text, false, 7)
    end
    chunks[#chunks+1] = ass_text(75, 598, 15, "TOUCH: hold LEFT/RIGHT = fast menu   |   hold CENTER = original", UI.muted, false, 7)
    chunks[#chunks+1] = ass_text(75, 622, 15, "TV: D-pad + OK   |   MENU toggles Lab   |   BACK closes Lab", UI.muted, false, 7)

    ui_overlay.data = table.concat(chunks, "\n")
    ui_overlay:update()
    render_graph()
end

local function activate_B_if_needed()
    if active_bank ~= "B" then active_bank = "B"; return true end
    return false
end

local function move_selection(direction, quiet)
    cancel_confirmation()
    selected = wrap_index(selected + direction)
    if not quiet then show(false) else show(false) end
end

local function next_group(direction)
    cancel_confirmation()
    direction = direction or 1
    local start_group = current_item().group
    local i = selected
    repeat
        i = wrap_index(i + direction)
    until items[i].group ~= start_group or i == selected
    selected = i
    show(false)
end

local function cycle_step(direction)
    step_mode = step_mode + (direction or 1)
    if step_mode > 3 then step_mode = 1 end
    if step_mode < 1 then step_mode = 3 end
    sync_granularity_item()
    show(false)
end

local function set_original_properties()
    for _, it in ipairs(items) do
        if it.kind == "property" then
            local v = video_original_properties[it.key]
            if v ~= nil and not unsupported_properties[it.key] then
                local ok = mp.set_property_number(it.key, v)
                if not ok then unsupported_properties[it.key] = true end
            end
        end
    end
end

local function enter_original_view()
    remove_slot(SLOT_A)
    remove_slot(SLOT_B)
    set_original_properties()
end

local function toggle_bypass()
    if not is_sdr() then mp.osd_message("Shader Lab bypass is SDR-only", 2); return false end
    if preview_active then return false end
    if not bypassed then
        enter_original_view()
        bypassed = true
        mp.osd_message("BYPASS: original SDR comparison", 1.5)
        show(false)
        return true
    end
    bypassed = false
    if apply_all() then show(true); return true end
    return false
end

local function preview_start()
    if preview_active or not is_sdr() then return false end
    preview_was_bypassed = bypassed
    preview_active = true
    if not bypassed then enter_original_view() end
    show(false)
    return true
end

local function preview_end()
    if not preview_active then return true end
    preview_active = false
    local ok = true
    if preview_was_bypassed then
        bypassed = true
        enter_original_view()
    else
        bypassed = false
        ok = apply_all()
    end
    show(false)
    return ok ~= false
end

local function preview_toggle()
    if preview_active then return preview_end() else return preview_start() end
end

local function compare()
    if preview_active then return end
    active_bank = (active_bank == "A") and "B" or "A"
    if apply_all() then show(true) end
end

local function capture_A()
    for _, it in ipairs(items) do A[it.key] = B[it.key] end
    mp.osd_message("Reference A captured from current B", 2)
    show(false)
end

local function apply_morph()
    local av, aerr = preset_ref_values(B.MORPH_FROM)
    if not av then mp.osd_message("Morph source unavailable:\n" .. tostring(aerr), 3); return false end
    local bv, berr = preset_ref_values(B.MORPH_TO)
    if not bv then mp.osd_message("Morph target unavailable:\n" .. tostring(berr), 3); return false end
    local t = clamp(B.MORPH_AMOUNT or 0, 0, 1)
    active_bank = "B"
    for _, key in ipairs(tuning_keys) do
        local it = by_key[key]
        local a = av[key] or it.d
        local b = bv[key] or it.d
        B[key] = round_if_needed(clamp(a + (b - a) * t, it.min, it.max), it.integer)
    end
    enforce_order(B, "")
    bypassed = false
    return apply_all()
end

local function adjust_current(direction)
    local it = current_item()
    if it.kind == "action" then return end
    if it.kind == "granularity" then
        step_mode = clamp(step_mode + direction, 1, 3)
        sync_granularity_item()
        show(false)
        return
    end

    activate_B_if_needed()
    local step = it.steps[step_mode] or it.steps[2] or 1
    local value = B[it.key] + direction * step
    B[it.key] = round_if_needed(clamp(value, it.min, it.max), it.integer)

    if it.kind == "controller" then
        show(false)
        return
    end

    if it.kind == "morph" then
        if apply_morph() then show(true) end
        return
    end

    enforce_order(B, it.key)

    if it.kind == "property" then
        if not unsupported_properties[it.key] then
            local ok, err = mp.set_property_number(it.key, B[it.key])
            if not ok then
                unsupported_properties[it.key] = true
                mp.osd_message("Property unavailable:\n" .. it.key .. "\n" .. tostring(err), 3)
            end
        end
        show(false)
        return
    end

    local ok, err = apply_shader(B)
    if not ok then mp.osd_message("SHADER APPLY FAILED:\n" .. tostring(err), 5); return end
    show(true)
end

local function reset_current()
    local it = current_item()
    if it.kind == "action" then return end
    if it.kind == "granularity" then step_mode = 2; sync_granularity_item(); show(false); return end
    B[it.key] = it.d
    if it.kind == "controller" then show(false); return end
    if it.kind == "morph" then apply_morph(); show(false); return end
    enforce_order(B, it.key)
    if it.kind == "property" then
        if not unsupported_properties[it.key] then mp.set_property_number(it.key, B[it.key]) end
    else
        apply_shader(B)
    end
    show(false)
end

local function reset_all()
    for _, it in ipairs(items) do
        if is_preset_item(it) then B[it.key] = it.d end
    end
    B.SHADER_PROOF = 0
    active_bank = "B"
    bypassed = false
    if apply_all() then
        mp.osd_message("Tuning reset to V3.1 baseline", 2)
        show(true)
        return true
    end
    return false
end

local function preset_chroma_1075()
    B.CHROMA_MASTER = 1.075
    active_bank = "B"
    selected = by_key.CHROMA_MASTER.index
    if apply_all() then mp.osd_message("Chroma master = 1.075 (+7.5%)", 2); show(true) end
end

local function export_values()
    local lines = {
        "PIXEL 9 V3.1 SHADER LAB v6",
        "================================",
        "A = reference bank",
        "B = working bank",
        "",
    }
    for _, it in ipairs(items) do
        if it.kind ~= "action" then
            lines[#lines+1] = string.format("%-14s %-24s A=%s B=%s", "["..it.group.."]", it.key, fmt(it,A[it.key]), fmt(it,B[it.key]))
        end
    end
    return atomic_write(VALUES_PATH, table.concat(lines,"\n") .. "\n")
end

local function save_state()
    local lines = {
        "# Pixel 9 V3.1 Shader Lab state v6",
        "step_mode=" .. tostring(step_mode),
    }
    for _, it in ipairs(items) do
        lines[#lines+1] = "A." .. it.key .. "=" .. string.format("%.17g", A[it.key] or it.d or 0)
        lines[#lines+1] = "B." .. it.key .. "=" .. string.format("%.17g", B[it.key] or it.d or 0)
    end
    local ok, err = atomic_write(STATE_PATH, table.concat(lines,"\n") .. "\n")
    if not ok then mp.osd_message("Save failed:\n" .. tostring(err),3); return false end

    local shader, serr = generate_shader(B)
    if shader then atomic_write(EXPORT_B_PATH, shader) else msg.warn("B shader export failed: " .. tostring(serr)) end
    export_values()
    mp.osd_message("Shader Lab state saved",2)
    return true
end

local function load_state()
    local f = io.open(STATE_PATH,"r")
    if not f then mp.osd_message("No saved Shader Lab state found",2); return false end
    for line in f:lines() do
        local key,value = line:match("^([^=]+)=([%+%-%.%deE]+)$")
        if key and value then
            if key == "step_mode" then
                step_mode = clamp(tonumber(value) or 2,1,3)
            else
                local bn, item_key = key:match("^([AB])%.(.+)$")
                local it = item_key and by_key[item_key]
                local num = tonumber(value)
                if bn and it and num then
                    local target = bn == "A" and A or B
                    target[item_key] = round_if_needed(clamp(num,it.min,it.max),it.integer)
                end
            end
        end
    end
    f:close()
    enforce_order(A,""); enforce_order(B,""); sync_granularity_item()
    active_bank="B"; bypassed=false
    if apply_all() then mp.osd_message("Shader Lab state loaded",2); show(true); return true end
    return false
end

local function capture_video_start()
    video_start_snapshot = {
        A = copy_table(A), B = copy_table(B), step_mode = step_mode,
        active_bank = active_bank,
    }
    video_original_properties = {}
    for _, it in ipairs(items) do
        if it.kind == "property" then
            video_original_properties[it.key] = mp.get_property_number(it.key, nil)
        end
    end
end

local function revert_video_start()
    if not video_start_snapshot then mp.osd_message("No video-start snapshot yet",2); return false end
    copy_into(A, video_start_snapshot.A)
    copy_into(B, video_start_snapshot.B)
    step_mode = video_start_snapshot.step_mode or 2
    active_bank = video_start_snapshot.active_bank or "B"
    sync_granularity_item()
    bypassed = false
    if apply_all() then mp.osd_message("Restored video-start state",2); show(true); return true end
    return false
end

local function request_confirmation(it, fn)
    local now = mp.get_time()
    if confirmation_key == it.key and now <= confirmation_deadline then
        cancel_confirmation()
        fn()
        show(false)
        return
    end
    cancel_confirmation()
    confirmation_key = it.key
    confirmation_deadline = now + 4.0
    confirmation_timer = mp.add_timeout(4.05, function()
        cancel_confirmation()
        if ui_visible then show(false) end
    end)
    show(false)
end

local function action_execute(it)
    local function run()
        if it.action == "bypass" then toggle_bypass()
        elseif it.action == "preview-toggle" then preview_toggle()
        elseif it.action == "load-user" then load_user_preset(B.USER_SLOT)
        elseif it.action == "save-user" then save_user_preset(B.USER_SLOT)
        elseif it.action == "clear-user" then clear_user_preset(B.USER_SLOT)
        elseif it.action == "load-builtin" then load_builtin_preset(B.BUILTIN_SLOT)
        elseif it.action == "revert-video-start" then revert_video_start()
        elseif it.action == "reset-all" then reset_all()
        elseif it.action == "save-state" then save_state()
        elseif it.action == "load-state" then load_state()
        end
    end
    if it.destructive then request_confirmation(it,run) else run() end
end

local function set_by_key(key, value)
    local it = by_key[key]
    local num = tonumber(value)
    if not it then mp.osd_message("Unknown Shader Lab control:\n"..tostring(key),3); return end
    if not num then mp.osd_message("Invalid number:\n"..tostring(value),3); return end
    if it.kind == "action" then return end
    B[key] = round_if_needed(clamp(num,it.min,it.max),it.integer)
    selected=it.index
    if it.kind == "controller" then show(false); return end
    if it.kind == "morph" then apply_morph(); show(false); return end
    enforce_order(B,key)
    if it.kind == "property" then
        if not unsupported_properties[key] then mp.set_property_number(key,B[key]) end
    elseif it.kind == "granularity" then
        step_mode=B[key]; sync_granularity_item()
    else
        apply_shader(B)
    end
    show(false)
end

local function show_list()
    local lines={"PIXEL 9 SHADER LAB v6.1.1 | " .. (bypassed and "BYPASS" or ("BANK "..active_bank))}
    local first=math.max(1,selected-4)
    local last=math.min(#items,first+9)
    first=math.max(1,last-9)
    for i=first,last do
        local it=items[i]
        lines[#lines+1]=string.format("%s %-28s %s", i==selected and ">" or " ", it.label, (i==selected) and display_value(it) or "")
    end
    mp.osd_message(table.concat(lines,"\n"),4)
end

local function status()
    local gamma=mp.get_property("video-params/gamma","") or ""
    mp.osd_message(string.format(
        "PIXEL 9 SHADER LAB v6.1.1\nSource gamma: %s\nMode: %s\nBank: %s\nShader slot: %s\nSwaps: %d\nUser presets: %d/10",
        gamma~="" and gamma or "(not ready)", preview_active and "ORIGINAL HOLD" or (bypassed and "BYPASS" or "ACTIVE"), active_bank,
        last_good_path==SLOT_A and "A" or "B", shader_apply_count,
        (function() local n=0; for i=1,10 do if user_slot_exists[i] then n=n+1 end end; return n end)()
    ),3)
end

local function phone_left()
    if not ui_visible then return end
    if confirmation_key then cancel_confirmation(); show(false); return end
    if edit_mode then adjust_current(-1) else move_selection(-1) end
end

local function phone_center()
    if not ui_visible then return end
    local it=current_item()
    if not edit_mode then
        if it.kind == "action" then action_execute(it); return end
        edit_mode=true
        edit_original_value=B[it.key]
        edit_changed=false
        show(false)
        return
    end
    edit_mode=false
    if it.kind == "granularity" then sync_granularity_item() end
    edit_original_value=nil; edit_changed=false
    show(false)
end

local function phone_right()
    if not ui_visible then return end
    if confirmation_key then cancel_confirmation(); show(false); return end
    if edit_mode then adjust_current(1) else move_selection(1) end
end

local function phone_top()
    if not ui_visible then return end
    if edit_mode then
        step_mode=clamp(step_mode+1,1,3); sync_granularity_item(); show(false)
    else
        next_group(-1)
    end
end

local function phone_bottom()
    if not ui_visible then return end
    if edit_mode then
        step_mode=clamp(step_mode-1,1,3); sync_granularity_item(); show(false)
    else
        next_group(1)
    end
end

-- Long-hold acceleration is deliberately navigation-only. It refuses to
-- repeat while editing a value, so parameter adjustments remain fixed-step.
local function nav_hold_end()
    if nav_repeat_timer then nav_repeat_timer:kill(); nav_repeat_timer=nil end
    if nav_repeat_watchdog then nav_repeat_watchdog:kill(); nav_repeat_watchdog=nil end
end

local function nav_hold_start(direction)
    if not ui_visible or edit_mode then return end
    nav_hold_end()
    move_selection(direction)
    nav_repeat_timer=mp.add_periodic_timer(0.085,function()
        if not ui_visible or edit_mode then
            nav_hold_end()
            return
        end
        move_selection(direction,true)
    end)
    -- Native finger-up is the primary stop. This watchdog is a second safety
    -- net so a lost Android pointer-up message can never create an infinite
    -- runaway menu. Re-hold to continue after the safety cap.
    nav_repeat_watchdog=mp.add_timeout(6.0,function() nav_hold_end() end)
end

local set_tv_bindings

local function toggle_ui()
    ui_visible=not ui_visible
    publish_ui_visibility()
    nav_hold_end(); cancel_confirmation()
    if set_tv_bindings then set_tv_bindings(ui_visible) end
    edit_mode=false; edit_original_value=nil; edit_changed=false
    if ui_visible then show(false) else hide_ui_overlay() end
end

local function safe_forced_binding(key,name,fn)
    local ok,err=pcall(mp.add_forced_key_binding,key,name,fn)
    if not ok then msg.error("Pixel 9 Lab binding failed for "..key..": "..tostring(err)) end
end

local tv_binding_names = {
    "p9lab-tv-left", "p9lab-tv-right", "p9lab-tv-up", "p9lab-tv-down",
    "p9lab-tv-enter", "p9lab-tv-kp-enter", "p9lab-tv-esc",
}

set_tv_bindings = function(enabled)
    for _, name in ipairs(tv_binding_names) do pcall(mp.remove_key_binding, name) end
    if not enabled then return end
    safe_forced_binding("LEFT", "p9lab-tv-left", phone_left)
    safe_forced_binding("RIGHT", "p9lab-tv-right", phone_right)
    safe_forced_binding("UP", "p9lab-tv-up", phone_top)
    safe_forced_binding("DOWN", "p9lab-tv-down", phone_bottom)
    safe_forced_binding("ENTER", "p9lab-tv-enter", phone_center)
    safe_forced_binding("KP_ENTER", "p9lab-tv-kp-enter", phone_center)
    safe_forced_binding("ESC", "p9lab-tv-esc", function() if ui_visible then toggle_ui() end end)
end

local layout_refresh_timer = nil

local function schedule_layout_refresh()
    if layout_refresh_timer then
        layout_refresh_timer:kill()
        layout_refresh_timer = nil
    end
    layout_refresh_timer = mp.add_timeout(0.05, function()
        layout_refresh_timer = nil
        sync_overlay_resolution()
        if ui_visible then show(false) end
    end)
end

mp.observe_property("osd-width", "native", schedule_layout_refresh)
mp.observe_property("osd-height", "native", schedule_layout_refresh)

sync_granularity_item()
scan_user_slots()

-- R07 event-driven Android state transport. Every native semantic mutation
-- publishes a complete snapshot through one observed mpv user-data property.
-- There is deliberately no periodic state timer here.
publish_native_state = function()
    native_serial = native_serial + 1
    local source_gamma = mp.get_property("video-params/gamma", "") or ""
    local lines = {
        "__ready=1",
        "__version=6.1.1-r07-state-3",
        "__serial=" .. tostring(native_serial),
        "__bank=" .. tostring(active_bank),
        "__bypassed=" .. (bypassed and "1" or "0"),
        "__preview=" .. (preview_active and "1" or "0"),
        "__sdr=" .. (is_sdr() and "1" or "0"),
        "__source_gamma=" .. tostring(source_gamma):gsub("[\r\n]", " "),
        "__shader_slot=" .. ((last_good_path == SLOT_A) and "A" or "B"),
        "__swaps=" .. tostring(shader_apply_count),
        "__apply_busy=" .. (native_apply_busy and "1" or "0"),
        "__error=" .. tostring(native_last_error or ""):gsub("[\r\n]", " "),
    }
    for _, it in ipairs(items) do
        if it.kind ~= "action" then
            local value = B[it.key]
            if value ~= nil then
                lines[#lines + 1] = it.key .. "=" .. string.format("%.17g", value)
            end
        end
    end
    for i = 1, 10 do
        lines[#lines + 1] = "__user" .. tostring(i) .. "=" .. (user_slot_exists[i] and "1" or "0")
    end
    mp.set_property("user-data/p9lab/native-state", table.concat(lines, "\n"))
end

local function native_invoke(label, busy, fn)
    if busy then
        native_apply_busy = true
        publish_native_state()
    end
    local ok, result = pcall(fn)
    native_apply_busy = false
    if not ok then
        native_last_error = tostring(result or (label .. " failed"))
        msg.error("Shader Lab native command failed: " .. native_last_error)
    elseif result == false then
        native_last_error = label .. " failed"
    else
        native_last_error = ""
    end
    publish_native_state()
    return ok and result ~= false
end

-- R08 resident transport compatibility cache. Android owns ordinary
-- resident GPU tuning, but legacy Lua preset/state files still serialize B.
-- This updates that in-memory bank without applying properties, generating
-- GLSL, writing runtime shader files, or changing the shader list.
local function resident_cache_sync(snapshot)
    if type(snapshot) ~= "string" then return false end
    local touched = false
    for token in snapshot:gmatch("[^;]+") do
        local key, value = token:match("^([^=]+)=([%+%-%.%deE]+)$")
        local it = key and by_key[key] or nil
        local num = tonumber(value)
        if it and it.kind ~= "action" and num then
            B[key] = round_if_needed(clamp(num, it.min, it.max), it.integer)
            if it.kind == "granularity" then step_mode = B[key] end
            touched = true
        end
    end
    enforce_order(B, "")
    sync_granularity_item()
    return touched
end

local function native_set_by_key(key, value)
    local it = by_key[key]
    local num = tonumber(value)
    if not it then
        native_last_error = "Unknown Shader Lab control: " .. tostring(key)
        publish_native_state()
        return false
    end
    if not num then
        native_last_error = "Invalid Shader Lab value for " .. tostring(key) .. ": " .. tostring(value)
        publish_native_state()
        return false
    end
    if it.kind == "action" then
        native_last_error = "Cannot set action control: " .. tostring(key)
        publish_native_state()
        return false
    end

    B[key] = round_if_needed(clamp(num, it.min, it.max), it.integer)
    selected = it.index

    if it.kind == "controller" then
        native_last_error = ""
        publish_native_state()
        return true
    end
    if it.kind == "granularity" then
        step_mode = B[key]
        sync_granularity_item()
        native_last_error = ""
        publish_native_state()
        return true
    end
    if it.kind == "morph" then
        native_apply_busy = true
        publish_native_state()
        local ok = apply_morph()
        native_apply_busy = false
        native_last_error = ok and "" or "Preset morph failed"
        publish_native_state()
        return ok
    end

    enforce_order(B, key)
    if it.kind == "property" then
        if unsupported_properties[key] then
            native_last_error = "Property unavailable: " .. tostring(key)
            publish_native_state()
            return false
        end
        local ok, err = mp.set_property_number(key, B[key])
        if not ok then
            unsupported_properties[key] = true
            native_last_error = "Property unavailable: " .. tostring(key) .. " (" .. tostring(err) .. ")"
            publish_native_state()
            return false
        end
        native_last_error = ""
        publish_native_state()
        return true
    end

    native_apply_busy = true
    publish_native_state()
    local ok, err = apply_shader(B)
    native_apply_busy = false
    if ok then
        native_last_error = ""
    else
        native_last_error = tostring(err or "Shader apply failed")
        msg.error("Shader Lab native apply failed: " .. native_last_error)
    end
    publish_native_state()
    return ok ~= nil and ok ~= false
end

safe_forced_binding("MBTN_LEFT_DBL","p9lab-mpvflux-left",phone_left)
safe_forced_binding("MBTN_MID_DBL","p9lab-mpvflux-center",phone_center)
safe_forced_binding("MBTN_RIGHT_DBL","p9lab-mpvflux-right",phone_right)

-- MENU is the cross-device remote shortcut. Android TV DPAD itself is also
-- routed by the companion native patch because mpvFlux intercepts DPAD
-- LEFT/RIGHT before libmpv receives them.
safe_forced_binding("MENU","p9lab-tv-menu-toggle",toggle_ui)

mp.register_script_message("p9lab-toggle-ui",toggle_ui)
mp.register_script_message("mpvflux-tap-top",phone_top)
mp.register_script_message("mpvflux-tap-bottom",phone_bottom)
mp.register_script_message("p9lab-phone-top",phone_top)
mp.register_script_message("p9lab-phone-bottom",phone_bottom)
mp.register_script_message("p9lab-phone-left",phone_left)
mp.register_script_message("p9lab-phone-center",phone_center)
mp.register_script_message("p9lab-phone-right",phone_right)

mp.register_script_message("p9lab-preview-start",preview_start)
mp.register_script_message("p9lab-preview-end",preview_end)
mp.register_script_message("p9lab-preview-toggle",preview_toggle)
mp.register_script_message("p9lab-nav-left-hold-start",function() nav_hold_start(-1) end)
mp.register_script_message("p9lab-nav-left-hold-end",nav_hold_end)
mp.register_script_message("p9lab-nav-right-hold-start",function() nav_hold_start(1) end)
mp.register_script_message("p9lab-nav-right-hold-end",nav_hold_end)
mp.register_script_message("p9lab-nav-hold-end",nav_hold_end)

mp.register_script_message("p9lab-inc",function() adjust_current(1) end)
mp.register_script_message("p9lab-dec",function() adjust_current(-1) end)
mp.register_script_message("p9lab-next",function() move_selection(1) end)
mp.register_script_message("p9lab-prev",function() move_selection(-1) end)
mp.register_script_message("p9lab-next-group",function() next_group(1) end)
mp.register_script_message("p9lab-prev-group",function() next_group(-1) end)
mp.register_script_message("p9lab-step",function() cycle_step(1) end)
mp.register_script_message("p9lab-step-back",function() cycle_step(-1) end)
mp.register_script_message("p9lab-compare",compare)
mp.register_script_message("p9lab-bypass",toggle_bypass)
mp.register_script_message("p9lab-capture-a",capture_A)
mp.register_script_message("p9lab-reset-current",reset_current)
mp.register_script_message("p9lab-reset-all",function() request_confirmation(by_key.RESET_ALL_MENU,reset_all) end)
mp.register_script_message("p9lab-chroma-1075",preset_chroma_1075)
mp.register_script_message("p9lab-save",save_state)
mp.register_script_message("p9lab-load",load_state)
mp.register_script_message("p9lab-show",function() if ui_visible then show(false) end end)
mp.register_script_message("p9lab-layout-info", function()
    local ow, oh = mp.get_osd_size()
    mp.osd_message(string.format(
        "Shader Lab layout\nOSD: %sx%s\nASS PlayRes: %sx%d",
        tostring(ow), tostring(oh), tostring(current_playres_x), LOGICAL_H
    ), 3)
end)
mp.register_script_message("p9lab-list",show_list)
mp.register_script_message("p9lab-status",status)
mp.register_script_message("p9lab-set",native_set_by_key)

mp.register_script_message("p9lab-user-save",function(slot) B.USER_SLOT=clamp(tonumber(slot) or B.USER_SLOT,1,10); save_user_preset(B.USER_SLOT) end)
mp.register_script_message("p9lab-user-load",function(slot) B.USER_SLOT=clamp(tonumber(slot) or B.USER_SLOT,1,10); load_user_preset(B.USER_SLOT) end)
mp.register_script_message("p9lab-builtin-load",function(slot) B.BUILTIN_SLOT=clamp(tonumber(slot) or B.BUILTIN_SLOT,1,10); load_builtin_preset(B.BUILTIN_SLOT) end)
mp.register_script_message("p9lab-morph",function(a,b,t)
    B.MORPH_FROM=clamp(tonumber(a) or B.MORPH_FROM,1,20)
    B.MORPH_TO=clamp(tonumber(b) or B.MORPH_TO,1,20)
    B.MORPH_AMOUNT=clamp(tonumber(t) or B.MORPH_AMOUNT,0,1)
    apply_morph(); show(false)
end)
mp.register_script_message("p9lab-revert-video-start",function() request_confirmation(by_key.REVERT_VIDEO_START,revert_video_start) end)

-- R07 semantic command bridge registrations. These bypass the legacy Lua OSD
-- confirmation layer because confirmation policy now lives above R06.
mp.register_script_message("p9lab-native-state", publish_native_state)
mp.register_script_message("p9lab-native-set", native_set_by_key)
mp.register_script_message("p9lab-native-user-save-r08", function(slot, snapshot)
    native_invoke("Save user preset", false, function()
        resident_cache_sync(snapshot)
        B.USER_SLOT = clamp(tonumber(slot) or B.USER_SLOT, 1, 10)
        return save_user_preset(B.USER_SLOT)
    end)
end)
mp.register_script_message("p9lab-native-save-state-r08", function(snapshot)
    native_invoke("Save state", false, function()
        resident_cache_sync(snapshot)
        return save_state()
    end)
end)
mp.register_script_message("p9lab-native-bypass", function() native_invoke("Bypass", true, toggle_bypass) end)
mp.register_script_message("p9lab-native-preview-start", function() native_invoke("Original preview start", true, preview_start) end)
mp.register_script_message("p9lab-native-preview-end", function() native_invoke("Original preview end", true, preview_end) end)
mp.register_script_message("p9lab-native-preview-toggle", function() native_invoke("Original preview toggle", true, preview_toggle) end)
mp.register_script_message("p9lab-native-reset-all", function() native_invoke("Reset all", true, reset_all) end)
mp.register_script_message("p9lab-native-revert-video-start", function() native_invoke("Revert video start", true, revert_video_start) end)
mp.register_script_message("p9lab-native-user-save", function(slot)
    native_invoke("Save user preset", false, function()
        B.USER_SLOT = clamp(tonumber(slot) or B.USER_SLOT, 1, 10)
        return save_user_preset(B.USER_SLOT)
    end)
end)
mp.register_script_message("p9lab-native-user-load", function(slot)
    native_invoke("Load user preset", true, function()
        B.USER_SLOT = clamp(tonumber(slot) or B.USER_SLOT, 1, 10)
        return load_user_preset(B.USER_SLOT)
    end)
end)
mp.register_script_message("p9lab-native-user-clear", function(slot)
    native_invoke("Clear user preset", false, function()
        B.USER_SLOT = clamp(tonumber(slot) or B.USER_SLOT, 1, 10)
        return clear_user_preset(B.USER_SLOT)
    end)
end)
mp.register_script_message("p9lab-native-builtin-load", function(slot)
    native_invoke("Load built-in preset", true, function()
        B.BUILTIN_SLOT = clamp(tonumber(slot) or B.BUILTIN_SLOT, 1, 10)
        return load_builtin_preset(B.BUILTIN_SLOT)
    end)
end)
mp.register_script_message("p9lab-native-morph", function(a,b,t)
    native_invoke("Preset morph", true, function()
        B.MORPH_FROM = clamp(tonumber(a) or B.MORPH_FROM, 1, 20)
        B.MORPH_TO = clamp(tonumber(b) or B.MORPH_TO, 1, 20)
        B.MORPH_AMOUNT = clamp(tonumber(t) or B.MORPH_AMOUNT, 0, 1)
        return apply_morph()
    end)
end)
mp.register_script_message("p9lab-native-save-state", function() native_invoke("Save state", false, save_state) end)
mp.register_script_message("p9lab-native-load-state", function() native_invoke("Load state", true, load_state) end)

mp.register_event("file-loaded",function()
    edit_mode=false; edit_original_value=nil; edit_changed=false
    preview_active=false; nav_hold_end(); cancel_confirmation()
    if is_sdr() then last_good_path=SLOT_A; bypassed=false end
    capture_video_start()
    if ui_visible then show(false) end
    publish_native_state()
end)

publish_ui_visibility()
hide_ui_overlay()
publish_native_state()

msg.info("Pixel 9 V3.1 Shader Lab Workstation v6.1 Studio R07 STATE 3 loaded: " .. tostring(#items) .. " menu items; 10 built-ins + 10 user slots; Studio UI + Android TV remote; state-compatible")
