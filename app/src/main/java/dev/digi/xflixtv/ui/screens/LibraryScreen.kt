package dev.digi.xflixtv.ui.screens

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
import dev.digi.xflixtv.data.Meta
import dev.digi.xflixtv.ui.components.EmptyState
import dev.digi.xflixtv.ui.components.PosterCard
import dev.digi.xflixtv.ui.theme.*

@Composable
fun LibraryScreen(
    items: List<Meta>,
    onMetaClick: (Meta) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier.padding(start = TvDimens.contentStart, top = 28.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "My List",
                color = OnBg,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            if (items.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "(${items.size})",
                    color = BrandRed,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("Titles you add to My List will appear here.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(horizontal = TvDimens.contentStart, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items) { meta ->
                    PosterCard(meta = meta, onClick = { onMetaClick(meta) })
                }
            }
        }
    }
}
