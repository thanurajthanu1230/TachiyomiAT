package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.NetworkExtensionStoreDto
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.days

internal class ExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val getExtensionRepo: GetExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateExtensionRepo by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()
    private val json: Json by injectLazy()

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_ext_check"), 0)
    }

    suspend fun findExtensions(): List<Extension.Available> = withIOContext {
        getExtensionRepo.getAll()
            .map { async { getExtensions(it) } }
            .awaitAll()
            .flatten()
    }

    private suspend fun getExtensions(extRepo: ExtensionRepo): List<Extension.Available> {
        val repoBaseUrl = extRepo.baseUrl.trimEnd('/')
        var lastError: Throwable? = null
        // V2 is preferred, while the old JSON index remains fully supported.
        for (fileName in listOf("index.pb", "index.pb.gz", "index.json", "index.min.json")) {
            try {
                val bytes = networkService.client
                    .newCall(GET("$repoBaseUrl/$fileName"))
                    .awaitSuccess()
                    .use { response -> response.body.bytes() }
                return decodeIndex(bytes, repoBaseUrl)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastError = e
            }
        }
        logcat(LogPriority.ERROR, lastError) { "Failed to get extensions from $repoBaseUrl" }
        return emptyList()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun decodeIndex(downloadedBytes: ByteArray, repoUrl: String): List<Extension.Available> {
        val bytes = downloadedBytes.decompressIfGzipped()
        val firstByte = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }
            ?.toInt()?.and(0xFF)

        if (firstByte == '['.code) {
            return json.decodeFromString<List<ExtensionJsonObject>>(bytes.decodeToString())
                .toExtensions(repoUrl)
        }

        val store = if (firstByte == '{'.code) {
            json.decodeFromString<NetworkExtensionStoreDto>(bytes.decodeToString())
        } else {
            ProtoBuf.decodeFromByteArray<NetworkExtensionStoreDto>(bytes)
        }

        val extensionList = when {
            !store.extensionListUrl.isNullOrBlank() -> fetchExtensionList(store.extensionListUrl)
            store.extensionList != null -> store.extensionList
            store.extensions.isNotEmpty() -> NetworkExtensionStoreDto.ExtensionList(store.extensions)
            else -> null
        } ?: return emptyList()

        return extensionList.toAvailableExtensions(repoUrl)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetchExtensionList(url: String): NetworkExtensionStoreDto.ExtensionList {
        val bytes = networkService.client
            .newCall(GET(url))
            .awaitSuccess()
            .use { response -> response.body.bytes() }
            .decompressIfGzipped()
        val firstByte = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }
            ?.toInt()?.and(0xFF)
        return if (firstByte == '{'.code) {
            json.decodeFromString(bytes.decodeToString())
        } else {
            ProtoBuf.decodeFromByteArray(bytes)
        }
    }

    private fun NetworkExtensionStoreDto.ExtensionList.toAvailableExtensions(
        repoUrl: String,
    ): List<Extension.Available> = extensions.mapNotNull { extension ->
        val libVersion = extension.extensionLib.toDoubleOrNull() ?: return@mapNotNull null
        if (libVersion < ExtensionLoader.LIB_VERSION_MIN || libVersion > ExtensionLoader.LIB_VERSION_MAX) {
            return@mapNotNull null
        }
        if (extension.resources.apkUrl.isBlank() || extension.pkgNameInvalid()) return@mapNotNull null

        val languages = extension.sources.map { it.language }.filter(String::isNotBlank).distinct()
        Extension.Available(
            name = extension.name,
            pkgName = extension.packageName,
            versionName = extension.versionName,
            versionCode = extension.versionCode,
            libVersion = libVersion,
            lang = languages.singleOrNull() ?: "all",
            isNsfw = extension.sources.any {
                it.contentRating == NetworkExtensionStoreDto.ContentRating.PORNOGRAPHIC
            },
            sources = extension.sources.map {
                Extension.Available.Source(
                    id = it.id,
                    lang = it.language,
                    name = it.name,
                    baseUrl = it.homeUrl,
                )
            },
            apkName = extension.resources.apkUrl.substringBefore('?').substringAfterLast('/'),
            iconUrl = extension.resources.iconUrl,
            repoUrl = repoUrl,
            apkUrl = extension.resources.apkUrl,
        )
    }

    private fun NetworkExtensionStoreDto.Extension.pkgNameInvalid(): Boolean = packageName.isBlank()

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<Extension.Installed>? {
        if (!fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        updateExtensionRepo.awaitAll()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val availableExt = extensions.find { it.pkgName == installedExt.pkgName } ?: continue
            if (availableExt.versionCode > installedExt.versionCode ||
                availableExt.libVersion > installedExt.libVersion
            ) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }
        return extensionsWithUpdate
    }

    private fun List<ExtensionJsonObject>.toExtensions(repoUrl: String): List<Extension.Available> =
        filter {
            val libVersion = it.extractLibVersion()
            libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
        }.map {
            Extension.Available(
                name = it.name.substringAfter("Tachiyomi: "),
                pkgName = it.pkg,
                versionName = it.version,
                versionCode = it.code,
                libVersion = it.extractLibVersion(),
                lang = it.lang,
                isNsfw = it.nsfw == 1,
                sources = it.sources?.map(extensionSourceMapper).orEmpty(),
                apkName = it.apk,
                iconUrl = "$repoUrl/icon/${it.pkg}.png",
                repoUrl = repoUrl,
            )
        }

    fun getApkUrl(extension: Extension.Available): String =
        extension.apkUrl?.takeIf(String::isNotBlank) ?: "${extension.repoUrl}/apk/${extension.apkName}"

    private fun ExtensionJsonObject.extractLibVersion(): Double = version.substringBeforeLast('.').toDouble()
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

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<ExtensionSourceJsonObject>?,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private val extensionSourceMapper: (ExtensionSourceJsonObject) -> Extension.Available.Source = {
    Extension.Available.Source(id = it.id, lang = it.lang, name = it.name, baseUrl = it.baseUrl)
}
