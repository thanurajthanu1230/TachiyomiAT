package mihon.domain.extensionrepo.service

import android.annotation.SuppressLint
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber

/** Network representation used by Mihon's Index V2 repositories. */
@OptIn(ExperimentalSerializationApi::class)
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class NetworkExtensionStoreDto(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: Contact = Contact(),
    // Kept for compatibility with the transitional index format.
    @ProtoNumber(5) val extensions: List<Extension> = emptyList(),
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String = "",
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(
        @ProtoNumber(1) val extensions: List<Extension> = emptyList(),
    )

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val packageName: String = "",
        @ProtoNumber(3) val resources: Resources = Resources(),
        @ProtoNumber(4) val extensionLib: String = "",
        @ProtoNumber(5) val versionCode: Long = 0,
        @ProtoNumber(6) val versionName: String = "",
        @ProtoNumber(7) val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String = "",
        @ProtoNumber(2) val iconUrl: String = "",
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long = 0,
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val language: String = "",
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(6) val contentRating: ContentRating = ContentRating.SAFE,
        @ProtoNumber(7) val message: String? = null,
    )

    @Suppress("Unused")
    @Serializable
    enum class ContentRating {
        @ProtoNumber(0)
        @JsonNames("CONTENT_RATING_SAFE")
        SAFE,

        @ProtoNumber(1)
        @JsonNames("CONTENT_RATING_SUGGESTIVE")
        SUGGESTIVE,

        @ProtoNumber(2)
        @JsonNames("CONTENT_RATING_EROTICA")
        EROTICA,

        @ProtoNumber(3)
        @JsonNames("CONTENT_RATING_PORNOGRAPHIC")
        PORNOGRAPHIC,
    }
}
