package app.marlboroadvance.mpvex.di

import android.content.Context
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstaller
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabWorkspaceManager
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.MpvShaderLabBridge
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.createR08InstrumentedMpvShaderLabBridge
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandApi
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandBackend
import app.marlboroadvance.mpvex.ui.player.controls.ShaderLabUiController
import org.koin.dsl.module

val ShaderLabModule =
  module {
    single { ShaderLabWorkspaceManager(get()) }
    single { ShaderLabEngineInstaller(context = get(), workspaceManager = get()) }
    single {
      createR08InstrumentedMpvShaderLabBridge(
        context = get<Context>(),
        engineInstaller = get<ShaderLabEngineInstaller>(),
      )
    }
    single<ShaderLabCommandBackend> { get<MpvShaderLabBridge>() }
    single { ShaderLabCommandApi(get()) }
    single { ShaderLabUiController() }
  }
