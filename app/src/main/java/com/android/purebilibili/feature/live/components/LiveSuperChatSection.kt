package com.android.purebilibili.feature.live.components

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppDropdownMenu
import com.android.purebilibili.core.ui.components.AppDropdownMenuItem
import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.android.purebilibili.feature.live.formatLiveSuperChatCountdown
import com.android.purebilibili.feature.live.rememberLiveChromePalette
import com.android.purebilibili.feature.live.resolveLiveSuperChatColor
import com.android.purebilibili.feature.live.resolveLiveSuperChatDurationSec
import com.android.purebilibili.feature.live.shouldExpireLiveSuperChat
import kotlinx.coroutines.delay

@Composable
fun LiveSuperChatSection(
    items: List<LiveDanmakuItem>,
    modifier: Modifier = Modifier,
    onExpired: (LiveDanmakuItem) -> Unit = {},
    onUserClick: (Long) -> Unit = {},
    onReport: (LiveDanmakuItem) -> Unit = {},
) {
    val palette = rememberLiveChromePalette()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(AppSpacingTokens.Large),
        verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.Medium)
    ) {
        items(
            items = items,
            key = { "${it.superChatId}_${it.uid}_${it.text}_${it.superChatPrice}" }
        ) { item ->
            LiveSuperChatCard(
                item = item,
                onExpired = { onExpired(item) },
                onUserClick = { onUserClick(item.uid) },
                onReport = { onReport(item) },
            )
        }
        if (items.isEmpty()) {
            item(key = "empty") {
                AppText(
                    text = "暂无醒目留言",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = AppSpacingTokens.Large),
                )
            }
        }
    }
}

@Composable
private fun LiveSuperChatCard(
    item: LiveDanmakuItem,
    onExpired: () -> Unit,
    onUserClick: () -> Unit,
    onReport: () -> Unit,
) {
    val palette = rememberLiveChromePalette()
    val context = LocalContext.current
    var menuExpanded by remember(item.superChatId) { mutableStateOf(false) }
    val totalSec = resolveLiveSuperChatDurationSec(item.superChatDuration)
    var remainingSec by remember(item.superChatId, item.superChatDuration, item.text) {
        mutableIntStateOf(totalSec)
    }

    LaunchedEffect(item.superChatId, totalSec) {
        remainingSec = totalSec
        var elapsed = 0
        while (!shouldExpireLiveSuperChat(totalSec, elapsed)) {
            delay(1_000)
            elapsed += 1
            remainingSec = (totalSec - elapsed).coerceAtLeast(0)
        }
        onExpired()
    }

    Box {
      AppSurface(
        shape = AppShapes.borderedContainer(ContainerLevel.Card),
        color = palette.surfaceElevated,
        border = androidx.compose.foundation.BorderStroke(
            AppSpacingTokens.Micro / 2,
            palette.border
        ),
        modifier = Modifier.clickable(onClickLabel = "醒目留言操作", onClick = { menuExpanded = true }),
      ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    resolveLiveSuperChatColor(item.superChatBackgroundColor).copy(alpha = 0.12f)
                )
                .padding(AppSpacingTokens.Large),
            verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AppText(
                    text = item.uname.ifBlank { "醒目留言" },
                    color = palette.primaryText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small)) {
                    AppText(
                        text = formatLiveSuperChatCountdown(remainingSec),
                        color = palette.secondaryText,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    AppText(
                        text = item.superChatPrice.ifBlank { "SC" },
                        color = palette.accentStrong,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            AppText(
                text = item.text,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge
            )
        }
      }
      AppDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
          AppDropdownMenuItem(
              text = { AppText("访问：${item.uname.ifBlank { "醒目留言用户" }}") },
              enabled = item.uid > 0L,
              onClick = { menuExpanded = false; onUserClick() },
          )
          AppDropdownMenuItem(
              text = { AppText("复制 SC 信息") },
              onClick = {
                  menuExpanded = false
                  val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                  clipboard.setPrimaryClip(
                      ClipData.newPlainText(
                          "SC 信息",
                          "${item.uname} (${item.uid}) · ¥${item.superChatPrice}\n${item.text}\nSC ID: ${item.superChatId}",
                      ),
                  )
                  Toast.makeText(context, "已复制 SC 信息", Toast.LENGTH_SHORT).show()
              },
          )
          AppDropdownMenuItem(
              text = { AppText("举报") },
              onClick = { menuExpanded = false; onReport() },
          )
      }
    }
}
