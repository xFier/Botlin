package com.github.gjum.minecraft.botlin.modules.defaults

import com.github.gjum.minecraft.botlin.api.*
import com.github.gjum.minecraft.botlin.modules.ServiceRegistry
import com.github.gjum.minecraft.botlin.state.AvatarImpl
import com.github.gjum.minecraft.botlin.state.normalizeServerAddress
import com.github.steveice10.mc.auth.data.GameProfile
import com.github.steveice10.mc.auth.service.ProfileService
import com.github.steveice10.packetlib.Client
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// TODO method to forget avatar?
class AvatarProvider(private val avatars: MutableMap<String, Avatar>) : Avatars {
    private val profileService = ProfileService()

    override suspend fun getAvatar(username: String, serverAddress: String): Avatar {
        val serverAddressNorm = normalizeServerAddress(serverAddress) // XXX
        return avatars.getOrPut("$username@$serverAddressNorm") {
            val profile: GameProfile = lookupProfile(username)
            AvatarImpl(profile, serverAddressNorm)
        }
    }

    override val availableAvatars get() = avatars.values

    private suspend fun lookupProfile(username: String): GameProfile {
        return suspendCancellableCoroutine { cont ->
            profileService.findProfilesByName(arrayOf(username),
                object : ProfileService.ProfileLookupCallback {
                    override fun onProfileLookupSucceeded(profile: GameProfile?) {
                        cont.resume(profile!!)
                    }

                    override fun onProfileLookupFailed(profile: GameProfile?, e: Exception?) {
                        cont.resumeWithException(e!!)
                    }
                }
            )
        }
    }
}

// TODO hot reload should retain previous avatars' state
class AvatarModule : Module() {
    private val avatars = AvatarProvider(mutableMapOf())

    override suspend fun initialize(serviceRegistry: ServiceRegistry, oldModule: Module?) {
        serviceRegistry.provideService(Avatars::class.java,
            avatars)

        val commands = serviceRegistry.consumeService(CommandService::class.java)
        commands ?: return
        commands.registerCommand("logout",
            "logout [username=all] [server=all]",
            "Disconnect account(s) from server(s)."
        ) { cmdLine, context ->
            val split = cmdLine.split(" +".toRegex())
            val (servers, usernames) = split.drop(1).partition { '.' in it || ':' in it }
            // TODO this is convenient and generalized, but kind of inefficient
            for (avatar in avatars.availableAvatars) {
                if (usernames.isNotEmpty() && avatar.profile.name !in usernames) continue
                if (servers.isNotEmpty() && avatar.serverAddress !in servers) continue
                avatar.disconnect("logout command")
            }
        }

        val auth = serviceRegistry.consumeService(Authentication::class.java)
        auth ?: return
        commands.registerCommand("connect",
            "connect [username=default] [server=localhost:25565]",
            "Connect account to server with its current behavior."
        ) { cmdLine, context ->
            val split = cmdLine.split(" +".toRegex())
            val (servers, users) = split.drop(1).partition { '.' in it || ':' in it }
            val serverAddress = servers.getOrElse(0) { TODO("previous server") }
            val username = users.getOrElse(0) { auth.defaultAccount } ?: run {
                context.respond("Not connecting: no username given and no default account available")
                return@registerCommand
            }
            val proto = runBlocking { auth.authenticate(username) } ?: run {
                context.respond("Failed to authenticate $username")
                return@registerCommand
            }
            runBlocking {
                val avatar = avatars.getAvatar(username, serverAddress)
                avatar.useProtocol(proto)
            }
        }
    }
}
