package com.android.purebilibili.feature.video.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import com.android.purebilibili.core.ui.common.verticalPriorityHorizontalPagerSwipe
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
import com.android.purebilibili.navigation.animatePagerSelection
import kotlinx.coroutines.launch

/**
 * The detail area under the near-square layout's player: a vertical rail on the left and one page
 * beside it. The info page puts a header of the video info over the recommendations, which take the
 * rest of the height; while the header's details are open they get the whole page instead. The
 * comments page gives the comment list the whole area. Swiping sideways switches pages, as on the
 * phone layout, and each page keeps its scroll position across switches.
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
    /** Draws the info header into the modifier; reports whether its details are open. */
    infoHeaderContent: @Composable (Modifier, onExpandedChange: (Boolean) -> Unit) -> Unit,
    relatedContent: @Composable (Modifier) -> Unit,
    commentsContent: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bvid = success.info.bvid
    val pages = LargeScreenDetailRailPage.entries
    val pagerState = rememberPagerState(
        initialPage = DEFAULT_LARGE_SCREEN_DETAIL_RAIL_PAGE.ordinal,
        pageCount = { pages.size },
    )
    val page = pages[pagerState.currentPage]
    // Each new video opens on the default page; the pager itself survives recreation.
    var pagerBvid by rememberSaveable { mutableStateOf(bvid) }
    LaunchedEffect(bvid) {
        if (pagerBvid != bvid) {
            pagerBvid = bvid
            pagerState.scrollToPage(DEFAULT_LARGE_SCREEN_DETAIL_RAIL_PAGE.ordinal)
        }
    }
    var showCommentSearch by remember(bvid) { mutableStateOf(false) }
    var pendingVideoShare by remember { mutableStateOf<VideoSharePayload?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A reply thread only opens from the comments page, but keep it visible if it opens elsewhere.
    LaunchedEffect(subReplyVisible) {
        if (subReplyVisible) {
            animatePagerSelection(pagerState, LargeScreenDetailRailPage.COMMENTS.ordinal)
        }
    }
    val relatedStateHolder = rememberSaveableStateHolder()
    var infoExpanded by remember(bvid) { mutableStateOf(false) }
    // The header's details close when it leaves; don't hold the page for them on the way back.
    LaunchedEffect(page) {
        if (page != LargeScreenDetailRailPage.INFO) infoExpanded = false
    }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Row(modifier = modifier) {
        LargeScreenDetailRail(
            page = page,
            onPageChange = { target ->
                scope.launch { animatePagerSelection(pagerState, target.ordinal) }
            },
            pagePositionProvider = {
                pagerState.currentPage + pagerState.currentPageOffsetFraction
            },
            isPageScrollInProgress = { pagerState.isScrollInProgress },
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
        HorizontalPager(
            state = pagerState,
            key = { index -> "${bvid}_${pages[index].name}" },
            userScrollEnabled = false,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalPriorityHorizontalPagerSwipe(state = pagerState, enabled = true),
        ) { index ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (pages[index]) {
                    LargeScreenDetailRailPage.INFO -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        val headerMaxHeight =
                            maxHeight * LARGE_SCREEN_DETAIL_RAIL_HEADER_MAX_HEIGHT_FRACTION
                        Column(modifier = Modifier.fillMaxSize()) {
                            infoHeaderContent(
                                if (infoExpanded) {
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                } else {
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = headerMaxHeight)
                                },
                            ) { expanded -> infoExpanded = expanded }
                            if (!infoExpanded) {
                                HorizontalDivider(color = dividerColor)
                                relatedStateHolder.SaveableStateProvider("${bvid}_related") {
                                    relatedContent(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    )
                                }
                            }
                        }
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
    /** The pager's position in pages, so the toggle follows a swipe. */
    pagePositionProvider: () -> Float,
    isPageScrollInProgress: () -> Boolean,
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
            indicatorPositionProvider = pagePositionProvider,
            isScrollInProgressProvider = isPageScrollInProgress,
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
