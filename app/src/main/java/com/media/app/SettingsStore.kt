package com.media.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "media_settings")

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class MediaSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val fontScale: Float = 1.0f,  // 0.9 = compact, 1.0 = default, 1.15 = large
    // Auto follows the detected device ceiling. A manual pick is honoured
    // even above it - the blueprint allows the override explicitly.
    val qualityMode: QualityMode = QualityMode.AUTO
)

object SettingsStore {
    private val THEME = stringPreferencesKey("theme_mode")
    private val FONT = floatPreferencesKey("font_scale")
    private val INTRO_SEEN = booleanPreferencesKey("intro_seen")
    private val REVEAL_SEEN = booleanPreferencesKey("library_reveal_seen")
    private val PLAYER_HINT = booleanPreferencesKey("player_hint_seen")
    private val REACTIVE_ART = booleanPreferencesKey("reactive_artwork")
    private val HIDE_COVER = booleanPreferencesKey("hide_cover")
    private val SPEAKER = booleanPreferencesKey("speaker_art")
    private val HOME_STYLE = androidx.datastore.preferences.core.stringPreferencesKey("home_style")
    private val QUALITY = stringPreferencesKey("display_quality")
    private val WORLD = stringPreferencesKey("living_world")
    private val AVATAR = longPreferencesKey("avatar_stamp")
    private val ONLINE = booleanPreferencesKey("online_enrichment")
    private val ONLINE_ASKED = booleanPreferencesKey("online_enrichment_asked")

    fun flow(context: Context): Flow<MediaSettings> =
        context.dataStore.data.map { p ->
            MediaSettings(
                themeMode = when (p[THEME]) {
                    "LIGHT" -> ThemeMode.LIGHT
                    "SYSTEM" -> ThemeMode.SYSTEM
                    "DARK" -> ThemeMode.DARK
                    else -> ThemeMode.DARK
                },
                fontScale = p[FONT] ?: 1.0f,
                qualityMode = QualityMode.fromName(p[QUALITY])
            )
        }

    suspend fun setTheme(context: Context, mode: ThemeMode) {
        context.dataStore.edit { it[THEME] = mode.name }
    }

    suspend fun setFontScale(context: Context, scale: Float) {
        context.dataStore.edit { it[FONT] = scale }
    }

    suspend fun setQualityMode(context: Context, mode: QualityMode) {
        context.dataStore.edit { it[QUALITY] = mode.name }
    }

    // Not the picture, just a stamp that changes when the picture does. The
    // image itself is a file (see Avatar); a DataStore write is what tells
    // Compose to go and read it again. 0 means the default mark.
    fun avatarStampFlow(context: Context): Flow<Long> =
        context.dataStore.data.map { it[AVATAR] ?: 0L }

    suspend fun setAvatarStamp(context: Context, stamp: Long) {
        context.dataStore.edit { it[AVATAR] = stamp }
    }

    fun introSeenFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[INTRO_SEEN] ?: false }

    suspend fun setIntroSeen(context: Context) {
        context.dataStore.edit { it[INTRO_SEEN] = true }
    }

    // §42: the first successful scan is a reveal, shown once and never again.
    fun revealSeenFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[REVEAL_SEEN] ?: false }

    suspend fun setRevealSeen(context: Context) {
        context.dataStore.edit { it[REVEAL_SEEN] = true }
    }

    // Shown once, the first time the full player is opened. A feature that is
    // off by default and buried in settings is a feature nobody finds.
    fun playerHintSeenFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[PLAYER_HINT] ?: false }

    suspend fun setPlayerHintSeen(context: Context) {
        context.dataStore.edit { it[PLAYER_HINT] = true }
    }

    // Artwork that moves with the music. OFF by default: it is the most
    // opinionated thing the app does, and an effect someone dislikes is worse
    // than an effect they never found.
    fun reactiveArtFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[REACTIVE_ART] ?: false }

    suspend fun setReactiveArt(context: Context, value: Boolean) {
        context.dataStore.edit { it[REACTIVE_ART] = value }
    }

    // The third state of Now Playing's artwork button: the cover steps
    // aside and the Living World has the whole screen. Off by default.
    fun hideCoverFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[HIDE_COVER] ?: false }

    // How Home opens (HomeStages.kt). Living Room unless picked otherwise.
    fun homeStyleFlow(context: Context): Flow<HomeStyle> =
        context.dataStore.data.map { HomeStyle.fromName(it[HOME_STYLE]) }

    suspend fun setHomeStyle(context: Context, style: HomeStyle) {
        context.dataStore.edit { it[HOME_STYLE] = style.name }
    }

    // The artwork button's speaker state (SpeakerArt.kt).
    fun speakerFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[SPEAKER] ?: false }

    /**
     * The artwork button: still cover -> reactive cover -> speaker -> no
     * cover -> still cover. The speaker keeps Reactive artwork on, since it
     * moves with the same beat.
     */
    suspend fun cycleArtwork(context: Context) {
        context.dataStore.edit {
            val reactive = it[REACTIVE_ART] ?: false
            val speaker = it[SPEAKER] ?: false
            val hidden = it[HIDE_COVER] ?: false
            when {
                hidden -> { it[HIDE_COVER] = false; it[SPEAKER] = false; it[REACTIVE_ART] = false }
                speaker -> { it[SPEAKER] = false; it[HIDE_COVER] = true }
                reactive -> it[SPEAKER] = true
                else -> it[REACTIVE_ART] = true
            }
        }
    }

    // Living Worlds (LivingWorld.kt). ON (Auto) by default, unlike Reactive
    // artwork: it is the scene around the cover, not the cover itself, so
    // the cover stays still unless that is turned on too.
    fun worldFlow(context: Context): Flow<WorldMode> =
        context.dataStore.data.map { WorldMode.fromName(it[WORLD]) }

    suspend fun setWorld(context: Context, mode: WorldMode) {
        context.dataStore.edit { it[WORLD] = mode.name }
    }

    // Artwork and lyrics lookups. OFF until the listener says yes: the app's
    // promise is that nothing leaves the phone, and this is the one feature
    // that has to break it - so it is asked for, never assumed. Asking for a
    // single cover or a lyrics search by hand is consent for that one lookup.
    fun onlineFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[ONLINE] ?: false }

    suspend fun setOnline(context: Context, value: Boolean) {
        context.dataStore.edit { it[ONLINE] = value; it[ONLINE_ASKED] = true }
    }

    fun onlineAskedFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[ONLINE_ASKED] ?: false }

    suspend fun setOnlineAsked(context: Context) {
        context.dataStore.edit { it[ONLINE_ASKED] = true }
    }
}
