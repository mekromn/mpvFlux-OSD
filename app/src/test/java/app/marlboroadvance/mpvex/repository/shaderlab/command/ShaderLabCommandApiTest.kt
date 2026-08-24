package app.marlboroadvance.mpvex.repository.shaderlab.command

import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabGroup
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabPresetId
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabStepMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderLabCommandApiTest {
  @Test
  fun setValueClampsAndUsesCatalogRelationshipNormalization() {
    val backend = FakeBackend()
    val api = ShaderLabCommandApi(backend)

    val sdrResult = api.execute(
      ShaderLabCommand.SetValue(ShaderLabControlId.SDR_INTENSITY, 99.0)
    )
    assertTrue(sdrResult is ShaderLabCommandResult.Applied)
    assertEquals(12.0, backend.values.getValue(ShaderLabControlId.SDR_INTENSITY), 0.0)

    val pairResult = api.execute(
      ShaderLabCommand.SetValue(ShaderLabControlId.SAT_GATE_START, 0.80)
    )
    assertTrue(pairResult is ShaderLabCommandResult.Applied)
    assertEquals(0.259999, backend.values.getValue(ShaderLabControlId.SAT_GATE_START), 1e-12)
    assertEquals(0.260, backend.values.getValue(ShaderLabControlId.SAT_GATE_FULL), 0.0)

    val effect = (pairResult as ShaderLabCommandResult.Applied).effect
      as ShaderLabCommandEffect.ValuesChanged
    assertEquals(setOf(ShaderLabControlId.SAT_GATE_START), effect.values.keys)
  }

  @Test
  fun adjustUsesExactDirectionAndRequestedStepMode() {
    val backend = FakeBackend()
    val api = ShaderLabCommandApi(backend)

    api.execute(
      ShaderLabCommand.Adjust(
        control = ShaderLabControlId.BRIGHT_CHROMA,
        direction = ShaderLabAdjustDirection.INCREASE,
        stepMode = ShaderLabStepMode.FINE,
      )
    )
    assertEquals(0.253125, backend.values.getValue(ShaderLabControlId.BRIGHT_CHROMA), 1e-12)

    api.execute(
      ShaderLabCommand.Adjust(
        control = ShaderLabControlId.BRIGHT_CHROMA,
        direction = ShaderLabAdjustDirection.DECREASE,
        stepMode = ShaderLabStepMode.COARSE,
      )
    )
    assertEquals(0.243125, backend.values.getValue(ShaderLabControlId.BRIGHT_CHROMA), 1e-12)
  }

  @Test
  fun selectionCommandsAreLocalSemanticEffectsAndDoNotTouchRuntimeBackend() {
    val backend = FakeBackend()
    val api = ShaderLabCommandApi(backend)

    val group = api.execute(ShaderLabCommand.SelectGroup(ShaderLabGroup.SKIN))
    val control = api.execute(ShaderLabCommand.SelectControl(ShaderLabControlId.SKIN_RETAIN))

    assertEquals(
      ShaderLabCommandEffect.GroupSelected(ShaderLabGroup.SKIN),
      (group as ShaderLabCommandResult.Applied).effect,
    )
    assertEquals(
      ShaderLabCommandEffect.ControlSelected(ShaderLabControlId.SKIN_RETAIN, ShaderLabGroup.SKIN),
      (control as ShaderLabCommandResult.Applied).effect,
    )
    assertTrue(backend.events.isEmpty())
    assertTrue(backend.writes.isEmpty())
  }

  @Test
  fun previewLifecycleHasDistinctStartEndAndLegacyFallbackCommands() {
    val backend = FakeBackend()
    val api = ShaderLabCommandApi(backend)

    api.execute(ShaderLabCommand.PreviewOriginalStart)
    api.execute(ShaderLabCommand.PreviewOriginalEnd)
    api.execute(ShaderLabCommand.TogglePreviewOriginalFallback)

    assertEquals(
      listOf("preview:true", "preview:false", "preview-fallback"),
      backend.events,
    )
  }

  @Test
  fun typedPresetSystemAndSystemActionsRouteWithoutRawSlots() {
    val backend = FakeBackend()
    val api = ShaderLabCommandApi(backend)
    val user = ShaderLabPresetId.User(4)
    val builtIn = ShaderLabPresetId.BuiltIn(7)

    api.execute(ShaderLabCommand.SaveUserPreset(user))
    api.execute(ShaderLabCommand.LoadUserPreset(user))
    api.execute(ShaderLabCommand.ClearUserPreset(user))
    api.execute(ShaderLabCommand.LoadBuiltInPreset(builtIn))
    api.execute(ShaderLabCommand.RevertVideoStart)
    api.execute(ShaderLabCommand.ResetAll)
    api.execute(ShaderLabCommand.SaveState)
    api.execute(ShaderLabCommand.LoadState)
    api.execute(ShaderLabCommand.ToggleBypass)

    assertEquals(
      listOf(
        "user-save:4",
        "user-load:4",
        "user-clear:4",
        "builtin-load:7",
        "revert-video-start",
        "reset-all",
        "save-state",
        "load-state",
        "bypass",
      ),
      backend.events,
    )
  }

  @Test
  fun morphUsesTypedPresetIdsClampsAmountAndRejectsVideoStart() {
    val backend = FakeBackend()
    val api = ShaderLabCommandApi(backend)
    val from = ShaderLabPresetId.BuiltIn(2)
    val to = ShaderLabPresetId.User(3)

    val applied = api.execute(ShaderLabCommand.Morph(from, to, 1.5))
    assertTrue(applied is ShaderLabCommandResult.Applied)
    assertEquals(MorphCall(from, to, 1.0), backend.morphCalls.single())

    val rejected = api.execute(ShaderLabCommand.Morph(ShaderLabPresetId.VideoStart, to, 0.5))
    assertTrue(rejected is ShaderLabCommandResult.Rejected)
    assertEquals(1, backend.morphCalls.size)
  }

  @Test
  fun diagnosticViewIsTypedAndMapsToCanonicalDebugControl() {
    val backend = FakeBackend()
    val api = ShaderLabCommandApi(backend)

    val result = api.execute(ShaderLabCommand.SetDiagnosticView(ShaderLabDiagnosticView.BOTH))

    assertEquals(3.0, backend.values.getValue(ShaderLabControlId.DEBUG_VIEW), 0.0)
    assertEquals(
      ShaderLabCommandEffect.DiagnosticViewChanged(ShaderLabDiagnosticView.BOTH),
      (result as ShaderLabCommandResult.Applied).effect,
    )
  }

  @Test
  fun destructiveConfirmationPolicyReusesR05ActionMetadata() {
    assertFalse(ShaderLabCommand.ToggleBypass.requiresConfirmation())
    assertFalse(ShaderLabCommand.PreviewOriginalStart.requiresConfirmation())
    assertFalse(ShaderLabCommand.SaveState.requiresConfirmation())

    assertTrue(ShaderLabCommand.SaveUserPreset(ShaderLabPresetId.User(1)).requiresConfirmation())
    assertTrue(ShaderLabCommand.LoadUserPreset(ShaderLabPresetId.User(1)).requiresConfirmation())
    assertTrue(ShaderLabCommand.ClearUserPreset(ShaderLabPresetId.User(1)).requiresConfirmation())
    assertTrue(ShaderLabCommand.LoadBuiltInPreset(ShaderLabPresetId.BuiltIn(1)).requiresConfirmation())
    assertTrue(ShaderLabCommand.RevertVideoStart.requiresConfirmation())
    assertTrue(ShaderLabCommand.ResetAll.requiresConfirmation())
    assertTrue(ShaderLabCommand.LoadState.requiresConfirmation())
  }

  @Test
  fun nonFiniteValuesAreRejectedBeforeBackendMutation() {
    val backend = FakeBackend()
    val api = ShaderLabCommandApi(backend)

    val valueResult = api.execute(
      ShaderLabCommand.SetValue(ShaderLabControlId.LUMA_CONTRAST, Double.NaN)
    )
    val morphResult = api.execute(
      ShaderLabCommand.Morph(
        ShaderLabPresetId.BuiltIn(1),
        ShaderLabPresetId.BuiltIn(2),
        Double.POSITIVE_INFINITY,
      )
    )

    assertTrue(valueResult is ShaderLabCommandResult.Rejected)
    assertTrue(morphResult is ShaderLabCommandResult.Rejected)
    assertTrue(backend.writes.isEmpty())
    assertTrue(backend.morphCalls.isEmpty())
  }

  @Test
  fun backendExceptionsSurfaceAsTypedFailures() {
    val backend = FakeBackend().apply { failOnBypass = true }
    val api = ShaderLabCommandApi(backend)

    val result = api.execute(ShaderLabCommand.ToggleBypass)

    assertTrue(result is ShaderLabCommandResult.Failed)
    val failure = result as ShaderLabCommandResult.Failed
    assertTrue(failure.reason.contains("synthetic backend failure"))
    assertEquals(IllegalStateException::class.java.name, failure.exceptionType)
  }

  private data class MorphCall(
    val from: ShaderLabPresetId,
    val to: ShaderLabPresetId,
    val amount: Double,
  )

  private class FakeBackend : ShaderLabCommandBackend {
    val values = ShaderLabControlCatalog.defaults().toMutableMap()
    val writes = mutableListOf<Map<ShaderLabControlId, Double>>()
    val events = mutableListOf<String>()
    val morphCalls = mutableListOf<MorphCall>()
    var failOnBypass: Boolean = false

    override fun snapshotValues(): Map<ShaderLabControlId, Double> = values.toMap()

    override fun setValues(values: Map<ShaderLabControlId, Double>) {
      writes += values.toMap()
      this.values.putAll(values)
    }

    override fun toggleBypass() {
      if (failOnBypass) error("synthetic backend failure")
      events += "bypass"
    }

    override fun setPreviewOriginal(active: Boolean) {
      events += "preview:$active"
    }

    override fun togglePreviewOriginalFallback() {
      events += "preview-fallback"
    }

    override fun revertVideoStart() {
      events += "revert-video-start"
    }

    override fun resetAll() {
      events += "reset-all"
    }

    override fun saveUserPreset(preset: ShaderLabPresetId.User) {
      events += "user-save:${preset.slot}"
    }

    override fun loadUserPreset(preset: ShaderLabPresetId.User) {
      events += "user-load:${preset.slot}"
    }

    override fun clearUserPreset(preset: ShaderLabPresetId.User) {
      events += "user-clear:${preset.slot}"
    }

    override fun loadBuiltInPreset(preset: ShaderLabPresetId.BuiltIn) {
      events += "builtin-load:${preset.slot}"
    }

    override fun morph(from: ShaderLabPresetId, to: ShaderLabPresetId, amount: Double) {
      morphCalls += MorphCall(from, to, amount)
    }

    override fun saveState() {
      events += "save-state"
    }

    override fun loadState() {
      events += "load-state"
    }
  }
}
