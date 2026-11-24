package com.media.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

// ============================================================================
//  HOME STYLES (Settings > Appearance > Home style)
//
//  Three ways for Home to open, each built on something only Via has, and
//  the classic one. The list, shuffle, Jump back in and everything below
//  them are the same in all four.
//
//      LIVING_ROOM  the Living World of your last song, running calmly,
//                   the cover floating in it, one button to continue
//      JUKEBOX      your covers on a curved wheel you spin with a thumb;
//                   the centre one is big and lit, and it ticks as it turns
//      AURA         an aura made from the colours of what you played,
//                   breathing slowly, your streak and plays around it
//      CLASSIC      Home as it was: the resume bar and the list
// ============================================================================

enum class HomeStyle { LIVING_ROOM, JUKEBOX, AURA, CLASSIC;
    companion object {
        fun fromName(n: String?): HomeStyle = entries.firstOrNull { it.name == n } ?: LIVING_ROOM
    }
}

@Composable
fun homeStyleName(s: HomeStyle): String = stringResource(
    when (s) {
        HomeStyle.LIVING_ROOM -> R.string.home_style_living
        HomeStyle.JUKEBOX -> R.string.home_style_jukebox
        HomeStyle.AURA -> R.string.home_style_aura
        HomeStyle.CLASSIC -> R.string.home_style_classic
    }
)

private val Lit = Color(0xFF20E6F2)

@Composable
private fun WhiteButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(46.dp).clip(CircleShape).background(Color.White)
            .pressScale(haptic = true, onClick = onClick)
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Typo.Label.copy(fontWeight = FontWeight.Bold), color = Color.Black)
    }
}

@Composable
private fun GlassButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(46.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .pressScale(haptic = true, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Shuffle, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Typo.Label.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text.uppercase(), style = Typo.Micro.copy(fontWeight = FontWeight.Bold, letterSpacing = androidx.compose.ui.unit.TextUnit(1.6f, androidx.compose.ui.unit.TextUnitType.Sp)),
        color = Lit
    )
}

// ------------------------------------------------------------ LIVING ROOM --

/**
 * Home as the Living World of [item]: the world runs calmly behind, the
 * cover floats in it, and one button continues.
 */
@Composable
fun LivingRoomStage(
    item: AppMediaItem,
    eyebrow: String,
    worldMode: WorldMode,
    playing: Boolean,
    /** Home is on screen: the world runs only then. */
    active: Boolean,
    onPlay: () -> Unit
) {
    val density = LocalDensity.current
    val q = LocalQuality.current
    val light = rememberCoverLight(item)
    val colors = rememberArtColors(item)
    val coverDp = 196.dp
    val coverTop = 40.dp
    // A slow float, a few dp: the cover is in the world, not pinned on it.
    val t = rememberInfiniteTransition(label = "float")
    val bob by t.animateFloat(
        -1f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse), label = "bob"
    )
    BoxWithConstraints(Modifier.fillMaxWidth().height(470.dp)) {
        val wPx = with(density) { maxWidth.toPx() }
        // Calm motion with no song analysis: the world moves on its own.
        val feed = remember(worldMode) {
            WorldFeed(mode = worldMode, shape = null, volume = 1f, playing = true, speed = 1f, clock = { 0L })
        }
        if (worldMode != WorldMode.OFF) {
            LivingWorldLayer(
                feed = feed, item = item, light = light, colors = colors, tilt = null, active = active,
                coverCx = wPx / 2f,
                coverCy = with(density) { (coverTop + coverDp / 2).toPx() },
                coverSize = with(density) { coverDp.toPx() },
                textTop = with(density) { (coverTop + coverDp + 24.dp).toPx() },
                level = q.level, relieved = q.lightField == 0,
                modifier = Modifier.fillMaxSize()
            )
        }
        // The room sinks into the page below, so the list carries on from it.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to MediaColors.Ink)
            )
        )
        CoverArt(
            item,
            Modifier.align(Alignment.TopCenter).offset(y = coverTop)
                .graphicsLayer { translationY = bob * 6.dp.toPx() }
                .size(coverDp)
                .shadow(28.dp, RoundedCornerShape(24.dp), spotColor = colors?.glow ?: Color.Black)
                .clip(RoundedCornerShape(24.dp)),
            corner = 0, targetPx = 600
        )
        Column(
            Modifier.align(Alignment.TopCenter).offset(y = coverTop + coverDp + 24.dp)
                .fillMaxWidth().padding(horizontal = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Eyebrow(eyebrow)
            Spacer(Modifier.height(6.dp))
            Text(shownTitle(item.title), style = Typo.Display.copy(fontWeight = FontWeight.ExtraBold),
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(shownArtist(item.artist), style = Typo.Body, color = Color.White.copy(alpha = 0.75f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            WhiteButton(stringResource(if (playing) R.string.home_open else R.string.home_continue), onPlay)
        }
    }
}

// ---------------------------------------------------------------- JUKEBOX --

/** Covers on a curved wheel: spin with a thumb; the centre one plays. */
@Composable
fun JukeboxStage(
    items: List<AppMediaItem>,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState { items.size }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    val centre = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    val colors = rememberArtColors(centre)
    val glow = colors?.glow ?: Lit
    Column(Modifier.fillMaxWidth().padding(top = Space.md), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            // The room takes the centre cover's colour as it turns.
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    Brush.radialGradient(listOf(glow.copy(alpha = 0.45f), glow.copy(alpha = 0.10f), Color.Transparent)),
                    radius = size.maxDimension * 0.6f
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val page = 190.dp
                HorizontalPager(
                    state = pager,
                    pageSize = PageSize.Fixed(page),
                    contentPadding = PaddingValues(horizontal = (maxWidth - page) / 2),
                    pageSpacing = (-40).dp,
                    beyondViewportPageCount = 2
                ) { i ->
                    val off = (pager.currentPage - i) + pager.currentPageOffsetFraction
                    val a = abs(off).coerceAtMost(2.5f)
                    CoverArt(
                        items[i],
                        Modifier.size(page)
                            .graphicsLayer {
                                rotationY = (off * 38f).coerceIn(-70f, 70f)
                                val s = 1f - 0.16f * a.coerceAtMost(1.5f)
                                scaleX = s; scaleY = s
                                alpha = (1f - 0.35f * a).coerceIn(0f, 1f)
                                cameraDistance = 14f * density
                            }
                            .shadow(if (a < 0.5f) 24.dp else 8.dp, RoundedCornerShape(20.dp))
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                            .pressScale(haptic = true) { onPlay(i) },
                        corner = 0, targetPx = 480
                    )
                }
            }
        }
        // The dial's ticks: where the wheel is.
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            val shown = 9
            val first = (pager.currentPage - shown / 2).coerceIn(0, max(0, items.size - shown))
            for (k in first until minOf(items.size, first + shown)) {
                val on = k == pager.currentPage
                Box(Modifier.size(width = 3.dp, height = if (on) 12.dp else 8.dp).clip(CircleShape)
                    .background(if (on) Lit else Color.White.copy(alpha = 0.25f)))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(shownTitle(centre.title), style = Typo.Display.copy(fontWeight = FontWeight.ExtraBold),
            color = MediaColors.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Space.xl))
        Text(shownArtist(centre.artist), style = Typo.Body, color = MediaColors.CreamDim, maxLines = 1)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WhiteButton(stringResource(R.string.home_play)) { onPlay(pager.currentPage) }
            GlassButton(stringResource(R.string.action_shuffle), onShuffle)
        }
        Spacer(Modifier.height(Space.md))
    }
}

