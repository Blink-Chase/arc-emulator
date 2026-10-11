package com.blinkchase.arc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import com.blinkchase.arc.ui.theme.ArcEmuTheme

@Composable
fun ArcNavigationRail(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        NavigationRailItem(
            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
            label = { Text("Home") },
            selected = currentRoute == Screen.HOME.name,
            onClick = { onNavigate(Screen.HOME) }
        )
        NavigationRailItem(
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Library") },
            label = { Text("Library") },
            selected = currentRoute == Screen.LIBRARY.name,
            onClick = { onNavigate(Screen.LIBRARY) }
        )
        NavigationRailItem(
            icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            label = { Text("Search") },
            selected = currentRoute == Screen.SEARCH.name,
            onClick = { onNavigate(Screen.SEARCH) }
        )
        NavigationRailItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            selected = currentRoute == Screen.SETTINGS.name,
            onClick = { onNavigate(Screen.SETTINGS) }
        )
    }
}

@Preview(name = "Landscape navigation rail", showBackground = true, widthDp = 800, heightDp = 400)
@Composable
private fun ArcNavigationRailPreview() {
    ArcEmuTheme(darkTheme = true) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding()
                    )
            ) {
                ArcNavigationRail(
                    currentRoute = Screen.LIBRARY.name,
                    onNavigate = {},
                    modifier = Modifier.fillMaxHeight()
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            start = innerPadding.calculateStartPadding(
                                LocalLayoutDirection.current
                            ),
                            end = innerPadding.calculateEndPadding(
                                LocalLayoutDirection.current
                            )
                        )
                        .padding(24.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
    }
}
