package app.marlboroadvance.mpvex.di

import app.marlboroadvance.mpvex.repository.shaderlab.ShaderLabWorkspaceManager
import org.koin.dsl.module

val ShaderLabModule =
  module {
    single { ShaderLabWorkspaceManager(get()) }
  }
