#!/usr/bin/env python3
"""Finalize R08 resident-source compatibility before custom libmpv validation.

This is deliberately idempotent. It keeps Lua as a preset/state compatibility
store while Android owns ordinary resident GPU parameter transport. No normal
R08 tuning path is routed back through Lua shader generation.
"""

from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> bool:
    text = path.read_text()
    if new in text:
        return False
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one anchor, found {count}")
    path.write_text(text.replace(old, new, 1))
    return True


def main() -> None:
    lua = ROOT / "app/src/main/assets/mpvlab/source/scripts/pixel9-shader-lab.lua"
    replace_once(
        lua,
        "local function native_set_by_key(key, value)\n",
        '''-- R08 resident transport compatibility cache. Android owns ordinary
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
''',
    )

    replace_once(
        lua,
        '''mp.register_script_message("p9lab-native-state", publish_native_state)
mp.register_script_message("p9lab-native-set", native_set_by_key)
''',
        '''mp.register_script_message("p9lab-native-state", publish_native_state)
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
''',
    )

    bridge = ROOT / "app/src/main/java/app/marlboroadvance/mpvex/repository/shaderlab/bridge/MpvShaderLabBridge.kt"
    replace_once(
        bridge,
        '''  override fun saveUserPreset(preset: ShaderLabPresetId.User) =
    scriptMessage("p9lab-native-user-save", preset.slot.toString())
''',
        '''  override fun saveUserPreset(preset: ShaderLabPresetId.User) =
    scriptMessage(
      "p9lab-native-user-save-r08",
      preset.slot.toString(),
      luaStateSnapshot(),
    )
''',
    )
    replace_once(
        bridge,
        '''  override fun saveState() =
    scriptMessage("p9lab-native-save-state")
''',
        '''  override fun saveState() =
    scriptMessage("p9lab-native-save-state-r08", luaStateSnapshot())
''',
    )
    replace_once(
        bridge,
        '''  companion object {
''',
        '''  private fun luaStateSnapshot(): String {
    val normalized = ShaderLabControlCatalog.normalizeValues(_state.value.values)
    return ShaderLabControlCatalog.controls.joinToString(";") { spec ->
      "${spec.id.legacyKey}=${formatDouble(normalized.getValue(spec.id))}"
    }
  }

  companion object {
''',
    )

    # The first ready Lua/native-state snapshot is the authoritative startup
    # seed. Resident defaults must not overlay it before Android has adopted it.
    replace_once(
        bridge,
        '''    val legacyShaderSwapChanged = decoded.shaderSwapCount != previous.shaderSwapCount

    if (residentGpu.isAuthoritative()) {
      if (!decoded.applyBusy && (adoptResidentFromLuaOnIdle || legacyShaderSwapChanged)) {
''',
        '''    val legacyShaderSwapChanged = decoded.shaderSwapCount != previous.shaderSwapCount
    val firstReadySnapshot = !previous.ready && decoded.ready

    if (residentGpu.isAuthoritative()) {
      if (!decoded.applyBusy && (firstReadySnapshot || adoptResidentFromLuaOnIdle || legacyShaderSwapChanged)) {
''',
    )

    test = ROOT / "app/src/test/java/app/marlboroadvance/mpvex/repository/shaderlab/bridge/MpvShaderLabBridgeTest.kt"
    replace_once(
        test,
        '''  @Test
  fun externalMpvPropertyObservationUpdatesStateImmediately() {
''',
        '''  @Test
  fun saveCompatibilityActionsCarryCurrentResidentAndMpvStateWithoutLegacyApply() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()
    bridge.setValues(
      linkedMapOf(
        ShaderLabControlId.LUMA_CONTRAST to 0.31,
        ShaderLabControlId.MPV_BRIGHTNESS to 4.25,
      ),
    )
    transport.commands.clear()

    bridge.saveUserPreset(ShaderLabPresetId.User(3))
    bridge.saveState()

    val preset = transport.commands.single { it.getOrNull(1) == "p9lab-native-user-save-r08" }
    assertEquals("3", preset[2])
    assertTrue(preset[3].contains("LUMA_CONTRAST=0.31000000000000000"))
    assertTrue(preset[3].contains("brightness=4.2500000000000000"))
    val state = transport.commands.single { it.getOrNull(1) == "p9lab-native-save-state-r08" }
    assertTrue(state[2].contains("LUMA_CONTRAST=0.31000000000000000"))
    assertTrue(state[2].contains("brightness=4.2500000000000000"))
    assertFalse(transport.commands.any { it.getOrNull(1) == "p9lab-native-set" })
    assertFalse(transport.commands.any { it.firstOrNull() == "change-list" })
  }

  @Test
  fun externalMpvPropertyObservationUpdatesStateImmediately() {
''',
    )

    manifest_path = ROOT / "app/src/main/assets/mpvlab/source/engine-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["engineVersion"] = "6.1.1-source-r08-resident-2"
    lua_bytes = lua.read_bytes()
    for entry in manifest["files"]:
        if entry["path"] == "scripts/pixel9-shader-lab.lua":
            entry["bytes"] = len(lua_bytes)
            entry["sha256"] = hashlib.sha256(lua_bytes).hexdigest()
            break
    else:
        raise RuntimeError("Lua payload missing from engine manifest")
    manifest.setdefault("sourceProvenance", {})["r08ResidentTransport"] = (
        "R08 resident-2: vo=gpu PARAM shader with complete glsl-shader-opts transport; "
        "Lua preset/state saves receive state-only cache synchronization without ordinary GLSL regeneration."
    )
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")

    print("R08 resident compatibility finalization PASS")


if __name__ == "__main__":
    main()
