package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.History
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

// ============================================================================
//  SEARCH (§19)
//
//  "Search must be extremely fast. When opened, focus immediately."
//
//  Matching runs against a PRE-LOWERCASED index built once per library change,
//  not per keystroke. The old version called .contains(query, true) twice per
//  item on every character typed, which case-folds the whole library each
//  time — fine at 200 tracks, not at §38's 10,000.
// ============================================================================

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    all: List<AppMediaItem>,
    onPlay: (List<AppMediaItem>, Int) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onBrowseLibrary: () -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { RecentSearches.load(context) }
    val recents by RecentSearches.list.collectAsState()
    // A search counts as made when a result is opened or Search is pressed.
    fun keepQuery() { RecentSearches.add(context, query) }
    val keyboard = LocalSoftwareKeyboardController.current

    // §19: focus immediately, don't make people tap the field they just opened.
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    // Built once per library, not per keystroke.
    val index = remember(all) {
        all.map { it to searchFold("${it.title} ${it.artist} ${it.album}") }
    }
    val albums = remember(all) { MediaRepository.albumsOf(all) }
    val artists = remember(all) { MediaRepository.artistsOf(all) }

    val q = searchFold(query.trim())
    val trackHits = remember(q, index) {
        if (q.isBlank()) emptyList() else index.filter { it.second.contains(q) }.map { it.first }
    }
    val albumHits = remember(q, albums) {
        if (q.isBlank()) emptyList()
        else albums.filter { searchFold(it.name).contains(q) || searchFold(it.artist).contains(q) }
            .take(8)
    }
    val artistHits = remember(q, artists) {
        if (q.isBlank()) emptyList()
        else artists.filter { searchFold(it.name).contains(q) }.take(8)
    }
    val nothing = q.isNotBlank() && trackHits.isEmpty() && albumHits.isEmpty() && artistHits.isEmpty()

    Column(Modifier.fillMaxSize().screenBackground().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(Space.md, Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MediaColors.Cream)
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(Radius.md))
                    .background(MediaColors.Elevated).padding(Space.md, 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, null, tint = MediaColors.CreamDim,
                    modifier = Modifier.size(IconSize.md))
                Spacer(Modifier.width(Space.sm))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.search_hint), style = Typo.Body, color = MediaColors.CreamFaint)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = Typo.Body.copy(color = MediaColors.Cream),
                        cursorBrush = SolidColor(MediaColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keepQuery(); keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Filled.Close, stringResource(R.string.action_clear), tint = MediaColors.CreamDim,
                        modifier = Modifier.size(IconSize.md).clickable { query = "" })
                }
            }
        }

        when {
            q.isBlank() && recents.isNotEmpty() -> RecentList(
                recents = recents,
                onPick = { query = it },
                onRemove = { RecentSearches.remove(context, it) },
                onClear = { RecentSearches.clear(context) }
            )
            q.isBlank() -> CenterHint(stringResource(R.string.search_empty_hint))

            // §19's exact prescription: name the situation, then offer a way out.
            nothing -> Column(
                Modifier.fillMaxSize().padding(Space.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.search_no_matches_title), style = Typo.Section, color = MediaColors.Cream)
                Spacer(Modifier.height(Space.sm))
                Text(
                    stringResource(R.string.search_no_matches_body, query),
                    style = Typo.Secondary, color = MediaColors.CreamFaint
                )
                Spacer(Modifier.height(Space.xl))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Box(
                        Modifier.clip(CircleShape).background(MediaColors.Accent)
                            .pressScale(haptic = true) { query = "" }
                            .padding(horizontal = Space.xl, vertical = Space.md)
                    ) { Text(stringResource(R.string.action_search_again), style = Typo.Label, color = Color.White) }
                    Box(
                        Modifier.clip(CircleShape).background(MediaColors.Elevated)
                            .pressScale(haptic = true, onClick = onBrowseLibrary)
                            .padding(horizontal = Space.xl, vertical = Space.md)
                    ) { Text(stringResource(R.string.search_browse), style = Typo.Label, color = MediaColors.Cream) }
                }
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = bottomSafePadding(gap = 100.dp))
            ) {
                // §19 "meaningful result sections". Artists and albums first —
                // they're fewer and more specific, so a match there is a
                // stronger signal than one of forty track matches.
                if (artistHits.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.label_artists)) }
                    items(artistHits.size) { i ->
                        val a = artistHits[i]
                        ResultRow(
                            title = shownArtist(a.name),
                            subtitle = plural(R.plurals.count_songs, a.trackCount),
                            art = a.tracks.firstOrNull(),
                            circular = true
                        ) { keepQuery(); onOpenArtist(a) }
                    }
                }
                if (albumHits.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.label_albums)) }
                    items(albumHits.size) { i ->
                        val al = albumHits[i]
                        ResultRow(
                            title = shownAlbum(al.name),
                            subtitle = shownArtist(al.artist),
                            art = al.tracks.firstOrNull(),
                            circular = false
                        ) { keepQuery(); onOpenAlbum(al) }
                    }
                }
                if (trackHits.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.label_tracks)) }
                    items(trackHits.size) { i ->
                        val t = trackHits[i]
                        ResultRow(
                            title = shownTitle(t.title),
                            subtitle = shownArtist(t.artist),
                            art = t,
                            circular = false
                        ) { keepQuery(); onPlay(trackHits, i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    art: AppMediaItem?,
    circular: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(Space.xl, Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(if (circular) CircleShape else RoundedCornerShape(Radius.sm))) {
            if (art != null) {
                CoverArt(art, Modifier.fillMaxSize(), corner = 0, targetPx = 144)
            }
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = Typo.Primary, color = MediaColors.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Typo.Secondary, color = MediaColors.CreamDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = Typo.Body, color = MediaColors.CreamFaint)
    }
}

/** What was searched for before, newest first: tap to search it again. */
@Composable
private fun RecentList(
    recents: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = bottomSafePadding(gap = 100.dp))) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(Space.xl, Space.lg, Space.md, Space.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.search_recent).uppercase(), style = Typo.Micro,
                    color = MediaColors.CreamDim, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.action_clear), style = Typo.Label, color = MediaColors.Accent,
                    modifier = Modifier.clip(RoundedCornerShape(Radius.pill)).clickable(onClick = onClear)
                        .padding(Space.md, Space.sm))
            }
        }
        items(recents.size, key = { recents[it] }) { i ->
            val r = recents[i]
            Row(
                Modifier.fillMaxWidth().clickable { onPick(r) }.padding(start = Space.xl, end = Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.History, null, tint = MediaColors.CreamFaint, modifier = Modifier.size(IconSize.md))
                Spacer(Modifier.width(Space.md))
                Text(r, style = Typo.Primary, color = MediaColors.Cream, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
                Icon(Icons.Filled.Close, stringResource(R.string.search_recent_remove), tint = MediaColors.CreamFaint,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable { onRemove(r) }.padding(12.dp))
            }
        }
    }
}

