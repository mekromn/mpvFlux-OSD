from pathlib import Path
import hashlib
import json


def must_replace(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# Android bridge: add user-visible sync proof sink.
bridge_path = Path(
    "app/src/main/java/app/marlboroadvance/mpvex/repository/shaderlab/bridge/MpvShaderLabBridge.kt"
)
bridge = bridge_path.read_text()
bridge = must_replace(
    bridge,
    """class MpvShaderLabBridge internal constructor(
  private val transport: ShaderLabMpvTransport,
) : ShaderLabCommandBackend {
  constructor() : this(LibMpvShaderLabTransport())""",
    """class MpvShaderLabBridge internal constructor(
  private val transport: ShaderLabMpvTransport,
  private val syncProbe: ShaderLabBridgeSyncProbe = NoOpShaderLabBridgeSyncProbe,
) : ShaderLabCommandBackend {
  constructor() : this(LibMpvShaderLabTransport(), FileShaderLabBridgeSyncProbe())""",
    "bridge constructor",
)
bridge = must_replace(
    bridge,
    """    _state.value = decoded.copy(connected = true)
    _events.tryEmit(ShaderLabBridgeEvent.SnapshotReceived(decoded.snapshotSerial))""",
    """    _state.value = decoded.copy(connected = true)
    runCatching { syncProbe.record(decoded) }
    _events.tryEmit(ShaderLabBridgeEvent.SnapshotReceived(decoded.snapshotSerial))""",
    "bridge sync probe",
)
bridge_path.write_text(bridge)


# Koin: register one bridge/backend/API singleton chain.
module_path = Path("app/src/main/java/app/marlboroadvance/mpvex/di/ShaderLabModule.kt")
module = module_path.read_text()
module = must_replace(
    module,
    """import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabWorkspaceManager
import org.koin.dsl.module""",
    """import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabWorkspaceManager
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.MpvShaderLabBridge
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandApi
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandBackend
import org.koin.dsl.module""",
    "module imports",
)
module = must_replace(
    module,
    """    single { ShaderLabWorkspaceManager(get()) }
    single { ShaderLabEngineInstaller(context = get(), workspaceManager = get()) }""",
    """    single { ShaderLabWorkspaceManager(get()) }
    single { ShaderLabEngineInstaller(context = get(), workspaceManager = get()) }
    single { MpvShaderLabBridge() }
    single<ShaderLabCommandBackend> { get<MpvShaderLabBridge>() }
    single { ShaderLabCommandApi(get()) }""",
    "module registrations",
)
module_path.write_text(module)


# Attach the bridge whenever libmpv establishes property observation.
view_path = Path("app/src/main/java/app/marlboroadvance/mpvex/ui/player/MPVView.kt")
view = view_path.read_text()
view = must_replace(
    view,
    """import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.PlayerActivity.Companion.TAG""",
    """import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.MpvShaderLabBridge
import app.marlboroadvance.mpvex.ui.player.PlayerActivity.Companion.TAG""",
    "MPVView bridge import",
)
view = must_replace(
    view,
    """  private val subtitlesPreferences: SubtitlesPreferences by inject()

  var isExiting = false""",
    """  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val shaderLabBridge: MpvShaderLabBridge by inject()

  var isExiting = false""",
    "MPVView bridge injection",
)
view = must_replace(
    view,
    """  override fun observeProperties() {
    for ((name, format) in observedProps) MPVLib.observeProperty(name, format)
  }""",
    """  override fun observeProperties() {
    for ((name, format) in observedProps) MPVLib.observeProperty(name, format)
    shaderLabBridge.attach()
  }""",
    "MPVView bridge attach",
)
view_path.write_text(view)


# Readable Lua: event-driven native state publisher and semantic wrappers.
lua_path = Path("app/src/main/assets/mpvlab/source/scripts/pixel9-shader-lab.lua")
lua = lua_path.read_text()

lua = must_replace(
    lua,
    """local user_slot_exists = {}

local function clamp(v, lo, hi)""",
    """local user_slot_exists = {}

-- R07 observable native bridge state. The publisher is assigned after all
-- workstation helpers are defined; functions may safely reference it later.
local native_last_error = ""
local native_apply_busy = false
local native_serial = 0
local publish_native_state = function() end

local function clamp(v, lo, hi)""",
    "Lua native state forward declaration",
)

lua = must_replace(
    lua,
    """local function toggle_bypass()
    if not is_sdr() then mp.osd_message("Shader Lab bypass is SDR-only", 2); return end
    if preview_active then return end
    if not bypassed then
        enter_original_view()
        bypassed = true
        mp.osd_message("BYPASS: original SDR comparison", 1.5)
        show(false)
        return
    end
    bypassed = false
    if apply_all() then show(true) end
end

local function preview_start()
    if not ui_visible or preview_active or not is_sdr() then return end
    preview_was_bypassed = bypassed
    preview_active = true
    if not bypassed then enter_original_view() end
    show(false)
end

local function preview_end()
    if not preview_active then return end
    preview_active = false
    if preview_was_bypassed then
        bypassed = true
        enter_original_view()
    else
        bypassed = false
        apply_all()
    end
    show(false)
end

local function preview_toggle()
    if preview_active then preview_end() else preview_start() end
end""",
    """local function toggle_bypass()
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
end""",
    "Lua comparison lifecycle returns",
)

lua = must_replace(
    lua,
    """local function reset_all()
    for _, it in ipairs(items) do
        if is_preset_item(it) then B[it.key] = it.d end
    end
    B.SHADER_PROOF = 0
    active_bank = "B"
    bypassed = false
    if apply_all() then mp.osd_message("Tuning reset to V3.1 baseline", 2); show(true) end
end""",
    """local function reset_all()
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
end""",
    "Lua reset return",
)

native_block = r'''-- R07 event-driven Android state transport. Every native semantic mutation
-- publishes a complete snapshot through one observed mpv user-data property.
-- There is deliberately no periodic state timer here.
publish_native_state = function()
    native_serial = native_serial + 1
    local source_gamma = mp.get_property("video-params/gamma", "") or ""
    local lines = {
        "__ready=1",
        "__version=6.1.1-r07-state-1",
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
end'''

lua = must_replace(
    lua,
    """sync_granularity_item()
scan_user_slots()

safe_forced_binding("MBTN_LEFT_DBL","p9lab-mpvflux-left",phone_left)""",
    """sync_granularity_item()
scan_user_slots()

""" + native_block + """

safe_forced_binding("MBTN_LEFT_DBL","p9lab-mpvflux-left",phone_left)""",
    "Lua native publisher insertion",
)

lua = must_replace(
    lua,
    '''mp.register_script_message("p9lab-set",set_by_key)''',
    '''mp.register_script_message("p9lab-set",native_set_by_key)''',
    "Lua p9lab-set observable wrapper",
)

native_regs = r'''

-- R07 semantic command bridge registrations. These bypass the legacy Lua OSD
-- confirmation layer because confirmation policy now lives above R06.
mp.register_script_message("p9lab-native-state", publish_native_state)
mp.register_script_message("p9lab-native-set", native_set_by_key)
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
mp.register_script_message("p9lab-native-load-state", function() native_invoke("Load state", true, load_state) end)'''

lua = must_replace(
    lua,
    '''mp.register_script_message("p9lab-revert-video-start",function() request_confirmation(by_key.REVERT_VIDEO_START,revert_video_start) end)

mp.register_event("file-loaded",function()''',
    '''mp.register_script_message("p9lab-revert-video-start",function() request_confirmation(by_key.REVERT_VIDEO_START,revert_video_start) end)''' + native_regs + '''

mp.register_event("file-loaded",function()''',
    "Lua native registrations",
)

lua = must_replace(
    lua,
    """    capture_video_start()
    if ui_visible then show(false) end
end)

publish_ui_visibility()""",
    """    capture_video_start()
    if ui_visible then show(false) end
    publish_native_state()
end)

publish_ui_visibility()""",
    "Lua file-loaded publish",
)

lua = must_replace(
    lua,
    '''publish_ui_visibility()
hide_ui_overlay()

msg.info("Pixel 9 V3.1 Shader Lab Workstation v6.1 Studio loaded:''',
    '''publish_ui_visibility()
hide_ui_overlay()
publish_native_state()

msg.info("Pixel 9 V3.1 Shader Lab Workstation v6.1 Studio loaded:''',
    "Lua startup publish",
)

lua_path.write_text(lua)


# Manifest: bump engine version and update exact Lua integrity metadata.
manifest_path = Path("app/src/main/assets/mpvlab/source/engine-manifest.json")
manifest = json.loads(manifest_path.read_text())
manifest["engineVersion"] = "6.1.1-source-r07-state-1"
manifest["sourceProvenance"]["r07StateTransport"] = (
    "Event-driven user-data/p9lab/native-state publisher with explicit native semantic messages; no periodic state timer."
)
lua_bytes = lua_path.read_bytes()
for record in manifest["files"]:
    if record["path"] == "scripts/pixel9-shader-lab.lua":
        record["bytes"] = len(lua_bytes)
        record["sha256"] = hashlib.sha256(lua_bytes).hexdigest()
        break
else:
    raise SystemExit("Lua manifest record not found")
manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
