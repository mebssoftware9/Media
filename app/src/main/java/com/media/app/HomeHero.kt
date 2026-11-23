package com.media.app

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

// ============================================================================
//  HOME, CINEMATIC (the blueprint's page 2)
//
//  The art direction there - a deep midnight room lit in cyan and violet, a
//  hero card with one clear action, glowing glass cards to jump back in -
//  built from the listener's OWN artwork, as the blueprint itself asks
//  ("real album artwork and adaptive media colors"), never stock imagery.
//
//      HomeAtmosphere   the hero's cover, huge and soft, sunk into midnight,
//                       with a cyan and a violet light and a few stars
//      HeroCarousel     continue listening / most played / rediscover, one
//                       card at a time, Play now, page dots, gentle advance
//      JumpBackInRow    glass cards with a play button; the streak first
// ============================================================================

/** One hero card: what it is, its cover, and what Play now does. */
class HeroSlide(
    val key: String,
    val overline: Int,
    /** Raw names, made readable when drawn (shownAlbum / shownTitle / shownArtist). */
    val rawTitle: String,
    val isAlbum: Boolean,
    val rawArtist: String,
    /** Tracks in it; more than one adds "· 12 songs". */
    val count: Int,
    val art: AppMediaItem,
    /** 0..1 for something half-heard, else null. */
    val progress: Float?,
    val onPlay: () -> Unit
)

/** The hero's cover spread into the room behind Home. Drawn under everything. */
@Composable
fun BoxScope.HomeAtmosphere(art: AppMediaItem?) {
    val ink = MediaColors.Ink
    val cyan = MediaColors.Accent
    val violet = Via.Violet
    Box(Modifier.matchParentSize()) {
        // The cover, huge and out of focus at the top of the room. Android
        // 12+ blurs it; before that a tiny decode scaled up is soft enough.
        Crossfade(targetState = art, animationSpec = tween(900), label = "homeAtmosphere") { a ->
            if (a != null) {
                val soft = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                CoverArt(
                    a,
                    Modifier.fillMaxWidth().aspectRatio(0.9f).alpha(0.62f)
                        .then(if (soft) Modifier.blur(56.dp) else Modifier),
                    corner = 0,
                    targetPx = if (soft) 256 else 48
                )
            }
        }
        // Light and depth: the room sinks to midnight below the hero, a cyan
        // light from the top left, a violet one from the right.
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.verticalGradient(
                    0f to ink.copy(alpha = 0.10f),
                    0.28f to ink.copy(alpha = 0.55f),
                    0.55f to ink.copy(alpha = 0.92f),
                    1f to ink
                )
            )
            drawRect(Brush.radialGradient(
                listOf(cyan.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(w * 0.05f, h * 0.10f), radius = w * 0.85f
            ))
            drawRect(Brush.radialGradient(
                listOf(violet.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(w * 1.0f, h * 0.30f), radius = w * 0.95f
            ))
            drawRect(Brush.radialGradient(
                listOf(violet.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(w * 0.2f, h * 0.95f), radius = w * 0.9f
            ))
            // A few still stars, the same every time.
            var seed = 0x2545F491
            repeat(46) {
                seed = seed * 1103515245 + 12345
                val x = ((seed ushr 8) and 0xFFFF) / 65535f * w
                seed = seed * 1103515245 + 12345
                val y = ((seed ushr 8) and 0xFFFF) / 65535f * h * 0.75f
                seed = seed * 1103515245 + 12345
                val a = 0.15f + ((seed ushr 8) and 0xFF) / 255f * 0.45f
                drawCircle(Color.White.copy(alpha = a), radius = 0.6f + a * 1.4f, center = Offset(x, y))
            }
        }
    }
}

/** One editorial feature at a time: big artwork, short copy, one action. */
@Composable
fun HeroCarousel(slides: List<HeroSlide>) {
    if (slides.isEmpty()) return
    val pager = rememberPagerState { slides.size }
    // Moves on by itself every seven seconds, never while a finger is on it.
    LaunchedEffect(pager, slides.size) {
        if (slides.size < 2) return@LaunchedEffect
        while (true) {
            delay(7_000)
            if (!pager.isScrollInProgress) {
                pager.animateScrollToPage((pager.currentPage + 1) % slides.size, animationSpec = tween(700))
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = Space.md)) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = Space.xl),
            pageSpacing = Space.md,
            key = { slides[it].key }
        ) { page ->
            val off = abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
            HeroCard(slides[page], Modifier.alpha(1f - 0.35f * off))
        }
        if (slides.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = Space.md),
                horizontalArrangement = Arrangement.Center
            ) {
                slides.indices.forEach { i ->
                    val on = i == pager.currentPage
                    val w by animateFloatAsState(if (on) 18f else 6f, tween(Motion.Standard), label = "dot")
                    Box(
                        Modifier.padding(horizontal = 3.dp).height(6.dp).width(w.dp)
                            .clip(CircleShape)
                            .background(if (on) MediaColors.Accent else MediaColors.CreamFaint.copy(alpha = 0.45f))
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(s: HeroSlide, modifier: Modifier = Modifier) {
    val cyan = MediaColors.Accent
    val violet = Via.Violet
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier.fillMaxWidth().height(212.dp)
            .shadow(24.dp, shape, ambientColor = violet, spotColor = cyan)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xE6101A33), Color(0xD91A0F3D))))
            .border(1.dp, Brush.linearGradient(listOf(cyan.copy(alpha = 0.65f), violet.copy(alpha = 0.55f))), shape)
            .pressScale(scaleDown = 0.985f, haptic = true, onClick = s.onPlay)
    ) {
        // The cover on the right, lit from behind.
        Box(
            Modifier.align(Alignment.CenterEnd).offset(x = 18.dp).size(176.dp)
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(listOf(cyan.copy(alpha = 0.40f), violet.copy(alpha = 0.18f), Color.Transparent)),
                        radius = size.minDimension * 0.75f
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            CoverArt(
                s.art,
                Modifier.size(140.dp).shadow(18.dp, RoundedCornerShape(18.dp), spotColor = violet)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(18.dp)),
                corner = 18, targetPx = 420
            )
        }
        Column(
            Modifier.fillMaxSize().padding(start = 20.dp, top = 20.dp, bottom = 18.dp, end = 160.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    stringResource(s.overline).uppercase(),
                    style = Typo.Micro.copy(letterSpacing = 1.6.sp), color = cyan, maxLines = 1
                )
                Spacer(Modifier.height(Space.sm))
                Text(
                    if (s.isAlbum) shownAlbum(s.rawTitle) else shownTitle(s.rawTitle),
                    style = Typo.Display.copy(fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 29.sp),
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Space.xs))
                val who = shownArtist(s.rawArtist)
                Text(if (s.count > 1) who + "  \u00b7  " + plural(R.plurals.count_tracks, s.count) else who,
                    style = Typo.Secondary, color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column {
                s.progress?.let { p ->
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).height(3.dp).background(cyan))
                    }
                    Spacer(Modifier.height(Space.md))
                }
                Row(
                    Modifier.clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(cyan, Color(0xFF6FF3FF))))
                        .pressScale(haptic = true, onClick = s.onPlay)
                        .padding(start = 14.dp, end = 18.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = MediaColors.OnAccent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.hero_play_now), style = Typo.Label.copy(fontWeight = FontWeight.Bold),
                        color = MediaColors.OnAccent)
                }
            }
        }
    }
}

