package app.marlboroadvance.mpvex.di

import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabEngineInstaller
import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabWorkspaceManager
import org.koin.dsl.module

val ShaderLabModule =
  module {
    single { ShaderLabWorkspaceManager(get()) }
    single { ShaderLabEngineInstaller(context = get(), workspaceManager = get()) }
  }
