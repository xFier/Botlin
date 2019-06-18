package com.github.gjum.minecraft.botlin.modules.defaults

import com.github.gjum.minecraft.botlin.api.Authentication
import com.github.gjum.minecraft.botlin.api.Module
import com.github.gjum.minecraft.botlin.modules.ReloadableServiceRegistry
import com.github.gjum.minecraft.botlin.util.RateLimiter
import com.github.steveice10.mc.auth.exception.request.InvalidCredentialsException
import com.github.steveice10.mc.auth.service.AuthenticationService
import com.github.steveice10.mc.protocol.MinecraftProtocol
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val mcPasswordEnv = "MINECRAFT_PASSWORD"

private class AuthTokenCache(val clientToken: String, val sessions: MutableMap<String, String> = mutableMapOf())

private class AuthenticationProvider(
    private val credentialsPath: String,
    private val authCachePath: String
) : Authentication {
    private val logger: Logger = Logger.getLogger(this::class.java.name)
    private val rateLimiters = mutableMapOf<String, RateLimiter>()

    override val defaultAccount: String?
        get () {
            return System.getProperty("mcUsername")
                ?: System.getenv("MINECRAFT_USERNAME")
                ?: availableAccounts.firstOrNull()
        }

    override val availableAccounts: Collection<String>
        get() {
            TODO("check authCache and .credentials") // XXX
        }

    override suspend fun authenticate(username: String): MinecraftProtocol? {
        rateLimiters.getOrPut(username, { RateLimiter(RateParamsFromSysProps()) })
            .runWithRateLimit { null to false } // XXX rate limiter api sucks, rework

        var cToken: String = UUID.randomUUID().toString() // TODO allow providing default value

        // try loading cached token, and maybe client token
        val authCache = readAuthTokenCache(authCachePath)
        if (authCache == null) {
            logger.fine("Could not read auth cache from $authCachePath")
        } else {
            cToken = authCache.clientToken
            val aToken = authCache.sessions[username]
            if (aToken == null || aToken.isEmpty()) {
                logger.info("Could not read auth token from $authCachePath")
            } else {
                try {
                    val auth = AuthenticationService(cToken)
                    auth.username = username
                    auth.accessToken = aToken
                    return tryLoginAndCacheToken(auth)
                } catch (e: InvalidCredentialsException) {
                    logger.info("Could not reuse auth token from $authCachePath")
                }
            }
        }

        // try password from env
        val envPassword = System.getenv(mcPasswordEnv) ?: ""
        if (envPassword.isNotEmpty()) {
            try {
                val auth = AuthenticationService(cToken)
                auth.username = username
                auth.password = envPassword
                return tryLoginAndCacheToken(auth)
            } catch (e: InvalidCredentialsException) {
                logger.warning("Invalid password provided via env ($mcPasswordEnv)")
            }
        }

        // try password from .credentials
        val credentials = readCredentialsFile(credentialsPath)
        if (credentials == null) {
            logger.info("Failed to authenticate $username: Could not read credentials from $credentialsPath and no password provided via env ($mcPasswordEnv)")
            return null
        }

        val credPassword = credentials[username] ?: ""
        if (credPassword.isEmpty()) {
            logger.info("Failed to authenticate $username: No password provided via env ($mcPasswordEnv) or $credentialsPath")
            return null
        }

        try {
            val auth = AuthenticationService(cToken)
            auth.username = username
            auth.password = credPassword
            return tryLoginAndCacheToken(auth)
        } catch (e: InvalidCredentialsException) {
            logger.warning("Failed to authenticate $username: Invalid password provided via $credentialsPath")
            return null
        }
    }

    @Throws(InvalidCredentialsException::class)
    private suspend fun tryLoginAndCacheToken(auth: AuthenticationService): MinecraftProtocol {
        runOnThread { auth.login() } // this may throw, in which case no token is cached
        // success!
        cacheToken(authCachePath, auth)
        return mcProtoFromAuth(auth)
    }

    private fun cacheToken(authCachePath: String, auth: AuthenticationService, authCacheArg: AuthTokenCache? = null) {
        val authCache = authCacheArg ?: AuthTokenCache(auth.clientToken)
        authCache.sessions[auth.username] = auth.accessToken
        try {
            Files.write(Paths.get(authCachePath), Gson().toJson(authCache).toByteArray())
        } catch (e: IOException) {
            logger.warning("Could not write auth token cache to $authCachePath")
        }
    }
}

class AuthModule : Module() {
    override suspend fun initialize(serviceRegistry: ReloadableServiceRegistry, oldModule: Module?) {
        val auth = AuthenticationProvider(
            System.getProperty("mcAuthCredentials") ?: ".credentials",
            System.getProperty("mcAuthCache") ?: ".auth_tokens.json"
        )
        serviceRegistry.provideService(Authentication::class.java, auth)
    }
}

private class RateParamsFromSysProps : RateLimiter.Params {
    override var backoffStart = System.getProperty(
        "authBackoffStart")?.toIntOrNull()
        ?: 1000
    override var backoffFactor = System.getProperty(
        "authBackoffFactor")?.toFloatOrNull()
        ?: 2f
    override var backoffEnd = System.getProperty(
        "authBackoffEnd")?.toIntOrNull()
        ?: 30_000
    override var connectRateLimit = System.getProperty(
        "authRateLimit")?.toIntOrNull()
        ?: 5
    override var connectRateInterval = System.getProperty(
        "authRateInterval")?.toIntOrNull()
        ?: 60_000
}

private fun readAuthTokenCache(authCachePath: String): AuthTokenCache? {
    val configStr: String
    try {
        configStr = String(Files.readAllBytes(Paths.get(authCachePath)))
    } catch (e: IOException) {
        return null
    }
    if (configStr.isEmpty()) {
        return null
    }
    return Gson().fromJson(configStr, AuthTokenCache::class.java)
}

private fun readCredentialsFile(credentialsPath: String): Map<String, String>? {
    val credentialsLines: List<String>
    try {
        credentialsLines = Files.readAllLines(Paths.get(credentialsPath))
    } catch (e: IOException) {
        return null
    }
    if (credentialsLines.isEmpty()) {
        return null
    }
    return parseCredentialsFile(credentialsLines)
}

internal fun parseCredentialsFile(lines: List<String>): Map<String, String> {
    return lines
        .filter { line -> line.isNotEmpty() }
        .map { line ->
            val split = line.split(" ", limit = 2)
            split[0] to split[1]
        }.toMap()
}

private fun mcProtoFromAuth(auth: AuthenticationService): MinecraftProtocol {
    return MinecraftProtocol(auth.selectedProfile.name, auth.clientToken, auth.accessToken)
}

private suspend inline fun <T> runOnThread(crossinline block: () -> T): T {
    var t: Thread? = null
    try {
        return suspendCancellableCoroutine { cont ->
            synchronized(cont) {
                t = thread(start = true) {
                    synchronized(cont) {
                        if (cont.isCancelled) return@thread
                    }
                    try {
                        cont.resume(block())
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Throwable) {
                        cont.resumeWithException(e)
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        t?.interrupt()
        throw e
    }
}
