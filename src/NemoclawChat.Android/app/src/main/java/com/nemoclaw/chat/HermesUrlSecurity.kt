package com.nemoclaw.chat

import java.net.URI

internal fun shouldAuthenticateHermesUrl(
    settings: AppSettings,
    url: String,
    additionalGatewayRoots: List<String> = emptyList()
): Boolean = runCatching {
    val target = URI(url)
    target.path.orEmpty().startsWith("/v1/") &&
        (listOf(settings.gatewayUrl) + additionalGatewayRoots).any { configured ->
            sameHttpOrigin(target, URI(configured.trim().trimEnd('/')))
        }
}.getOrDefault(false)

internal fun sameHttpOrigin(left: URI, right: URI): Boolean {
    fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }
    return (left.scheme.equals("http", ignoreCase = true) || left.scheme.equals("https", ignoreCase = true)) &&
        left.scheme.equals(right.scheme, ignoreCase = true) &&
        left.host.equals(right.host, ignoreCase = true) &&
        effectivePort(left) == effectivePort(right)
}
