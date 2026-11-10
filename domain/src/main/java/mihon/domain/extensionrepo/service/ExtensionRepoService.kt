package mihon.domain.extensionrepo.service

import androidx.core.net.toUri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import logcat.LogPriority
import mihon.domain.extensionrepo.model.ExtensionRepo
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

class ExtensionRepoService(
    networkHelper: NetworkHelper,
    private val json: Json,
) {
    val client = networkHelper.client

    @Suppress("TooGenericExceptionCaught")
    suspend fun fetchRepoDetails(repo: String): ExtensionRepo? = withIOContext {
        // Keep the existing repo.json contract first; Index V2 is the fallback.
        try {
            with(json) {
                client.newCall(GET("${repo.trimEnd('/')}/repo.json".toUri().toString()))
                    .awaitSuccess()
                    .parseAs<ExtensionRepoMetaDto>()
                    .toExtensionRepo(baseUrl = repo.trimEnd('/'))
            }
        } catch (legacyError: Exception) {
            if (legacyError is CancellationException) throw legacyError
            try {
                fetchIndexV2RepoDetails(repo.trimEnd('/'))
            } catch (indexError: Exception) {
                logcat(LogPriority.ERROR, indexError) { "Failed to fetch Index V2 repository details" }
                logcat(LogPriority.DEBUG, legacyError) { "Legacy repo.json lookup also failed" }
                null
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetchIndexV2RepoDetails(repo: String): ExtensionRepo? {
        var lastError: Throwable? = null
        for (fileName in listOf("index.pb", "index.pb.gz", "index.json")) {
            try {
                val bytes = client.newCall(GET("$repo/$fileName"))
                    .awaitSuccess()
                    .use { response -> response.body.bytes() }
                    .decompressIfGzipped()

                val firstByte = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }
                    ?.toInt()?.and(0xFF)
                val store = if (firstByte == '{'.code) {
                    json.decodeFromString<NetworkExtensionStoreDto>(bytes.decodeToString())
                } else {
                    ProtoBuf.decodeFromByteArray<NetworkExtensionStoreDto>(bytes)
                }

                if (store.name.isBlank() || store.signingKey.isBlank()) continue
                return ExtensionRepo(
                    baseUrl = repo,
                    name = store.name,
                    shortName = store.badgeLabel.takeIf(String::isNotBlank),
                    website = store.contact.website.ifBlank { repo },
                    signingKeyFingerprint = store.signingKey,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
            }
        }
        lastError?.let { throw it }
        return null
    }
}

private fun ByteArray.decompressIfGzipped(): ByteArray {
    if (size < 2 || this[0] != 0x1F.toByte() || this[1] != 0x8B.toByte()) return this
    return GZIPInputStream(ByteArrayInputStream(this)).use { input ->
        ByteArrayOutputStream().use { output ->
            input.copyTo(output)
            output.toByteArray()
        }
    }
}
