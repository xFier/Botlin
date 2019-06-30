package com.github.gjum.minecraft.botlin.util

import com.github.steveice10.mc.auth.data.GameProfile
import com.github.steveice10.mc.auth.service.AuthenticationService
import com.github.steveice10.mc.auth.service.ProfileService
import com.github.steveice10.mc.protocol.MinecraftProtocol
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun mcProtoFromAuth(auth: AuthenticationService): MinecraftProtocol {
    return MinecraftProtocol(auth.selectedProfile.name, auth.clientToken, auth.accessToken)
}

suspend fun lookupProfile(username: String, profileService: ProfileService): GameProfile {
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

fun splitHostPort(address: String): Pair<String, Int> {
	val split = address.split(':')
	val host = split[1]
	val port = split.getOrNull(2)?.toInt() ?: 25565
	return host to port
}
