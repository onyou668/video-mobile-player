package io.github.mobileplayer.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.mobileplayer.compose.ContainedVideoPlayer
import io.github.mobileplayer.compose.ExpandableVideoPlayer
import io.github.mobileplayer.compose.PlayerFullscreenOrientation
import io.github.mobileplayer.compose.VideoPlayerColors
import io.github.mobileplayer.compose.rememberPlayerController
import io.github.mobileplayer.core.VideoCacheConfig

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SampleApp(onBack = ::finish) }
    }
}

private enum class DemoTab(val label: String) {
    CONTAINED("Contained"),
    EXPANDABLE("Expandable"),
}

private data class DemoSource(
    val label: String,
    val title: String,
    val url: String,
)

@Composable
private fun SampleApp(onBack: () -> Unit) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedSource by rememberSaveable { mutableIntStateOf(0) }
    val tab = DemoTab.entries[selectedTab]
    val source = DEMO_SOURCES[selectedSource]
    val colors = remember {
        VideoPlayerColors(
            background = Color.Black,
            controlsBackground = Color(0xB312171A),
            accent = Color(0xFF2DD4A7),
            buffered = Color.White.copy(alpha = 0.32f),
        )
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0E10)) {
            Column(Modifier.fillMaxSize()) {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    DemoTab.entries.forEachIndexed { index, item ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(item.label) },
                        )
                    }
                }
                SourceSelector(
                    selectedSource = selectedSource,
                    onSourceSelected = { selectedSource = it },
                )
                key(tab) {
                    val controller = rememberPlayerController(
                        cacheConfig = VideoCacheConfig(enabled = false),
                    )
                    when (tab) {
                        DemoTab.CONTAINED -> ContainedDemo(
                            source = source,
                            colors = colors,
                            controller = controller,
                            onBack = onBack,
                        )

                        DemoTab.EXPANDABLE -> ExpandableDemo(
                            source = source,
                            colors = colors,
                            controller = controller,
                            onBack = onBack,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceSelector(
    selectedSource: Int,
    onSourceSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121719))
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Test source",
            color = Color(0xFFB8C2BE),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DEMO_SOURCES.forEachIndexed { index, source ->
                FilterChip(
                    selected = selectedSource == index,
                    onClick = { onSourceSelected(index) },
                    label = { Text(source.label) },
                )
            }
        }
    }
}

@Composable
private fun ContainedDemo(
    source: DemoSource,
    colors: VideoPlayerColors,
    controller: io.github.mobileplayer.core.PlayerController,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        ContainedVideoPlayer(
            controller = controller,
            url = source.url,
            title = source.title,
            onBack = onBack,
            modifier = Modifier.fillMaxSize(),
            colors = colors,
        )
    }
}

@Composable
private fun ExpandableDemo(
    source: DemoSource,
    colors: VideoPlayerColors,
    controller: io.github.mobileplayer.core.PlayerController,
    onBack: () -> Unit,
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    ExpandableVideoPlayer(
        controller = controller,
        url = source.url,
        title = source.title,
        onBack = onBack,
        isFullscreen = fullscreen,
        onFullscreenChange = { fullscreen = it },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        orientation = PlayerFullscreenOrientation.LANDSCAPE,
        colors = colors,
    )
}

private val DEMO_SOURCES = listOf(
    DemoSource(
        label = "9:16 MP4",
        title = "Portrait source",
        url = "https://res.cloudinary.com/demo/video/upload/c_fill,h_1280,w_720/elephants.mp4",
    ),
    DemoSource(
        label = "16:9 HLS",
        title = "Landscape HLS",
        url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
    ),
    DemoSource(
        label = "4:3 HLS",
        title = "4:3 adaptive source",
        url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8",
    ),
    DemoSource(
        label = "DASH + CC",
        title = "DASH with subtitles",
        url = "https://storage.googleapis.com/shaka-demo-assets/sintel/dash.mpd",
    ),
    DemoSource(
        label = "Live HLS",
        title = "Live stream",
        url = "https://demo.unified-streaming.com/k8s/live/stable/live.isml/live.m3u8",
    ),
)