/** "Jump back in": the streak, then what was played last, as glass cards. */
@Composable
fun JumpBackInRow(
    streak: Streak,
    recent: List<AppMediaItem>,
    playingUri: String?,
    onPlay: (Int) -> Unit
) {
    val showStreak = streak.days > 0
    if (!showStreak && recent.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = Space.xl, bottom = Space.xs)) {
        Row(Modifier.padding(start = Space.xl, bottom = Space.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, null, tint = MediaColors.Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Space.sm))
            Text(stringResource(R.string.hero_jump_back_in), style = Typo.Section.copy(fontWeight = FontWeight.Bold),
                color = MediaColors.Cream)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Space.xl),
            horizontalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            if (showStreak) item(key = "streak") { StreakCard(streak) }
            items(recent.size, key = { recent[it].id }) { i ->
                val item = recent[i]
                GlassCard(item, playing = playingUri == item.uri.toString()) { onPlay(i) }
            }
        }
    }
}

private val CardW = 118.dp
private val CardH = 132.dp

@Composable
private fun GlassCard(item: AppMediaItem, playing: Boolean, onClick: () -> Unit) {
    val cyan = MediaColors.Accent
    val shape = RoundedCornerShape(18.dp)
    val desc = shownTitle(item.title)
    Box(
        Modifier.size(CardW, CardH)
            .then(if (playing) Modifier.shadow(16.dp, shape, spotColor = cyan, ambientColor = cyan) else Modifier)
            .clip(shape)
            .border(1.dp, if (playing) cyan.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.14f), shape)
            .pressScale(haptic = true, onClick = onClick)
            .semantics { contentDescription = desc }
    ) {
        CoverArt(item, Modifier.fillMaxSize(), corner = 0, targetPx = 360)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0.35f to Color.Transparent, 1f to Color(0xF2050816)
        )))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 9.dp, end = 40.dp)) {
            Text(shownTitle(item.title), style = Typo.Label.copy(fontWeight = FontWeight.Bold), color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shownArtist(item.artist), style = Typo.Tertiary, color = Color.White.copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(28.dp).clip(CircleShape)
                .background(Color(0x99050816)).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StreakCard(streak: Streak) {
    val cyan = MediaColors.Accent
    val lit = streak.playedToday
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.size(CardW, CardH)
            .shadow(if (lit) 16.dp else 0.dp, shape, spotColor = cyan, ambientColor = cyan)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF0B2A3A), Color(0xFF0A1024))))
            .border(1.dp, cyan.copy(alpha = if (lit) 0.85f else 0.35f), shape)
    ) {
        // A little equaliser of light: this week's listening, made visible.
        Canvas(Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 14.dp, vertical = 12.dp)) {
            val bars = 13
            val gap = size.width / bars
            for (b in 0 until bars) {
                val h = size.height * (0.25f + 0.75f * abs(kotlin.math.sin(b * 1.3f + streak.days)))
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0xFF8FF8FF), cyan), startY = size.height - h, endY = size.height),
                    topLeft = Offset(b * gap + gap * 0.2f, size.height - h),
                    size = androidx.compose.ui.geometry.Size(gap * 0.6f, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(gap * 0.3f),
                    alpha = if (lit) 1f else 0.5f
                )
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocalFireDepartment, null,
                    tint = if (lit) Color(0xFFFFB547) else MediaColors.CreamFaint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(plural(R.plurals.streak_days, streak.days), style = Typo.Label.copy(fontWeight = FontWeight.Bold),
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (lit) plural(R.plurals.plays_this_week, streak.weekPlays) else stringResource(R.string.streak_keep),
                style = Typo.Tertiary, color = if (lit) Color.White.copy(alpha = 0.7f) else cyan,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The hero cards for one format: continue (something half-heard), most
 * played, and something not heard in a while. Falls back to the library.
 */
fun heroSlidesFor(
    pillar: Pillar,
    inPillar: List<AppMediaItem>,
    resume: Pair<AppMediaItem, PlaybackPosition>?,
    lastPlayed: Map<Long, Long>,
    playCounts: Map<Long, Int>,
    play: (List<AppMediaItem>, Int) -> Unit,
    playAt: (AppMediaItem, Long) -> Unit
): List<HeroSlide> {
    if (inPillar.isEmpty()) return emptyList()
    val out = ArrayList<HeroSlide>(3)
    resume?.let { (item, pos) ->
        out += HeroSlide(
            key = "resume", overline = R.string.hero_continue,
            rawTitle = item.title, isAlbum = false, rawArtist = item.artist, count = 1, art = item,
            progress = (pos.positionMs.toFloat() / pos.durationMs).coerceIn(0f, 1f),
            onPlay = { playAt(item, pos.positionMs) }
        )
    }
    // Music groups by album; everything else stands alone.
    val groups: List<List<AppMediaItem>> = if (pillar == Pillar.MUSIC) {
        MediaRepository.albumsOf(inPillar).filter { it.tracks.isNotEmpty() }.map { it.tracks }
    } else inPillar.map { listOf(it) }
    fun albumOf(g: List<AppMediaItem>) = pillar == Pillar.MUSIC && g.first().album.isNotBlank()
    fun titleOf(g: List<AppMediaItem>) = if (albumOf(g)) g.first().album else g.first().title
    val used = HashSet<Long>()
    resume?.first?.let { used += it.id }
    val top = groups.filter { g -> g.sumOf { playCounts[it.id] ?: 0 } > 0 }
        .maxByOrNull { g -> g.sumOf { playCounts[it.id] ?: 0 } }
    if (top != null && top.none { it.id in used }) {
        used += top.map { it.id }
        out += HeroSlide(
            key = "top", overline = R.string.hero_most_played,
            rawTitle = titleOf(top), isAlbum = albumOf(top), rawArtist = top.first().artist, count = top.size,
            art = top.first(), progress = null,
            onPlay = { play(top, 0) }
        )
    }
    // Not heard in the longest time (or never), one per day.
    val quiet = groups.filter { g -> g.none { it.id in used } }
        .sortedBy { g -> g.maxOf { lastPlayed[it.id] ?: 0L } }
        .take(12)
    if (quiet.isNotEmpty()) {
        val day = (System.currentTimeMillis() / 86_400_000L).toInt()
        val g = quiet[Math.floorMod(day, quiet.size)]
        out += HeroSlide(
            key = "rediscover", overline = if (out.isEmpty()) R.string.hero_journey else R.string.hero_rediscover,
            rawTitle = titleOf(g), isAlbum = albumOf(g), rawArtist = g.first().artist, count = g.size,
            art = g.first(), progress = null,
            onPlay = { play(g, 0) }
        )
    }
    return out
}
