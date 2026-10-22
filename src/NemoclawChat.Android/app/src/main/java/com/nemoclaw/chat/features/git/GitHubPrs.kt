package com.nemoclaw.chat.features.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Pull request GitHub essenziali (lista, diff, merge). Solo stdlib+org.json. */
internal data class GitHubPr(
    val number: Int,
    val title: String,
    val state: String,
    val author: String,
    val createdAt: String
)

internal fun parseGitHubRepo(url: String): Pair<String, String>? {
    val clean = url.trim().removeSuffix(".git").removeSuffix("/")
    val match = Regex("""github\.com[:/]([^/\s]+)/([^/\s]+)""").find(clean) ?: return null
    val (owner, repo) = match.destructured
    if (owner.isBlank() || repo.isBlank()) return null
    return owner to repo
}

internal fun prListUrl(owner: String, repo: String): String =
    "https://api.github.com/repos/$owner/$repo/pulls?state=open&per_page=30"

internal fun prDiffUrl(owner: String, repo: String, number: Int): String =
    "https://api.github.com/repos/$owner/$repo/pulls/$number"

internal fun prMergeUrl(owner: String, repo: String, number: Int): String =
    "https://api.github.com/repos/$owner/$repo/pulls/$number/merge"

/** Classifica riga diff: '+' aggiunta, '-' rimossa, '@' hunk, ' ' contesto. */
internal fun diffLineKind(line: String): Char = when {
    line.startsWith("+++") || line.startsWith("---") -> ' '
    line.startsWith("+") -> '+'
    line.startsWith("-") -> '-'
    line.startsWith("@") -> '@'
    else -> ' '
}

private fun githubGet(url: String, token: String, diff: Boolean = false): Pair<Int, String> {
    val connection = URL(url).openConnection() as HttpsURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Accept", if (diff) "application/vnd.github.diff" else "application/vnd.github+json")
    connection.setRequestProperty("Authorization", "Bearer $token")
    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    connection.setRequestProperty("User-Agent", "HermesHub-Android")
    return try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        code to (stream?.bufferedReader()?.readText()?.take(400_000).orEmpty())
    } finally {
        connection.disconnect()
    }
}

internal suspend fun listOpenPrs(owner: String, repo: String, token: String): List<GitHubPr> =
    withContext(Dispatchers.IO) {
        val (code, body) = runCatching { githubGet(prListUrl(owner, repo), token) }.getOrElse { return@withContext emptyList<GitHubPr>() }
        if (code !in 200..299) return@withContext emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList<GitHubPr>()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    GitHubPr(
                        number = o.optInt("number"),
                        title = o.optString("title"),
                        state = o.optString("state"),
                        author = o.optJSONObject("user")?.optString("login").orEmpty(),
                        createdAt = o.optString("created_at").take(10)
                    )
                )
            }
        }
    }

internal suspend fun fetchPrDiff(owner: String, repo: String, number: Int, token: String): String =
    withContext(Dispatchers.IO) {
        val (code, body) = runCatching { githubGet(prDiffUrl(owner, repo, number), token, diff = true) }
            .getOrElse { return@withContext "" }
        if (code !in 200..299) "" else body
    }

internal suspend fun mergePr(owner: String, repo: String, number: Int, token: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(prMergeUrl(owner, repo, number)).openConnection() as HttpsURLConnection
            connection.requestMethod = "PUT"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "HermesHub-Android")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{}".toByteArray()) }
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }
