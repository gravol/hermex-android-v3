package com.hermex.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

sealed class NetworkResult<out T> {
    data class Success<out T>(val data: T) : NetworkResult<T>()
    data class Error(val exception: Throwable) : NetworkResult<Nothing>()
    data class HttpError(val code: Int, val message: String) : NetworkResult<Nothing>()
}

/** Parse an [okhttp3.Response] into [NetworkResult], decoding the body as [T]. */
suspend fun <T> okhttp3.Response.handleResult(
    json: Json,
    serializer: KSerializer<T>,
): NetworkResult<T> {
    return try {
        val bodyString = this.body?.string() ?: ""
        if (this.isSuccessful) {
            if (bodyString.isEmpty()) {
                @Suppress("UNCHECKED_CAST")
                NetworkResult.Success(Unit as T)
            } else {
                NetworkResult.Success(json.decodeFromString(serializer, bodyString))
            }
        } else {
            NetworkResult.HttpError(this.code, bodyString)
        }
    } catch (e: Exception) {
        NetworkResult.Error(e)
    }
}
