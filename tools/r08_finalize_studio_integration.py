#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one anchor, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1))


studio = ROOT / "app/src/main/java/app/marlboroadvance/mpvex/ui/player/controls/ShaderLabStudio.kt"
replace_once(studio, "import androidx.compose.runtime.Stable\n", "")
replace_once(studio, "import androidx.compose.runtime.rememberCoroutineScope\n", "")
replace_once(studio, "import androidx.compose.runtime.withFrameNanos\n", "")
replace_once(studio, "import kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.Job\nimport kotlinx.coroutines.launch\n", "")
replace_once(
    studio,
    """ * Unlike the R08 debug harness, this is a native Compose editor: Material\n * sliders emit continuously during pointer movement and are coalesced to at\n * most one backend update per display frame. The native mpv R08 patch then\n * updates the already-resident vo=gpu uniforms in-place.\n""",
    """ * Unlike the R08 debug harness, this is a native Compose editor. Like the\n * player's native brightness slider, every slider movement is applied\n * immediately. The destination is the resident vo=gpu PARAM set rather than\n * WindowManager brightness, so there is no Lua round-trip, debounce/apply\n * phase, shader regeneration, or shader-list detach/reattach.\n""",
)
replace_once(
    studio,
    """  val scope = rememberCoroutineScope()\n  val liveDispatcher = remember(commandApi, scope) { FrameCoalescedShaderDispatcher(scope, commandApi) }\n""",
    "",
)
replace_once(
    studio,
    """            onValueChange = liveDispatcher::submit,\n""",
    """            onValueChange = { id, value ->\n              commandApi.execute(ShaderLabCommand.SetValue(id, value))\n            },\n""",
)
replace_once(
    studio,
    """              } else {\n                liveDispatcher.submit(spec.id, value)\n              }\n""",
    """              } else {\n                commandApi.execute(ShaderLabCommand.SetValue(spec.id, value))\n              }\n""",
)
replace_once(
    studio,
    """@Stable\nprivate class FrameCoalescedShaderDispatcher(\n  private val scope: CoroutineScope,\n  private val api: ShaderLabCommandApi,\n) {\n  private val pending = linkedMapOf<ShaderLabControlId, Double>()\n  private var job: Job? = null\n\n  fun submit(id: ShaderLabControlId, value: Double) {\n    pending[id] = value\n    if (job?.isActive == true) return\n    job = scope.launch {\n      while (pending.isNotEmpty()) {\n        withFrameNanos { }\n        val frame = pending.toMap()\n        pending.clear()\n        frame.forEach { (control, next) ->\n          api.execute(ShaderLabCommand.SetValue(control, next))\n        }\n      }\n    }\n  }\n}\n\n""",
    "",
)

resident = ROOT / "app/src/main/java/app/marlboroadvance/mpvex/repository/shaderlab/bridge/ShaderLabResidentGpuTransport.kt"
replace_once(
    resident,
    """  /**\n   * Legacy preset/state actions may still alter Lua's value bank. Adopt that\n   * bank at an explicit compatibility boundary, then publish it through the\n   * same resident live-uniform path. If the SDR resident hook is already\n   * attached, adoption must not rebuild or churn the shader list.\n   */\n  fun adoptLegacyValues(values: Map<ShaderLabControlId, Double>, sourceKind: ShaderLabSourceKind) {\n    lastGoodValues = ShaderLabControlCatalog.normalizeValues(values)\n    lastGoodOptions = encodeOptions(lastGoodValues)\n    authoritative = true\n\n    if (\n      sourceKind == ShaderLabSourceKind.SDR &&\n        attachedSourceKind == ShaderLabSourceKind.SDR &&\n        residentShaderIsAttached()\n    ) {\n      setAndVerifyOptions(optionsForView(lastGoodOptions))\n    } else {\n      reconcileSource(sourceKind, force = true)\n    }\n  }\n""",
    """  /**\n   * Legacy preset/state actions may still alter Lua's value bank. Adopt that\n   * bank at an explicit compatibility boundary, then publish it through the\n   * same resident live-uniform path. If the SDR resident hook is already\n   * attached, adoption must not rebuild or churn the shader list.\n   *\n   * The incoming bank is authoritative only after mpv accepts/reads it back.\n   * A failed adoption restores the previous resident PARAM set and Android's\n   * previous last-known-good value bank.\n   */\n  fun adoptLegacyValues(values: Map<ShaderLabControlId, Double>, sourceKind: ShaderLabSourceKind) {\n    val previousValues = lastGoodValues\n    val previousOptions = lastGoodOptions\n    val previousAuthoritative = authoritative\n    val nextValues = ShaderLabControlCatalog.normalizeValues(values)\n    val nextOptions = encodeOptions(nextValues)\n\n    try {\n      lastGoodValues = nextValues\n      lastGoodOptions = nextOptions\n      authoritative = true\n\n      if (\n        sourceKind == ShaderLabSourceKind.SDR &&\n          attachedSourceKind == ShaderLabSourceKind.SDR &&\n          residentShaderIsAttached()\n      ) {\n        setAndVerifyOptions(optionsForView(nextOptions))\n      } else {\n        reconcileSource(sourceKind, force = true)\n      }\n    } catch (error: Throwable) {\n      lastGoodValues = previousValues\n      lastGoodOptions = previousOptions\n      authoritative = previousAuthoritative\n\n      if (\n        sourceKind == ShaderLabSourceKind.SDR &&\n          attachedSourceKind == ShaderLabSourceKind.SDR &&\n          residentShaderIsAttached()\n      ) {\n        runCatching { setAndVerifyOptions(optionsForView(previousOptions)) }\n      }\n      throw error\n    }\n  }\n""",
)

test = ROOT / "app/src/test/java/app/marlboroadvance/mpvex/repository/shaderlab/bridge/ShaderLabResidentGpuTransportTest.kt"
anchor = """  @Test\n  fun publishRejectsAReadbackThatDidNotAcceptTheRequestedValue() {\n"""
new_test = """  @Test\n  fun failedLegacyAdoptionRollsBackWithoutShaderListChurn() {\n    val transport = FakeTransport()\n    val gpu = ShaderLabResidentGpuTransport(transport)\n    val initial = ShaderLabControlCatalog.defaults()\n    gpu.initialize(initial, ShaderLabSourceKind.SDR)\n    val previous = transport.strings.getValue(ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY)\n    transport.commands.clear()\n\n    val changed = initial.toMutableMap().apply {\n      this[ShaderLabControlId.LUMA_CONTRAST] = 0.777\n    }\n    transport.corruptNextReadback = true\n\n    val result = runCatching { gpu.adoptLegacyValues(changed, ShaderLabSourceKind.SDR) }\n\n    assertTrue(result.isFailure)\n    assertFalse(transport.commands.any { it.firstOrNull() == \"change-list\" })\n    assertEquals(previous, transport.commands.last()[2])\n    assertEquals(previous, transport.strings[ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY])\n    assertEquals(\n      initial[ShaderLabControlId.LUMA_CONTRAST],\n      gpu.overlayResidentValues(changed)[ShaderLabControlId.LUMA_CONTRAST],\n    )\n  }\n\n""" + anchor
replace_once(test, anchor, new_test)

print("R08 Studio integration finalization applied")
