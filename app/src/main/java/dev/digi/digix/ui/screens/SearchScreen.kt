package dev.digi.digix.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.digi.digix.data.Meta
import dev.digi.digix.ui.MainViewModel
import dev.digi.digix.ui.components.PosterCard
import dev.digi.digix.ui.theme.*

private val GENRE_PILLS = listOf(
    "Trending",
    "Hollywood",
    "Bollywood",
    "South Indian",
    "K-Drama",
    "Anime",
    "Action",
    "Comedy",
    "Sci-Fi",
    "Horror",
    "Thriller",
    "Romance",
)

@Composable
fun SearchScreen(
    vm: MainViewModel,
    onMetaClick: (Meta, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val searchLoading by vm.searchLoading.collectAsStateWithLifecycle()
    val currentQuery by vm.searchQuery.collectAsStateWithLifecycle()

    var textInput by remember { mutableStateOf(currentQuery) }
    var selectedGenre by remember { mutableStateOf<String?>("Trending") }
    val focusManager = LocalFocusManager.current
    val inputFocusRequester = remember { FocusRequester() }

    // On initial launch of SearchScreen, populate trending if results are empty
    LaunchedEffect(Unit) {
        if (searchResults.isEmpty()) {
            vm.search("Trending")
        }
    }

    val m = uiMetrics()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .padding(top = m.d(24.dp), bottom = m.d(14.dp)),
    ) {
        // 1. Search input bar
        var isInputFocused by remember { mutableStateOf(false) }
        val inputBorderColor by animateColorAsState(
            targetValue = if (isInputFocused) Color.White else Color(0x33FFFFFF),
            animationSpec = tween(180),
            label = "inputBorder",
        )
        val inputBgColor by animateColorAsState(
            targetValue = if (isInputFocused) Color(0xFF242424) else Color(0xFF161616),
            animationSpec = tween(180),
            label = "inputBg",
        )

        Row(
            modifier = Modifier
                .padding(horizontal = m.d(TvDimens.contentStart))
                // Was fillMaxWidth(0.92f), which on top of the horizontal padding
                // left the bar oddly short on a phone.
                .fillMaxWidth()
                .height(m.d(48.dp))
                .clip(RoundedCornerShape(m.d(8.dp)))
                .background(inputBgColor)
                .border(if (isInputFocused) m.d(2.dp) else m.d(1.dp), inputBorderColor, RoundedCornerShape(m.d(8.dp)))
                .onFocusChanged { isInputFocused = it.hasFocus }
                .padding(horizontal = m.d(14.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = if (isInputFocused) BrandAccent else Color(0xFF888888),
                modifier = Modifier.size(m.d(21.dp)),
            )
            Spacer(Modifier.width(m.d(10.dp)))

            BasicTextField(
                value = textInput,
                onValueChange = {
                    textInput = it
                    selectedGenre = null
                    vm.search(it)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(inputFocusRequester),
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = m.f(15f),
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(BrandAccent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        vm.search(textInput)
                    }
                ),
                decorationBox = { innerTextField ->
                    if (textInput.isBlank()) {
                        Text(
                            "Search movies, series, anime…",
                            color = Color(0xFF707070),
                            fontSize = m.f(13f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                },
            )

            if (textInput.isNotBlank()) {
                IconButton(
                    onClick = {
                        textInput = ""
                        selectedGenre = "Trending"
                        vm.search("Trending")
                    },
                    modifier = Modifier.size(m.d(30.dp)),
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = Color(0xFFAAAAAA),
                        modifier = Modifier.size(m.d(18.dp)),
                    )
                }
            }
        }

        Spacer(Modifier.height(m.d(14.dp)))

        // 2. Quick Genre & Topic Pills Row
        LazyRow(
            contentPadding = PaddingValues(horizontal = m.d(TvDimens.contentStart)),
            horizontalArrangement = Arrangement.spacedBy(m.d(9.dp)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(GENRE_PILLS) { genre ->
                val isSelected = selectedGenre == genre || (textInput.equals(genre, ignoreCase = true))
                var isPillFocused by remember { mutableStateOf(false) }

                val pillScale by animateFloatAsState(
                    targetValue = if (isPillFocused) 1.08f else 1f,
                    animationSpec = tween(150, easing = FastOutSlowInEasing),
                    label = "pillScale",
                )
                val pillBg by animateColorAsState(
                    targetValue = when {
                        isPillFocused -> Color.White
                        isSelected -> BrandAccent
                        else -> Color(0xFF222222)
                    },
                    animationSpec = tween(150),
                    label = "pillBg",
                )
                val pillText by animateColorAsState(
                    targetValue = when {
                        // Selected fills with the accent, which is white, so the
                        // label has to be black or it disappears.
                        isPillFocused -> OnAccent
                        isSelected -> OnAccent
                        else -> Color(0xFFCCCCCC)
                    },
                    animationSpec = tween(150),
                    label = "pillText",
                )

                Box(
                    modifier = Modifier
                        .scale(pillScale)
                        .clip(RoundedCornerShape(m.d(20.dp)))
                        .background(pillBg)
                        .clickable {
                            selectedGenre = genre
                            textInput = genre
                            vm.search(genre)
                        }
                        .onFocusChanged { isPillFocused = it.hasFocus }
                        .padding(horizontal = m.d(15.dp), vertical = m.d(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = genre,
                        color = pillText,
                        fontSize = m.f(12.5f),
                        fontWeight = if (isSelected || isPillFocused) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(Modifier.height(m.d(18.dp)))

        // 3. Status or Results Header
        Row(
            modifier = Modifier
                .padding(horizontal = m.d(TvDimens.contentStart))
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val titleText = when {
                searchLoading -> "Searching titles…"
                textInput.isBlank() || selectedGenre == "Trending" -> "Trending on Digi X TV"
                searchResults.isNotEmpty() -> "Results for \"$textInput\" (${searchResults.size})"
                else -> "No results found for \"$textInput\""
            }
            Text(
                text = titleText,
                color = OnBg,
                fontSize = m.f(16f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (searchLoading) {
                Spacer(Modifier.width(m.d(10.dp)))
                CircularProgressIndicator(
                    color = BrandAccent,
                    modifier = Modifier.size(m.d(14.dp)),
                    strokeWidth = m.d(2.dp),
                )
            }
        }

        Spacer(Modifier.height(m.d(12.dp)))

        // 4. Posters grid
        if (searchResults.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = m.d(TvDimens.posterWidth)),
                contentPadding = PaddingValues(
                    start = m.d(TvDimens.contentStart),
                    end = m.d(TvDimens.contentStart),
                    bottom = m.d(44.dp),
                ),
                horizontalArrangement = Arrangement.spacedBy(m.d(TvDimens.rowGap)),
                verticalArrangement = Arrangement.spacedBy(m.d(14.dp)),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(searchResults, key = { it.id }) { meta ->
                    PosterCard(
                        meta = meta,
                        onClick = {
                            onMetaClick(meta, meta.sourceAddon.orEmpty())
                        },
                    )
                }
            }
        } else if (!searchLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = m.d(TvDimens.contentStart), vertical = m.d(36.dp)),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    text = "Try searching for movie names, series, anime, actors, or select a genre above.",
                    color = Color(0xFF888888),
                    fontSize = m.f(13f),
                )
            }
        }
    }
}
