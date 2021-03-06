package com.github.gjum.minecraft.botlin.api

sealed class Result<T, E> {
	open val value: T? get() = null
	open val error: E? get() = null

	class Success<T, E>(override val value: T) : Result<T, E>()
	class Failure<T, E>(override val error: E) : Result<T, E>()

	operator fun component1(): T? = value
	operator fun component2(): E? = error
}

fun <T, E : Throwable> Result<T, E>.getOrThrow(): T {
	if (error != null) throw error!!
	return value!!
}

fun <T, EO, EI : EO> Result<Result<T, EI>, out EO>.flatten(): Result<T, out EO> {
	return when (this) {
		is Result.Success -> value
		is Result.Failure -> Result.Failure(error)
	}
}
