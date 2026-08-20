package app.marlboroadvance.mpvex.repository.shaderlab.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ShaderLabControlCatalogTest {
  @Test
  fun legacyValueControlKeysMatchExactly() {
    val expected = setOf(
      "SHADER_PROOF", "TOUCH_GRANULARITY", "LUMA_MASTER", "CHROMA_MASTER",
      "sdr-intensity", "brightness", "contrast", "gamma", "saturation", "hue",
      "LUMA_PIVOT", "LUMA_CONTRAST", "LUMA_HIGHLIGHT_START", "LUMA_HIGHLIGHT_END", "LUMA_HIGHLIGHT",
      "SAT_L_FLOOR", "SAT_GATE_START", "SAT_GATE_FULL", "SHADOW_GATE_START", "SHADOW_GATE_FULL",
      "MIDTONE_START", "MIDTONE_FULL", "MIDTONE_FADE_START", "MIDTONE_FADE_END", "BRIGHT_START", "BRIGHT_FULL",
      "BASE_CHROMA", "MID_CHROMA", "BRIGHT_CHROMA",
      "SKIN_RETAIN", "SKIN_CENTER", "SKIN_HUE_INNER", "SKIN_HUE_OUTER",
      "SKIN_L_LOW_START", "SKIN_L_LOW_FULL", "SKIN_L_HIGH_START", "SKIN_L_HIGH_END",
      "SKIN_C_LOW_START", "SKIN_C_LOW_FULL", "SKIN_C_HIGH_START", "SKIN_C_HIGH_END",
      "RGB_LOW", "RGB_HIGH", "GAMUT_MARGIN", "GAMUT_ITERATIONS",
      "SDR_COMPRESS", "DEBUG_VIEW", "GRAPH_VIEW", "USER_SLOT", "BUILTIN_SLOT",
      "MORPH_FROM", "MORPH_TO", "MORPH_AMOUNT",
    )

    assertEquals(53, ShaderLabControlCatalog.controls.size)
    assertEquals(expected, ShaderLabControlCatalog.byLegacyKey.keys)
  }

  @Test
  fun legacyActionOnlyEntriesAreAccountedForExactly() {
    val expected = setOf(
      "BYPASS_ACTION", "PREVIEW_ACTION", "LOAD_USER", "SAVE_USER", "CLEAR_USER", "LOAD_BUILTIN",
      "REVERT_VIDEO_START", "RESET_ALL_MENU", "SAVE_STATE_MENU", "LOAD_STATE_MENU",
    )

    assertEquals(10, ShaderLabControlCatalog.actions.size)
    assertEquals(expected, ShaderLabControlCatalog.actionsByLegacyKey.keys)
    assertTrue(ShaderLabControlCatalog.actionsByLegacyKey.getValue("CLEAR_USER").destructive)
    assertFalse(ShaderLabControlCatalog.actionsByLegacyKey.getValue("SAVE_STATE_MENU").destructive)
  }

  @Test
  fun clampsIntegerAndContinuousControls() {
    val iterations = ShaderLabControlCatalog.spec(ShaderLabControlId.GAMUT_ITERATIONS)
    assertEquals(7.0, iterations.clamp(6.6), 0.0)
    assertEquals(12.0, iterations.clamp(50.0), 0.0)
    assertEquals("7", iterations.format(6.6))

    val sdr = ShaderLabControlCatalog.spec(ShaderLabControlId.SDR_INTENSITY)
    assertEquals(0.10, sdr.clamp(-2.0), 0.0)
    assertEquals(12.0, sdr.clamp(20.0), 0.0)
    assertEquals("4.16", sdr.format(4.16))

    val compress = ShaderLabControlCatalog.spec(ShaderLabControlId.SDR_COMPRESS)
    assertEquals("25%", compress.format(0.25))
  }

  @Test
  fun preservesExactStepSizesAndHighPrecisionConstants() {
    val brightChroma = ShaderLabControlCatalog.spec(ShaderLabControlId.BRIGHT_CHROMA)
    assertEquals(0.0005, brightChroma.step(ShaderLabStepMode.FINE), 0.0)
    assertEquals(0.0025, brightChroma.step(ShaderLabStepMode.NORMAL), 0.0)
    assertEquals(0.01, brightChroma.step(ShaderLabStepMode.COARSE), 0.0)
    assertEquals(0.252625, brightChroma.defaultValue, 0.0)
    assertEquals("0.252625", brightChroma.format(brightChroma.defaultValue))

    val skinCenter = ShaderLabControlCatalog.spec(ShaderLabControlId.SKIN_CENTER)
    assertEquals(-3.14159265, skinCenter.minValue, 0.0)
    assertEquals(3.14159265, skinCenter.maxValue, 0.0)

    val rgbLow = ShaderLabControlCatalog.spec(ShaderLabControlId.RGB_LOW)
    assertEquals(0.00001, rgbLow.fineStep, 0.0)
    assertEquals(0.00005, rgbLow.defaultValue, 0.0)
  }

  @Test
  fun orderedPairsAreDataDrivenAndChangedControlWins() {
    val relations = ShaderLabControlCatalog.relationships
      .filterIsInstance<ShaderLabControlRelationship.OrderedPair>()
    assertEquals(12, relations.size)

    val lowerChanged = ShaderLabControlCatalog.normalizeValues(
      mapOf(
        ShaderLabControlId.SAT_GATE_START to 0.40,
        ShaderLabControlId.SAT_GATE_FULL to 0.20,
      ),
      changedId = ShaderLabControlId.SAT_GATE_START,
    )
    assertEquals(0.20, lowerChanged.getValue(ShaderLabControlId.SAT_GATE_FULL), 0.0)
    assertEquals(0.199999, lowerChanged.getValue(ShaderLabControlId.SAT_GATE_START), 1e-12)

    val upperChanged = ShaderLabControlCatalog.normalizeValues(
      mapOf(
        ShaderLabControlId.SAT_GATE_START to 0.40,
        ShaderLabControlId.SAT_GATE_FULL to 0.20,
      ),
      changedId = ShaderLabControlId.SAT_GATE_FULL,
    )
    assertEquals(0.40, upperChanged.getValue(ShaderLabControlId.SAT_GATE_START), 0.0)
    assertEquals(0.400001, upperChanged.getValue(ShaderLabControlId.SAT_GATE_FULL), 1e-12)
  }

  @Test
  fun virtualMasterDependenciesAreDataDriven() {
    val scales = ShaderLabControlCatalog.relationships
      .filterIsInstance<ShaderLabControlRelationship.ScaledBy>()
    assertEquals(5, scales.size)

    val luma = ShaderLabControlCatalog.effectiveBackendValue(
      ShaderLabControlId.LUMA_CONTRAST,
      mapOf(
        ShaderLabControlId.LUMA_CONTRAST to 0.50,
        ShaderLabControlId.LUMA_MASTER to 1.50,
      ),
    )
    assertEquals(0.75, luma, 0.0)

    val chroma = ShaderLabControlCatalog.effectiveBackendValue(
      ShaderLabControlId.BRIGHT_CHROMA,
      mapOf(
        ShaderLabControlId.BRIGHT_CHROMA to 0.20,
        ShaderLabControlId.CHROMA_MASTER to 2.0,
      ),
    )
    assertEquals(0.40, chroma, 0.0)
  }

  @Test
  fun presetIdsAndBuiltInsAreTypedAndBounded() {
    assertEquals(
      listOf(
        "V3.1 Reference", "Natural Plus", "Vivid Clean", "Cinema", "Daylight Punch",
        "Dark Room", "Animation", "Skin Priority", "Highlight Pop", "SDR Safe",
      ),
      ShaderLabControlCatalog.builtInPresets.map { it.name },
    )
    assertEquals((1..10).toList(), ShaderLabControlCatalog.builtInPresets.map { it.id.slot })

    assertThrows(IllegalArgumentException::class.java) { ShaderLabPresetId.User(0) }
    assertThrows(IllegalArgumentException::class.java) { ShaderLabPresetId.BuiltIn(11) }
  }

  @Test
  fun controllerOnlyAndDiagnosticViewMetadataIsNotPresetEligible() {
    assertFalse(ShaderLabControlCatalog.spec(ShaderLabControlId.DEBUG_VIEW).presetEligible)
    assertFalse(ShaderLabControlCatalog.spec(ShaderLabControlId.GRAPH_VIEW).presetEligible)
    assertFalse(ShaderLabControlCatalog.spec(ShaderLabControlId.USER_SLOT).presetEligible)
    assertFalse(ShaderLabControlCatalog.spec(ShaderLabControlId.MORPH_AMOUNT).presetEligible)
    assertTrue(ShaderLabControlCatalog.spec(ShaderLabControlId.LUMA_CONTRAST).presetEligible)
  }
}
