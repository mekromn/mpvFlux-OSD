from pathlib import Path


def must_replace(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))


bridge = Path("app/src/main/java/app/marlboroadvance/mpvex/repository/shaderlab/bridge/MpvShaderLabBridge.kt")
must_replace(
    bridge,
    """import app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog\n""",
    """import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstallState\nimport app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstaller\nimport app.marlboroadvance.mpvex.repository.shaderlab.catalog.ShaderLabControlCatalog\n""",
    "bridge installer imports",
)
must_replace(
    bridge,
    """class MpvShaderLabBridge internal constructor(\n  private val transport: ShaderLabMpvTransport,\n  private val syncProbe: ShaderLabBridgeSyncProbe = NoOpShaderLabBridgeSyncProbe,\n) : ShaderLabCommandBackend {\n  constructor() : this(LibMpvShaderLabTransport(), FileShaderLabBridgeSyncProbe())\n""",
    """class MpvShaderLabBridge internal constructor(\n  private val transport: ShaderLabMpvTransport,\n  private val syncProbe: ShaderLabBridgeSyncProbe = NoOpShaderLabBridgeSyncProbe,\n  private val prepareEngine: () -> Unit = {},\n) : ShaderLabCommandBackend {\n  constructor(engineInstaller: ShaderLabEngineInstaller) : this(\n    transport = LibMpvShaderLabTransport(),\n    syncProbe = FileShaderLabBridgeSyncProbe(),\n    prepareEngine = {\n      when (val installState = engineInstaller.installOrRepair()) {\n        is ShaderLabEngineInstallState.Success -> Unit\n        is ShaderLabEngineInstallState.Blocked ->\n          error(\"Shader Lab workspace unavailable: ${installState.workspaceState}\")\n        is ShaderLabEngineInstallState.Failure -> error(installState.reason)\n        ShaderLabEngineInstallState.Idle -> error(\"Shader Lab engine installer remained idle\")\n      }\n    },\n  )\n""",
    "bridge engine-aware constructor",
)
must_replace(
    bridge,
    """      runCatching {\n        if (attached) {\n          transport.detach()\n        }\n        transport.attach(::onObservedProperty)\n""",
    """      runCatching {\n        prepareEngine()\n        if (attached) {\n          transport.detach()\n        }\n        transport.attach(::onObservedProperty)\n""",
    "bridge engine preparation",
)
must_replace(
    bridge,
    """        transport.getString(NATIVE_STATE_PROPERTY)\n          ?.takeIf { it.isNotBlank() }\n          ?.let(::consumeNativeState)\n        transport.getString(SOURCE_GAMMA_PROPERTY)?.let(::consumeSourceGamma)\n        MPV_PROPERTY_CONTROLS.forEach { spec ->\n          transport.getDouble(spec.id.legacyKey)?.let { consumeDirectControlValue(spec.id, it) }\n        }\n\n        transport.command(\"script-message\", \"p9lab-native-state\")\n""",
    """        val nativeState = transport.getString(NATIVE_STATE_PROPERTY)\n        nativeState\n          ?.takeIf { it.isNotBlank() }\n          ?.let(::consumeNativeState)\n        transport.getString(SOURCE_GAMMA_PROPERTY)?.let(::consumeSourceGamma)\n        MPV_PROPERTY_CONTROLS.forEach { spec ->\n          transport.getDouble(spec.id.legacyKey)?.let { consumeDirectControlValue(spec.id, it) }\n        }\n\n        // R04 owns the readable controller in the canonical workspace, but its\n        // reference mpv.conf is intentionally not forced into the user's active\n        // config. R07 therefore activates the controller explicitly only when\n        // no native-state publisher is already present. This makes the bridge\n        // work on a clean install without duplicating a user-configured script.\n        if (nativeState.isNullOrBlank()) {\n          transport.command(\"load-script\", CONTROLLER_PATH)\n        }\n        transport.command(\"script-message\", \"p9lab-native-state\")\n""",
    "bridge controller activation",
)
must_replace(
    bridge,
    """    const val NATIVE_STATE_PROPERTY = \"user-data/p9lab/native-state\"\n    const val SOURCE_GAMMA_PROPERTY = \"video-params/gamma\"\n""",
    """    const val NATIVE_STATE_PROPERTY = \"user-data/p9lab/native-state\"\n    const val SOURCE_GAMMA_PROPERTY = \"video-params/gamma\"\n    const val CONTROLLER_PATH = \"/storage/emulated/0/mpv/scripts/pixel9-shader-lab.lua\"\n""",
    "bridge controller path",
)

module = Path("app/src/main/java/app/marlboroadvance/mpvex/di/ShaderLabModule.kt")
must_replace(
    module,
    """    single { MpvShaderLabBridge() }\n""",
    """    single { MpvShaderLabBridge(get<ShaderLabEngineInstaller>()) }\n""",
    "Koin engine-aware bridge",
)

test = Path("app/src/test/java/app/marlboroadvance/mpvex/repository/shaderlab/bridge/MpvShaderLabBridgeTest.kt")
must_replace(
    test,
    """    assertEquals(\n      listOf(\"script-message\", \"p9lab-native-state\"),\n      transport.commands.last(),\n    )\n  }\n\n  @Test\n  fun nativeSnapshotDecodesTypedObservableStateAndControlValues() {\n""",
    """    assertTrue(\n      transport.commands.contains(\n        listOf(\"load-script\", MpvShaderLabBridge.CONTROLLER_PATH),\n      ),\n    )\n    assertEquals(\n      listOf(\"script-message\", \"p9lab-native-state\"),\n      transport.commands.last(),\n    )\n  }\n\n  @Test\n  fun attachReusesExistingNativePublisherWithoutLoadingDuplicateController() {\n    val transport = FakeTransport().apply {\n      strings[MpvShaderLabBridge.NATIVE_STATE_PROPERTY] =\n        \"__ready=1\\n__version=6.1.1-r07-state-1\\n__serial=7\"\n    }\n    val bridge = MpvShaderLabBridge(transport)\n\n    bridge.attach()\n\n    assertTrue(bridge.state.value.ready)\n    assertEquals(7L, bridge.state.value.snapshotSerial)\n    assertFalse(\n      transport.commands.contains(\n        listOf(\"load-script\", MpvShaderLabBridge.CONTROLLER_PATH),\n      ),\n    )\n    assertEquals(\n      listOf(\"script-message\", \"p9lab-native-state\"),\n      transport.commands.last(),\n    )\n  }\n\n  @Test\n  fun enginePreparationFailureSurfacesAsBackendErrorBeforeTransportAttach() {\n    val transport = FakeTransport()\n    val bridge = MpvShaderLabBridge(\n      transport = transport,\n      prepareEngine = { error(\"synthetic engine preparation failure\") },\n    )\n\n    bridge.attach()\n\n    assertFalse(bridge.state.value.connected)\n    assertEquals(\"synthetic engine preparation failure\", bridge.state.value.lastError)\n    assertTrue(transport.commands.isEmpty())\n  }\n\n  @Test\n  fun nativeSnapshotDecodesTypedObservableStateAndControlValues() {\n""",
    "bridge activation tests",
)

print("R07 activation patch applied")
