package dev.digi.digix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.digi.digix.data.Meta
import dev.digi.digix.ui.components.EmptyState
import dev.digi.digix.ui.components.PosterCard
import dev.digi.digix.ui.theme.*

@Composable
fun LibraryScreen(
    items: List<Meta>,
    onMetaClick: (Meta) -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = uiMetrics()
    Column(modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier.padding(
                start = m.d(TvDimens.contentStart),
                end = m.d(TvDimens.contentStart),
                top = m.d(28.dp),
                bottom = m.d(14.dp),
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "My List",
                color = OnBg,
                fontSize = m.f(26f),
                fontWeight = FontWeight.Bold,
            )
            if (items.isNotEmpty()) {
                Spacer(Modifier.width(m.d(12.dp)))
                Text(
                    "(${items.size})",
                    color = BrandAccent,
                    fontSize = m.f(18f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("Titles you add to My List will appear here.")
            }
        } else {
            // Adaptive rather than Fixed(5): five columns is right for a
            // 960dp TV panel but produced postcards on a 360dp phone. The
            // minimum tile size tracks the poster token so the grid holds the
            // same visual density from phone through tablet to TV.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = m.d(TvDimens.posterWidth)),
                contentPadding = PaddingValues(
                    start = m.d(TvDimens.contentStart),
                    end = m.d(TvDimens.contentStart),
                    top = m.d(8.dp),
                    bottom = m.d(8.dp),
                ),
                horizontalArrangement = Arrangement.spacedBy(m.d(TvDimens.rowGap)),
                verticalArrangement = Arrangement.spacedBy(m.d(16.dp)),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items) { meta ->
                    PosterCard(meta = meta, onClick = { onMetaClick(meta) })
                }
            }
        }
    }
}
