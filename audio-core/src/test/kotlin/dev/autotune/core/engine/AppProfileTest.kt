package dev.autotune.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AppProfileTest {

    @Test
    fun `known TV packages map to their profile`() {
        assertEquals(AppProfile.STREAMING_FILM, AppProfile.forPackage("com.netflix.ninja"))
        assertEquals(AppProfile.STREAMING_FILM, AppProfile.forPackage("com.amazon.amazonvideo.livingroom"))
        assertEquals(AppProfile.VIDEO_SHARING, AppProfile.forPackage("com.google.android.youtube.tv"))
        assertEquals(AppProfile.LOCAL_MEDIA, AppProfile.forPackage("org.videolan.vlc"))
        assertEquals(AppProfile.BROWSER, AppProfile.forPackage("com.android.chrome"))
    }

    @Test
    fun `unknown packages fall back on name hints then generic`() {
        assertEquals(AppProfile.BROWSER, AppProfile.forPackage("com.acme.tvbrowser"))
        assertEquals(AppProfile.LOCAL_MEDIA, AppProfile.forPackage("com.example.kodi.fork"))
        assertEquals(AppProfile.GENERIC, AppProfile.forPackage("com.example.launcher"))
        assertEquals(AppProfile.GENERIC, AppProfile.forPackage(null))
    }

    @Test
    fun `profiles only adjust what they mean to`() {
        val base = StabilizerConfig(targetDialogueLufs = -18f, strength = 0.6f)
        for (profile in AppProfile.entries) {
            val configured = profile.configure(base)
            assertEquals(base.targetDialogueLufs, configured.targetDialogueLufs, 0f)
            assertEquals(base.strength, configured.strength, 0f)
        }
    }
}
