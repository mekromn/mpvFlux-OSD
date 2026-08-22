package app.marlboroadvance.mpvex.repository.shaderlab.bridge

import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlId
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommand
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandApi
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandResult
import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabPresetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvShaderLabBridgeTest {
  @Test
  fun preInitPreparationReturnsCanonicalScriptAndDoesNotReprepareOnAttach() {
    var prepareCount = 0
    val bridge =
      MpvShaderLabBridge(
        transport = FakeTransport(),
        prepareEngine = { prepareCount += 1 },
      )

    assertEquals(MpvShaderLabBridge.CONTROLLER_PATH, bridge.prepareForMpvInitialization())
    assertEquals(1, prepareCount)

    bridge.attach()

    assertEquals(1, prepareCount)
  }

  @Test
  fun preInitPreparationFailureReturnsNullAndSurfacesObservableError() {
    val bridge =
      MpvShaderLabBridge(
        transport = FakeTransport(),
        prepareEngine = { error("synthetic preinit failure") },
      )

    assertEquals(null, bridge.prepareForMpvInitialization())
    assertEquals("synthetic preinit failure", bridge.state.value.lastError)
  }

  @Test
  fun attachObservesStateGammaAndSixMpvPropertiesAndRequestsHandshake() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)

    bridge.attach()

    assertTrue(bridge.state.value.connected)
    assertTrue(MpvShaderLabBridge.NATIVE_STATE_PROPERTY in transport.stringProperties)
    assertTrue(MpvShaderLabBridge.USER_DATA_ROOT_PROPERTY in transport.stringProperties)
    assertTrue(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY in transport.stringProperties)
    assertEquals(
      setOf("sdr-intensity", "brightness", "contrast", "gamma", "saturation", "hue"),
      transport.doubleProperties,
    )
    assertTrue(
      transport.commands.contains(
        listOf("load-script", MpvShaderLabBridge.CONTROLLER_PATH),
      ),
    )
    assertEquals(listOf("script-message", "p9lab-native-state"), transport.commands.last())
  }

  @Test
  fun attachReusesExistingPublisherAndRecoversNestedUserDataState() {
    val transport = FakeTransport().apply {
      strings[MpvShaderLabBridge.USER_DATA_ROOT_PROPERTY] =
        """{"p9lab":{"ui-visible":"no","native-state":"__ready=1\n__version=6.1.1-r07-state-3\n__serial=11\n__bank=B"}}"""
    }
    val bridge = MpvShaderLabBridge(transport)

    bridge.attach()

    assertTrue(bridge.state.value.ready)
    assertEquals("6.1.1-r07-state-3", bridge.state.value.backendVersion)
    assertEquals(11L, bridge.state.value.snapshotSerial)
    assertEquals(ShaderLabBank.B, bridge.state.value.activeBank)
    assertFalse(transport.commands.contains(listOf("load-script", MpvShaderLabBridge.CONTROLLER_PATH)))
    assertTrue(transport.commands.none { it == listOf("script-message", "p9lab-native-state") })
  }

  @Test
  fun nativeSnapshotDecodesObservableStateAndResidentValues() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()

    transport.emitText(
      MpvShaderLabBridge.NATIVE_STATE_PROPERTY,
      """
      __ready=1
      __version=6.1.1-r07-state-3
      __serial=42
      __bank=A
      __bypassed=0
      __preview=0
      __sdr=1
      __source_gamma=bt.1886
      __shader_slot=B
      __swaps=0
      __apply_busy=0
      __user1=1
      __user10=1
      brightness=12.5
      LUMA_CONTRAST=0.333
      USER_SLOT=10
      """.trimIndent(),
    )

    val state = bridge.state.value
    assertTrue(state.connected)
    assertTrue(state.ready)
    assertEquals(42L, state.snapshotSerial)
    assertEquals(ShaderLabSourceKind.SDR, state.sourceKind)
    assertEquals(setOf(1, 10), state.userPresetOccupied)
    assertEquals(12.5, state.values.getValue(ShaderLabControlId.MPV_BRIGHTNESS), 0.0)
    assertEquals(0.333, state.values.getValue(ShaderLabControlId.LUMA_CONTRAST), 0.0)
    assertEquals(10.0, state.values.getValue(ShaderLabControlId.USER_SLOT), 0.0)
  }

  @Test
  fun sourceClassificationAttachesResidentOnlyForSdrAndRemovesItForHdr() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()
    transport.commands.clear()

    transport.emitText(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY, "bt.1886")
    assertEquals(ShaderLabSourceKind.SDR, bridge.state.value.sourceKind)
    assertTrue(bridge.state.value.sdrEligible)
    assertTrue(
      transport.commands.contains(
        listOf("change-list", "glsl-shaders", "append", ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH),
      ),
    )

    transport.commands.clear()
    transport.emitText(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY, "pq")
    assertEquals(ShaderLabSourceKind.HDR_PQ, bridge.state.value.sourceKind)
    assertFalse(bridge.state.value.sdrEligible)
    assertTrue(
      transport.commands.contains(
        listOf("change-list", "glsl-shaders", "remove", ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH),
      ),
    )
    assertFalse(transport.commands.any { it.getOrNull(2) == "append" })
  }

  @Test
  fun normalShaderAndMpvTuningBypassesLuaAndNeverMutatesShaderList() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()
    transport.commands.clear()

    bridge.setValues(
      linkedMapOf(
        ShaderLabControlId.MPV_BRIGHTNESS to 4.25,
        ShaderLabControlId.LUMA_CONTRAST to 0.31,
        ShaderLabControlId.GAMUT_ITERATIONS to 9.0,
      ),
    )

    assertTrue(transport.commands.contains(listOf("set", "brightness", "4.2500000000000000")))
    val optsCommand = transport.commands.single { it.getOrNull(1) == ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY }
    assertEquals("set", optsCommand[0])
    assertTrue(optsCommand[2].contains("SHADER_PROOF=0"))
    assertTrue(optsCommand[2].contains("LUMA_CONTRAST=0.31000000000000000"))
    assertTrue(optsCommand[2].contains("GAMUT_ITERATIONS=9"))
    assertEquals(40, optsCommand[2].split(',').size)
    assertFalse(transport.commands.any { it.firstOrNull() == "change-list" })
    assertFalse(transport.commands.any { it.getOrNull(1) == "p9lab-native-set" })
    assertEquals(0.31, bridge.state.value.values.getValue(ShaderLabControlId.LUMA_CONTRAST), 0.0)
  }

  @Test
  fun controllerCompatibilityValuesStillUseLuaWithoutSendingShaderOpts() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()
    transport.commands.clear()

    bridge.setValues(mapOf(ShaderLabControlId.TOUCH_GRANULARITY to 3.0))

    assertTrue(
      transport.commands.contains(
        listOf("script-message", "p9lab-native-set", "TOUCH_GRANULARITY", "3.0000000000000000"),
      ),
    )
    assertFalse(transport.commands.any { it.getOrNull(1) == ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY })
  }

  @Test
  fun bypassAndPreviewLuaStateAlsoControlsResidentShaderVisibility() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()
    transport.emitText(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY, "bt.1886")
    transport.commands.clear()

    bridge.toggleBypass()
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-bypass")))

    transport.emitText(
      MpvShaderLabBridge.NATIVE_STATE_PROPERTY,
      "__ready=1\n__serial=2\n__sdr=1\n__source_gamma=bt.1886\n__bypassed=1\n__preview=0\n__swaps=0\n__apply_busy=0",
    )
    assertTrue(
      transport.commands.contains(
        listOf("change-list", "glsl-shaders", "remove", ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH),
      ),
    )

    transport.commands.clear()
    transport.emitText(
      MpvShaderLabBridge.NATIVE_STATE_PROPERTY,
      "__ready=1\n__serial=3\n__sdr=1\n__source_gamma=bt.1886\n__bypassed=0\n__preview=0\n__swaps=0\n__apply_busy=0",
    )
    assertTrue(
      transport.commands.contains(
        listOf("change-list", "glsl-shaders", "append", ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH),
      ),
    )
  }

  @Test
  fun legacyPresetBoundaryAdoptsValuesThenRestoresSingleResidentShader() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()
    transport.emitText(MpvShaderLabBridge.SOURCE_GAMMA_PROPERTY, "bt.1886")
    transport.commands.clear()

    bridge.loadBuiltInPreset(ShaderLabPresetId.BuiltIn(6))
    assertTrue(transport.commands.contains(listOf("script-message", "p9lab-native-builtin-load", "6")))

    transport.emitText(
      MpvShaderLabBridge.NATIVE_STATE_PROPERTY,
      "__ready=1\n__serial=4\n__sdr=1\n__source_gamma=bt.1886\n__swaps=1\n__apply_busy=0\nLUMA_CONTRAST=0.456",
    )

    assertEquals(0.456, bridge.state.value.values.getValue(ShaderLabControlId.LUMA_CONTRAST), 0.0)
    assertTrue(transport.commands.any { it.getOrNull(2) == "append" && it.getOrNull(3) == ShaderLabResidentGpuTransport.RESIDENT_SHADER_PATH })
    assertTrue(
      transport.commands.any {
        it.getOrNull(1) == ShaderLabResidentGpuTransport.GLSL_SHADER_OPTS_PROPERTY &&
          it.getOrNull(2)?.contains("LUMA_CONTRAST=0.45600000000000000") == true
      },
    )
  }

  @Test
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
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()

    transport.emitNumber("brightness", 18.25)
    transport.emitNumber("sdr-intensity", 4.75)

    assertEquals(18.25, bridge.state.value.values.getValue(ShaderLabControlId.MPV_BRIGHTNESS), 0.0)
    assertEquals(4.75, bridge.state.value.values.getValue(ShaderLabControlId.SDR_INTENSITY), 0.0)
  }

  @Test
  fun commandTransportFailureBecomesTypedFailureAndObservableBackendError() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()
    transport.failCommands = true

    val result = ShaderLabCommandApi(bridge).execute(ShaderLabCommand.ToggleBypass)

    assertTrue(result is ShaderLabCommandResult.Failed)
    assertEquals("synthetic transport failure", bridge.state.value.lastError)
    assertFalse(bridge.state.value.applyBusy)
  }

  @Test
  fun detachClearsReadinessWithoutDestroyingLastKnownValues() {
    val transport = FakeTransport()
    val bridge = MpvShaderLabBridge(transport)
    bridge.attach()
    transport.emitNumber("brightness", 9.0)

    bridge.detach()

    assertFalse(bridge.state.value.connected)
    assertFalse(bridge.state.value.ready)
    assertEquals(9.0, bridge.state.value.values.getValue(ShaderLabControlId.MPV_BRIGHTNESS), 0.0)
    assertEquals(1, transport.detachCount)
  }

  private class FakeTransport : ShaderLabMpvTransport {
    var listener: ((String, ShaderLabMpvValue) -> Unit)? = null
    val stringProperties = linkedSetOf<String>()
    val doubleProperties = linkedSetOf<String>()
    val commands = mutableListOf<List<String>>()
    val strings = mutableMapOf<String, String>()
    val doubles = mutableMapOf<String, Double>()
    var detachCount = 0
    var failCommands = false

    override fun attach(listener: (String, ShaderLabMpvValue) -> Unit) {
      this.listener = listener
    }

    override fun detach() {
      detachCount += 1
      listener = null
    }

    override fun observeString(property: String) {
      stringProperties += property
    }

    override fun observeDouble(property: String) {
      doubleProperties += property
    }

    override fun getString(property: String): String? = strings[property]

    override fun getDouble(property: String): Double? = doubles[property]

    override fun command(vararg args: String) {
      if (failCommands) error("synthetic transport failure")
      commands += args.toList()
    }

    fun emitText(property: String, value: String) {
      strings[property] = value
      listener?.invoke(property, ShaderLabMpvValue.Text(value))
    }

    fun emitNumber(property: String, value: Double) {
      doubles[property] = value
      listener?.invoke(property, ShaderLabMpvValue.Number(value))
    }
  }
}
