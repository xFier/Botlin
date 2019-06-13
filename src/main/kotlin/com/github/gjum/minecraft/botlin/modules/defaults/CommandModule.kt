package com.github.gjum.minecraft.botlin.modules.defaults

import com.github.gjum.minecraft.botlin.api.CommandService
import com.github.gjum.minecraft.botlin.api.Module
import com.github.gjum.minecraft.botlin.modules.ServiceRegistry

class CommandModule : Module() {
    override val name = "Commands"
    override val description = "Provides command registration and execution"

    override suspend fun initialize(serviceRegistry: ServiceRegistry, oldModule: Module?) {
        val commandRegistry = CommandRegistry()
        serviceRegistry.provideService(CommandService::class.java, commandRegistry)
    }
}
