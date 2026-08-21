package app.marlboroadvance.mpvex.di

import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstaller
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabWorkspaceManager
import app.marlboroadvance.mpvex.repository.shaderlab.bridge.MpvShaderLabBridge
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandApi
import app.marlboroadvance.mpvex.repository.shaderlab.command.ShaderLabCommandBackend
import org.koin.dsl.module

val ShaderLabModule =
  module {
    single { ShaderLabWorkspaceManager(get()) }
    single { ShaderLabEngineInstaller(context = get(), workspaceManager = get()) }
    single { MpvShaderLabBridge(get<ShaderLabEngineInstaller>()) }
    single<ShaderLabCommandBackend> { get<MpvShaderLabBridge>() }
    single { ShaderLabCommandApi(get()) }
  }
