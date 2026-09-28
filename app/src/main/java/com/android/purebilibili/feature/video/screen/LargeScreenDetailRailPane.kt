package com.android.purebilibili.feature.video.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppSegmentOption
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppThemeAdaptiveTabRow
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.feature.video.share.VideoSharePayload
import com.android.purebilibili.feature.video.share.VideoShareSheetHost
import com.android.purebilibili.feature.video.share.buildVideoSharePayload
import com.android.purebilibili.feature.video.ui.components.CommentSearchSheet
import com.android.purebilibili.feature.video.ui.section.ActionButtonsGrid
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import com.android.purebilibili.feature.video.viewmodel.CommentUiState
import com.android.purebilibili.feature.video.viewmodel.VideoEngagementUiState
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.withEngagementUiState
import kotlinx.coroutines.launch

/**
 * The detail area under the near-square layout's player: a vertical rail on the left and one page
 * beside it. The info page puts the video info next to the recommendations; the comments page
 * gives the comment list the whole width. Each page keeps its scroll position across switches.
 */
@Composable
internal fun LargeScreenDetailRailPane(
    success: VideoPlaybackUiState.Success,
    engagementState: VideoEngagementUiState,
    commentState: CommentUiState,
    subReplyVisible: Boolean,
    downloadProgress: Float,
    playbackActions: VideoDetailPlaybackActions,
    engagementActions: VideoDetailEngagementActions,
    commentActions: VideoDetailCommentActions,
    infoContent: @Composable (Modifier) -> Unit,
    relatedContent: @Composable (Modifier) -> Unit,
    commentsContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bvid = success.info.bvid
    var page by rememberSaveable(bvid) { mutableStateOf(DEFAULT_LARGE_SCREEN_DETAIL_RAIL_PAGE) }
    var showCommentSearch by remember(bvid) { mutableStateOf(false) }
    var pendingVideoShare by remember { mutableStateOf<VideoSharePayload?>(null) }
    // A reply thread only opens from the comments page, but keep it visible if it opens elsewhere.
    LaunchedEffect(subReplyVisible) {
        if (subReplyVisible) page = LargeScreenDetailRailPage.COMMENTS
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageStateHolder = rememberSaveableStateHolder()
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Row(modifier = modifier) {
        LargeScreenDetailRail(
            page = page,
            onPageChange = { page = it },
            success = success,
            engagementState = engagementState,
            commentState = commentState,
            downloadProgress = downloadProgress,
            playbackActions = playbackActions,
            engagementActions = engagementActions,
            onShareClick = {
                pendingVideoShare = buildVideoSharePayload(
                    title = success.info.title,
                    bvid = bvid,
                    coverUrl = success.info.pic,
                    upName = success.info.owner.name,
                    playCountText = FormatUtils.formatStat(success.info.stat.view.toLong()),
                )
            },
            onSortModeChange = { mode ->
                commentActions.setSortMode(mode)
                scope.launch { SettingsManager.setCommentDefaultSortMode(context, mode.apiMode) }
            },
            onCommentSearchClick = { showCommentSearch = true },
            modifier = Modifier
                .width(LARGE_SCREEN_DETAIL_RAIL_WIDTH_DP.dp)
                .fillMaxHeight(),
        )
        VerticalDivider(modifier = Modifier.fillMaxHeight(), color = dividerColor, thickness = 1.dp)
        Crossfade(
            targetState = page,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            label = "largeScreenDetailRailPage",
        ) { target ->
            pageStateHolder.SaveableStateProvider("${bvid}_${target.name}") {
                when (target) {
                    LargeScreenDetailRailPage.INFO -> Row(modifier = Modifier.fillMaxSize()) {
                        infoContent(
                            Modifier
                                .weight(1f - LARGE_SCREEN_DETAIL_RAIL_RELATED_FRACTION)
                                .fillMaxHeight(),
                        )
                        VerticalDivider(
                            modifier = Modifier.fillMaxHeight(),
                            color = dividerColor,
                            thickness = 1.dp,
                        )
                        relatedContent(
                            Modifier
                                .weight(LARGE_SCREEN_DETAIL_RAIL_RELATED_FRACTION)
                                .fillMaxHeight(),
                        )
                    }
                    LargeScreenDetailRailPage.COMMENTS -> commentsContent(Modifier.fillMaxSize())
                }
            }
        }
    }

    VideoShareSheetHost(
        payload = pendingVideoShare,
        onDismiss = { pendingVideoShare = null },
    )
    if (showCommentSearch) {
        CommentSearchSheet(
            replies = commentState.replies,
            upMid = success.info.owner.mid,
            onCommentClick = playbackActions.replyTo,
            onSubReplyClick = { rootReply -> commentActions.openSubReply(rootReply, 0L) },
            onDismiss = { showCommentSearch = false },
        )
    }
}

@Composable
private fun LargeScreenDetailRail(
    page: LargeScreenDetailRailPage,
    onPageChange: (LargeScreenDetailRailPage) -> Unit,
    success: VideoPlaybackUiState.Success,
    engagementState: VideoEngagementUiState,
    commentState: CommentUiState,
    downloadProgress: Float,
    playbackActions: VideoDetailPlaybackActions,
    engagementActions: VideoDetailEngagementActions,
    onShareClick: () -> Unit,
    onSortModeChange: (CommentSortMode) -> Unit,
    onCommentSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageOptions = remember {
        LargeScreenDetailRailPage.entries.map { AppSegmentOption(it, it.label) }
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppThemeAdaptiveTabRow(
            options = pageOptions,
            selectedValue = page,
            onSelectionChange = onPageChange,
            modifier = Modifier.fillMaxWidth(),
            labelFontSize = 13.sp,
            compactMiuixWhenTwoOptions = true,
            dragSelectionEnabled = true,
            tapPressRefractionEnabled = false,
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        when (page) {
            LargeScreenDetailRailPage.INFO -> {
                val info = success.withEngagementUiState(engagementState).info
                ActionButtonsGrid(
                    info = info,
                    isFavorited = engagementState.isFavorited,
                    isLiked = engagementState.isLiked,
                    coinCount = engagementState.coinCount,
                    downloadProgress = downloadProgress,
                    isInWatchLater = engagementState.isInWatchLater,
                    onFavoriteClick = { engagementActions.onFavoriteAction(false) },
                    onLikeClick = engagementActions.toggleLike,
                    onCoinClick = engagementActions.openCoinDialog,
                    onTripleClick = engagementActions.doTripleAction,
                    onDownloadClick = playbackActions.openDownloadDialog,
                    onWatchLaterClick = engagementActions.toggleWatchLater,
                    onFavoriteLongClick = { engagementActions.onFavoriteAction(true) },
                    onShareClick = onShareClick,
                    columns = LARGE_SCREEN_DETAIL_RAIL_ACTION_COLUMNS,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LargeScreenDetailRailPage.COMMENTS -> {
                AppText(
                    text = "${FormatUtils.formatStat(commentState.replyCount.toLong())} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(6.dp))
                AppThemeAdaptiveTabRow(
                    options = remember {
                        listOf(CommentSortMode.HOT, CommentSortMode.NEWEST)
                            .map { AppSegmentOption(it, it.label) }
                    },
                    selectedValue = commentState.sortMode,
                    onSelectionChange = onSortModeChange,
                    modifier = Modifier.fillMaxWidth(),
                    labelFontSize = 13.sp,
                    compactMiuixWhenTwoOptions = true,
                    dragSelectionEnabled = true,
                    tapPressRefractionEnabled = false,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    LargeScreenDetailRailButton(
                        icon = Icons.Outlined.Search,
                        label = "搜索",
                        onClick = onCommentSearchClick,
                        modifier = Modifier.weight(1f),
                    )
                    LargeScreenDetailRailButton(
                        icon = Icons.Outlined.Edit,
                        label = "发评论",
                        onClick = playbackActions.openRootCommentComposer,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LargeScreenDetailRailButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(AppShapes.container(ContainerLevel.Chip))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        AppText(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
