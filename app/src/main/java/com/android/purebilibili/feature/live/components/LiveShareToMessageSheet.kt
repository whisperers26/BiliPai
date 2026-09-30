package com.android.purebilibili.feature.live.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.data.model.response.SessionItem
import com.android.purebilibili.data.repository.MessageRepository
import com.android.purebilibili.feature.dynamic.components.DynamicShareSessionRow
import com.android.purebilibili.feature.dynamic.components.resolveDynamicShareSessionPresentation
import com.android.purebilibili.feature.message.InboxUserInfoResolver
import com.android.purebilibili.feature.message.MessageUserInfoLoader
import com.android.purebilibili.feature.message.UserBasicInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveShareToMessageSheet(
    roomId: Long,
    roomTitle: String,
    onDismiss: () -> Unit,
    onResult: (Boolean, String) -> Unit,
) {
    var sessions by remember(roomId) { mutableStateOf<List<SessionItem>>(emptyList()) }
    var loading by remember(roomId) { mutableStateOf(true) }
    var loadError by remember(roomId) { mutableStateOf<String?>(null) }
    var sendingTo by remember(roomId) { mutableStateOf<Long?>(null) }
    var userInfoMap by remember(roomId) { mutableStateOf<Map<Long, UserBasicInfo>>(emptyMap()) }
    var resolvingMids by remember(roomId) { mutableStateOf<Set<Long>>(emptySet()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(roomId) {
        MessageRepository.getSessions(size = 30).fold(
            onSuccess = { response ->
                sessions = response.session_list.orEmpty().filter {
                    it.session_type == 1 && it.talker_id > 0L
                }
                loading = false
            },
            onFailure = {
                loadError = it.message ?: "加载会话失败"
                loading = false
            },
        )
        userInfoMap = sessions.mapNotNull { session ->
            val account = session.account_info ?: return@mapNotNull null
            session.talker_id to UserBasicInfo(
                mid = session.talker_id,
                name = account.name,
                face = account.avatarUrl,
            )
        }.toMap()
        val missing = sessions.filter { session ->
            InboxUserInfoResolver.shouldFetchSessionUserInfo(session, userInfoMap)
        }.map(SessionItem::talker_id).distinct()
        resolvingMids = missing.toSet()
        missing.chunked(12).forEach { batch ->
            val fetched = coroutineScope {
                batch.map { mid -> async { mid to MessageUserInfoLoader.fetch(mid) } }.awaitAll()
            }
            userInfoMap = userInfoMap + fetched.mapNotNull { (mid, info) -> info?.let { mid to it } }
            resolvingMids = resolvingMids - batch.toSet()
        }
    }

    AppModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = AppSpacingTokens.Large, vertical = AppSpacingTokens.Small),
            verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small),
        ) {
            AppText("分享至消息", style = MaterialTheme.typography.headlineSmall)
            AppText("选择联系人发送直播间链接", style = MaterialTheme.typography.bodySmall)
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.ExtraSmall),
            ) {
                when {
                    loading -> AppText("正在加载最近联系人…")
                    loadError != null -> AppText(loadError.orEmpty())
                    sessions.isEmpty() -> AppText("暂无可发送的最近联系人")
                    else -> sessions.forEach { session ->
                        val presentation = resolveDynamicShareSessionPresentation(
                            session = session,
                            userInfo = userInfoMap[session.talker_id],
                            resolvingUserInfo = session.talker_id in resolvingMids,
                        )
                        DynamicShareSessionRow(
                            presentation = presentation,
                            sending = sendingTo == session.talker_id,
                            enabled = sendingTo == null,
                            onClick = {
                                sendingTo = session.talker_id
                                scope.launch {
                                    MessageRepository.sendTextMessage(
                                        receiverId = session.talker_id,
                                        content = "${roomTitle.ifBlank { "直播间" }}\nhttps://live.bilibili.com/$roomId",
                                    ).fold(
                                        onSuccess = {
                                            onResult(true, "已分享给${presentation.name}")
                                            onDismiss()
                                        },
                                        onFailure = {
                                            sendingTo = null
                                            onResult(false, it.message ?: "分享失败")
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
            AppButton(onClick = onDismiss, enabled = sendingTo == null, modifier = Modifier.fillMaxWidth()) {
                AppText("取消")
            }
        }
    }
}
