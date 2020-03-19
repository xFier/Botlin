package com.github.gjum.minecraft.botlin

import com.github.gjum.minecraft.botlin.api.Bot
import com.github.gjum.minecraft.botlin.api.Vec3d
import com.github.gjum.minecraft.botlin.impl.*
import com.github.gjum.minecraft.botlin.util.Cli
import com.github.gjum.minecraft.botlin.util.runOnThread
import com.github.gjum.minecraft.botlin.util.toAnsi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.logging.Level
import java.util.logging.Logger
import java.util.regex.Pattern

object Main {
	@JvmStatic
	fun main(args: Array<String>) = runBlocking {
		val username = args.getOrNull(0) ?: "Botlin"
		val address = args.getOrNull(1)

		val bot = setupBot(username, listOf(::EventLogger))

		val commandRegistry = CommandRegistryImpl()
		registerUsefulCommands(commandRegistry, bot, this)

		launch {
			try {
				runOnThread {
					Cli.run {
						handleCommand(it, commandRegistry)
					}
				}
			} catch (e: Throwable) { // and rethrow
				commandLogger.log(Level.SEVERE, "Error in CLI: $e", e)
				throw e
			} finally {
				Cli.stop()
				// TODO emit some endRequested event
			}
		}

		if (address != null) bot.connect(address)
	}
}

private fun handleCommand(cmdLineRaw: String, commands: CommandRegistry) {
	val cmdLineClean = cmdLineRaw.trim()
	if (cmdLineClean.isEmpty()) return
	val isSlashCommand = cmdLineClean.getOrNull(0)?.equals('/') == true
	val cmdLine = if (isSlashCommand) "say $cmdLineClean" else cmdLineClean
	val cmdName = cmdLine.substringBefore(' ')
	val context = LoggingCommandContext(cmdName)
	try {
		if (!commands.executeCommand(cmdLine, context)) {
			commandLogger.warning("Unknown command: $cmdLine")
		}
	} catch (e: Throwable) {
		context.respond("Error: $e")
		commandLogger.log(Level.WARNING, "Error while running command '$cmdName'", e)
	}
}

private val commandLogger = Logger.getLogger("com.github.gjum.minecraft.botlin.Commands")

private class LoggingCommandContext(val cmdName: String) : CommandContext {
	override fun respond(message: String) {
		commandLogger.info("[$cmdName] $message")
	}
}