// ------------------------------------------------------------------- AURA --

/**
 * An aura made from the colours of [played] (up to five covers), breathing
 * slowly, with the week's numbers around it, and one button for a mix.
 */
@Composable
fun AuraStage(
    played: List<AppMediaItem>,
    streak: Streak,
    topArtist: String?,
    songs: Int,
    onPlayMix: () -> Unit
) {
    val c0 = rememberArtColors(played.getOrNull(0))
    val c1 = rememberArtColors(played.getOrNull(1))
    val c2 = rememberArtColors(played.getOrNull(2))
    val c3 = rememberArtColors(played.getOrNull(3))
    val c4 = rememberArtColors(played.getOrNull(4))
    val palette = listOfNotNull(c0?.glow, c1?.accent, c2?.glow, c3?.accent, c4?.glow, c0?.accent)
        .ifEmpty { listOf(Lit, Color(0xFF8B4DFF), Color(0xFF2DD4BF)) }
    val reduced = LocalReducedMotion.current
    val t = rememberInfiniteTransition(label = "aura")
    val time by t.animateFloat(0f, 1000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing)), label = "time")
    Box(Modifier.fillMaxWidth().height(430.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val tt = if (reduced) 0f else time
            val cx = size.width / 2f
            val cy = size.height * 0.40f
            // A slow breath, about one a second.
            val breath = (sin(tt * 2f * PI.toFloat() * 0.9f) * 0.5f + 0.5f).pow(3)
            val r = size.width * 0.30f * (1f + 0.035f * breath)
            palette.forEachIndexed { i, col ->
                val a = tt * 0.25f + i * 1.3f
                val o = Offset(cx + cos(a) * r * 0.35f, cy + sin(a * 1.2f) * r * 0.30f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(col.copy(alpha = 0.80f), col.copy(alpha = 0.25f), Color.Transparent),
                        center = o, radius = r * 1.1f
                    ),
                    radius = r * 1.1f, center = o, blendMode = BlendMode.Plus
                )
            }
            drawCircle(Color.White.copy(alpha = 0.08f + 0.10f * breath), radius = r * 1.2f,
                center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
        }
        StatChip(plural(R.plurals.streak_days, max(1, streak.days)), stringResource(R.string.home_aura_streak),
            Modifier.align(Alignment.TopStart).offset(x = 20.dp, y = 22.dp))
        StatChip("${streak.weekPlays}", stringResource(R.string.home_aura_plays),
            Modifier.align(Alignment.TopEnd).offset(x = (-20).dp, y = 52.dp))
        if (topArtist != null) {
            StatChip(shownArtist(topArtist), stringResource(R.string.home_aura_top),
                Modifier.align(Alignment.TopStart).offset(x = 26.dp, y = 200.dp))
        }
        StatChip("$songs", stringResource(R.string.home_aura_songs),
            Modifier.align(Alignment.TopEnd).offset(x = (-26).dp, y = 186.dp))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Eyebrow(stringResource(R.string.home_aura_eyebrow))
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.home_aura_title), style = Typo.Section.copy(fontWeight = FontWeight.Bold),
                color = MediaColors.Cream, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            WhiteButton(stringResource(R.string.home_play_mix), onPlayMix)
        }
    }
}

@Composable
private fun StatChip(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.06f))))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(value, style = Typo.Label.copy(fontWeight = FontWeight.Bold), color = MediaColors.Cream,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = Typo.Tertiary, color = MediaColors.CreamDim, maxLines = 1)
    }
}
