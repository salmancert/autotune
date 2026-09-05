package dev.autotune.core.engine

/**
 * Per-source defaults.
 *
 * The apps differ in how they master audio: a film on Netflix or Prime Video
 * carries a wide cinematic range that badly needs taming, YouTube is already
 * loudness-normalised so it needs a lighter touch, and a local file in VLC has
 * had nothing done to it at all.
 */
enum class AppProfile(
    val label: String,
    val packages: List<String>,
    val configure: (StabilizerConfig) -> StabilizerConfig,
) {
    STREAMING_FILM(
        label = "Films & series",
        packages = listOf(
            "com.netflix.ninja",
            "com.netflix.mediaclient",
            "com.amazon.amazonvideo.livingroom",
            "com.amazon.avod.thirdpartyclient",
            "com.amazon.firebat",
            "com.disney.disneyplus",
            "com.apple.atve.androidtv.appletv",
            "com.hbo.hbonow",
            "com.wbd.stream",
        ),
        configure = { it.copy(musicCeilingOffsetDb = 5f, maxBoostDb = 12f, compressionRatio = 4.5f) },
    ),
    VIDEO_SHARING(
        label = "YouTube & short video",
        packages = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube.tvunplugged",
            "com.google.android.youtube.tvkids",
            "com.google.android.apps.youtube.music",
        ),
        // Already loudness-normalised upstream, so correct gently.
        configure = { it.copy(musicCeilingOffsetDb = 3f, maxBoostDb = 8f, compressionRatio = 3f) },
    ),
    LOCAL_MEDIA(
        label = "VLC & local media",
        packages = listOf(
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "com.plexapp.android",
            "com.jellyfin.androidtv",
            "org.jellyfin.androidtv",
            "com.kodi.android",
            "org.xbmc.kodi",
        ),
        // Untouched masters: the widest range, so the most correction.
        configure = { it.copy(musicCeilingOffsetDb = 6f, maxBoostDb = 14f, compressionRatio = 5f) },
    ),
    BROWSER(
        label = "Browser",
        packages = listOf(
            "com.android.chrome",
            "com.google.android.tv.browser",
            "com.amazon.cloud9",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.puffin.tv.free",
        ),
        configure = { it.copy(musicCeilingOffsetDb = 4f, maxBoostDb = 10f) },
    ),
    GENERIC(
        label = "Everything else",
        packages = emptyList(),
        configure = { it },
    );

    companion object {
        private val byPackage: Map<String, AppProfile> = buildMap {
            for (profile in AppProfile.entries) {
                for (packageName in profile.packages) put(packageName, profile)
            }
        }

        /** Resolves a playing package to its profile, falling back to [GENERIC]. */
        fun forPackage(packageName: String?): AppProfile {
            if (packageName.isNullOrEmpty()) return GENERIC
            byPackage[packageName]?.let { return it }
            // Unknown browsers and forks are common on TV; match on the obvious hints.
            val lower = packageName.lowercase()
            return when {
                lower.contains("browser") || lower.contains("chrom") || lower.contains("firefox") -> BROWSER
                lower.contains("youtube") -> VIDEO_SHARING
                lower.contains("netflix") || lower.contains("amazon") || lower.contains("disney") -> STREAMING_FILM
                lower.contains("vlc") || lower.contains("kodi") || lower.contains("plex") -> LOCAL_MEDIA
                else -> GENERIC
            }
        }
    }
}
