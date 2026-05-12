package com.musync.ui.createroom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.musync.R
import com.musync.data.model.YouTubeSearchResult
import com.musync.ui.components.SkeletonLine
import com.musync.util.YouTubeUrlParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRoomScreen(
    viewModel: CreateRoomViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onRoomCreated: (sessionId: String, videoId: String) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Trigger navigation once the ViewModel signals the room has been started.
    LaunchedEffect(uiState.started, uiState.sessionId, uiState.videoId) {
        val sessionId = uiState.sessionId
        val videoId = uiState.videoId
        if (uiState.started && sessionId != null && videoId != null) {
            onRoomCreated(sessionId, videoId)
            viewModel.onNavigationConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_room_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.create_room_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),        ) {
            item {
                Spacer(Modifier.height(8.dp))

                // ── YouTube URL / search field ─────────────────────────────────────
                OutlinedTextField(
                    value = uiState.urlInput,
                    onValueChange = viewModel::onUrlChanged,
                    label = { Text(stringResource(R.string.create_room_url_label)) },
                    singleLine = true,
                    isError = uiState.urlError,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions =
                        KeyboardActions(
                            onSearch = {
                                keyboardController?.hide()
                                viewModel.onSearch()
                            },
                        ),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = {
                                val pasted = clipboardManager.getText()?.text.orEmpty()
                                if (pasted.isNotEmpty()) viewModel.onUrlChanged(pasted)
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.ContentPaste,
                                    contentDescription = stringResource(R.string.create_room_paste),
                                )
                            }
                            IconButton(onClick = {
                                keyboardController?.hide()
                                viewModel.onSearch()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.create_room_search),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Inline validation hint ────────────────────────────────────────
                UrlValidationHint(
                    videoId = uiState.videoId,
                    urlError = uiState.urlError,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )

                Spacer(Modifier.height(12.dp))
            }

            // ── Search loading indicator / error ──────────────────────────────
            if (uiState.isSearching) {
                item {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            } else if (uiState.searchError) {
                item {
                    Text(
                        text = stringResource(R.string.create_room_search_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                }
            }

            // ── Search results list ────────────────────────────────────────────
            if (uiState.searchResults.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.create_room_search_results),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                items(items = uiState.searchResults, key = { it.videoId }) { result ->
                    SearchResultRow(
                        result = result,
                        onClick = { viewModel.onSearchResultSelected(result) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // ── Video preview card (only when a valid id was parsed) ──────────
            if (uiState.videoId != null && uiState.searchResults.isEmpty() && !uiState.isSearching) {
                item {
                    VideoPreviewCard(
                        videoId = uiState.videoId!!,
                        videoTitle = uiState.videoTitle,
                        channelTitle = uiState.channelTitle,
                        isFetchingVideoInfo = uiState.isFetchingVideoInfo,
                        videoInfoError = uiState.videoInfoError,
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }

            item {
                // ── Display name field ────────────────────────────────────────────
                OutlinedTextField(
                    value = uiState.displayName,
                    onValueChange = viewModel::onDisplayNameChanged,
                    label = { Text(stringResource(R.string.create_room_name_label)) },
                    placeholder = { Text(uiState.displayNamePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(28.dp))

                // ── Start Room CTA ────────────────────────────────────────────
                Button(
                    onClick = viewModel::onStartRoom,
                    enabled = uiState.videoId != null,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                ) {
                    Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.create_room_start), fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun UrlValidationHint(
    videoId: String?,
    urlError: Boolean,
    modifier: Modifier = Modifier,
) {
    when {
        videoId != null -> {
            Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(
                    text = stringResource(R.string.create_room_url_valid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        urlError -> {
            Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(
                    text = stringResource(R.string.create_room_url_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: YouTubeSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(0.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = result.thumbnailUrl,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(width = 96.dp, height = 54.dp)
                        .clip(RoundedCornerShape(4.dp)),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = result.channelTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun VideoPreviewCard(
    videoId: String,
    videoTitle: String?,
    channelTitle: String?,
    isFetchingVideoInfo: Boolean,
    videoInfoError: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            ) {
                AsyncImage(
                    model = YouTubeUrlParser.thumbnailUrl(videoId),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(56.dp),
                )
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = videoTitle ?: videoId,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isFetchingVideoInfo) {
                    val loadingDescription = stringResource(R.string.cd_video_preview_loading)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                // Announce loading state changes to screen readers
                                // (#51) instead of relying on the spinner alone.
                                .semantics {
                                    contentDescription = loadingDescription
                                    liveRegion = LiveRegionMode.Polite
                                },
                    ) {
                        // Skeleton placeholder for the channel-title row while
                        // we resolve metadata, replacing the lone spinner with
                        // a clear "content is loading" affordance (#52).
                        SkeletonLine(
                            modifier = Modifier.fillMaxWidth(0.6f),
                            height = 12.dp,
                        )
                    }
                } else {
                    Text(
                        text = channelTitle?.takeIf { it.isNotBlank() } ?: "youtube.com/watch?v=$videoId",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (videoInfoError) {
                    Text(
                        text = stringResource(R.string.video_info_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
