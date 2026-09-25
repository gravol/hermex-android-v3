package com.hermex.core.network

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * OkHttp Interceptor that writes every request/response to [DebugLog].
 * Redacts the Bearer token value — only "Authorization: Bearer [present]" is logged.
 */
class DebugLoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        // Log request
        val redactedHeaders = request.headers.joinToString("\n") { header ->
            if (header.first.equals("Authorization", ignoreCase = true)) {
                "Authorization: Bearer [present]"
            } else {
                "${header.first}: ${header.second}"
            }
        }

        val bodyPreview = if (request.body != null && request.body!!.contentType()?.toString()?.contains("json") == true) {
            try {
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                buffer.readUtf8().take(500)
            } catch (_: Exception) {
                "[binary body]"
            }
        } else {
            null
        }

        DebugLog.req(
            method = request.method,
            url = request.url.toString(),
            headers = redactedHeaders + if (bodyPreview != null) "\nBody: $bodyPreview" else "",
        )

        // Execute
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            DebugLog.error("HTTP", "Request failed: ${e.message}", e)
            throw e
        }

        val elapsed = System.currentTimeMillis() - startTime

        // Log response — skip body peeking for SSE streams (chat/stream).
        // peekBody(Long.MAX_VALUE) blocks until the entire stream arrives,
        // which defeats incremental SSE delivery.
        val isStreamingResponse = response.request.url.encodedPath.endsWith("/chat/stream")
        val responseBodyStr = if (isStreamingResponse) {
            "[SSE stream — body skipped]"
        } else {
            try {
                response.peekBody(2000).string()
            } catch (_: Exception) {
                "[unreadable body]"
            }
        }

        DebugLog.resp(
            code = response.code,
            url = "${request.method} ${request.url} (${elapsed}ms)",
            body = responseBodyStr,
        )

        if (!response.isSuccessful && response.code != 101) {
            DebugLog.log("ERROR", "HTTP", "Non-success response: ${response.code} ${response.message}")
        }

        return response
    }
}
