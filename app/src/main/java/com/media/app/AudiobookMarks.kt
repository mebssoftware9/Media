package com.media.app

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

// ============================================================================
//  CHAPTERS AND BOOKMARKS (podcasts and audiobooks)
//
//  Chapters come from the file itself (ChapterReader.kt) and are read once
//  per item, off the main thread. Bookmarks are the listener's own: times in
//  one item, kept in the app's private preferences under the item's uri, so
//  they survive restarts and never touch the file.
//
//  Now Playing shows, for long-form audio, the chapter being heard and the
//  time left in it under the scrubber, and a Chapters and bookmarks button
//  in its lower row that opens the sheet below.
// ============================================================================

object Chapters {
    private val cache = HashMap<String, List<Chapter>>()

    /** This item's chapters, or empty. Blocking file read on IO, cached by uri. */
    suspend fun load(context: Context, uri: String): List<Chapter> {
        synchronized(cache) { cache[uri]?.let { return it } }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                        ChapterReader.read(ChannelSource(ch))
                    }
                }
            }.getOrNull().orEmpty()
        }
        synchronized(cache) {
            if (cache.size > 64) cache.clear()
            cache[uri] = found
        }
        return found
    }
}

object Bookmarks {
    private const val PREFS = "bookmarks"
    private val _all = MutableStateFlow<Map<String, List<Long>>>(emptyMap())
    val all: StateFlow<Map<String, List<Long>>> = _all.asStateFlow()
    private var loaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        _all.value = prefs(context).all.mapNotNull { (k, v) ->
            (v as? String)?.let { k to parse(it) }
        }.toMap()
        loaded = true
    }

    private fun parse(s: String): List<Long> =
        s.split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it >= 0 }.distinct().sorted()

    private fun save(context: Context, key: String, list: List<Long>) {
        val p = prefs(context).edit()
        if (list.isEmpty()) p.remove(key) else p.putString(key, list.joinToString(","))
        p.apply()
        _all.value = if (list.isEmpty()) _all.value - key else _all.value + (key to list)
    }

    /** Adds [ms] to this item's bookmarks. One within 2 s of another replaces it. */
    fun add(context: Context, key: String, ms: Long) {
        load(context)
        val now = _all.value[key].orEmpty().filter { kotlin.math.abs(it - ms) > 2_000L }
        save(context, key, (now + ms).sorted())
    }

    fun remove(context: Context, key: String, ms: Long) {
        load(context)
        save(context, key, _all.value[key].orEmpty() - ms)
    }
}

/** This item's chapters, loaded when the item changes. Empty for anything else. */
@Composable
fun rememberChapters(uri: String?, longForm: Boolean): List<Chapter> {
    val context = LocalContext.current
    var chapters by remember(uri) { mutableStateOf(emptyList<Chapter>()) }
    LaunchedEffect(uri, longForm) {
        chapters = if (uri != null && longForm) Chapters.load(context, uri) else emptyList()
    }
    return chapters
}

/** Under the scrubber: "Chapter 3 of 12 · Title" and the time left in it. */
@Composable
fun ChapterLine(chapters: List<Chapter>, positionMs: Long, durationMs: Long, onClick: () -> Unit) {
    val i = chapterIndexAt(chapters, positionMs)
    if (i < 0) return
    val c = chapters[i]
    val end = chapters.getOrNull(i + 1)?.startMs ?: durationMs
    val left = (end - positionMs).coerceAtLeast(0L)
    Row(
        Modifier.fillMaxWidth().padding(top = Space.xs)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            buildString {
                append(stringResource(R.string.chapter_of, i + 1, chapters.size))
                if (c.title.isNotBlank()) append("  ·  ").append(c.title)
            },
            style = Typo.Tertiary, color = MediaColors.CreamDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Space.sm))
        Text(stringResource(R.string.chapter_left, fmtClock(left)), style = Typo.Tertiary, color = MediaColors.CreamFaint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersAndBookmarksSheet(
    state: PlayerState,
    chapters: List<Chapter>,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val key = state.currentUri ?: return
    LaunchedEffect(Unit) { Bookmarks.load(context) }
    val all by Bookmarks.all.collectAsState()
    val marks = all[key].orEmpty()
    val current = chapterIndexAt(chapters, state.positionMs)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MediaColors.Modal,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MediaColors.CreamFaint) }
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 560.dp)
                .padding(horizontal = Space.xl)
        ) {
            item {
                Text(stringResource(R.string.marks_title), style = MaterialTheme.typography.titleLarge, color = MediaColors.Cream)
                Spacer(Modifier.height(Space.lg))
                // Add a bookmark here: the one action, first.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md))
                        .background(MediaColors.FillSubtle)
                        .clickable { Bookmarks.add(context, key, state.positionMs) }
                        .padding(Space.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.md)
                ) {
                    Icon(Icons.Outlined.BookmarkAdd, null, tint = MediaColors.Accent, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.marks_add, fmtClock(state.positionMs)),
                        style = MaterialTheme.typography.bodyLarge, color = MediaColors.Cream)
                }
                Spacer(Modifier.height(Space.lg))
                Text(stringResource(R.string.marks_bookmarks).uppercase(), style = Typo.Micro, color = MediaColors.CreamDim)
                if (marks.isEmpty()) {
                    Text(stringResource(R.string.marks_none), style = Typo.Secondary, color = MediaColors.CreamFaint,
                        modifier = Modifier.padding(vertical = Space.md))
                }
            }
            items(marks, key = { "b$it" }) { ms ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSeek(ms); onDismiss() }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(fmtClock(ms), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MediaColors.Cream, modifier = Modifier.width(76.dp))
                    val inChapter = chapterIndexAt(chapters, ms)
                    Text(
                        if (inChapter >= 0) chapters[inChapter].title.ifBlank { stringResource(R.string.chapter_of, inChapter + 1, chapters.size) } else "",
                        style = Typo.Secondary, color = MediaColors.CreamDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Outlined.Close, stringResource(R.string.marks_remove), tint = MediaColors.CreamFaint,
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(Radius.pill))
                            .clickable { Bookmarks.remove(context, key, ms) }.padding(8.dp))
                }
            }
            if (chapters.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(Space.lg))
                    Text(stringResource(R.string.marks_chapters).uppercase(), style = Typo.Micro, color = MediaColors.CreamDim)
                    Spacer(Modifier.height(Space.xs))
                }
                items(chapters.size, key = { "c$it" }) { i ->
                    val c = chapters[i]
                    val on = i == current
                    Row(
                        Modifier.fillMaxWidth().clickable { onSeek(c.startMs); onDismiss() }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${i + 1}", style = Typo.Secondary, color = if (on) MediaColors.Accent else MediaColors.CreamFaint,
                            modifier = Modifier.width(36.dp))
                        Text(c.title.ifBlank { stringResource(R.string.chapter_of, i + 1, chapters.size) },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (on) MediaColors.Accent else MediaColors.Cream,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(fmtClock(c.startMs), style = Typo.Tertiary, color = MediaColors.CreamFaint)
                    }
                }
            }
            item { Spacer(Modifier.height(Space.xl)) }
        }
    }
}