/** The last eight searches, in the app's private preferences. */
object RecentSearches {
    private const val PREFS = "recent_searches"
    private const val KEY = "list"
    private const val MAX = 8
    private val _list = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val list: kotlinx.coroutines.flow.StateFlow<List<String>> = _list
    private var loaded = false

    private fun prefs(c: android.content.Context) =
        c.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun load(c: android.content.Context) {
        if (loaded) return
        _list.value = prefs(c).getString(KEY, null).orEmpty().split('\n').filter { it.isNotBlank() }.take(MAX)
        loaded = true
    }

    private fun save(c: android.content.Context, l: List<String>) {
        _list.value = l
        prefs(c).edit().putString(KEY, l.joinToString("\n")).apply()
    }

    fun add(c: android.content.Context, query: String) {
        load(c)
        val q = query.trim().replace('\n', ' ')
        if (q.isEmpty()) return
        save(c, (listOf(q) + _list.value.filterNot { it.equals(q, ignoreCase = true) }).take(MAX))
    }

    fun remove(c: android.content.Context, query: String) { load(c); save(c, _list.value - query) }
    fun clear(c: android.content.Context) { load(c); save(c, emptyList()) }
}

/** Lower case without accents: "Beyoncé" and "beyonce" match each other. */
internal fun searchFold(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
