package h.Hchat.ui.miuix

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import h.Hchat.hooks.items.subscribe.RemoveSubscribeAdsFeature
import h.Hchat.preferences.ConfigStore
import h.Hchat.ui.FeatureSettingsProvider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 「去除公众号信息流广告」的设置页。
 *
 * 广告卡是 Flutter 渲染的，拦截发生在数据下发层，页面上没有可点的东西，
 * 所以这里只保留开关。
 */
@Composable
internal fun RemoveSubscribeAdsMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember(context, provider.featureId()) {
        ConfigStore(context).getFeaturePrefs(provider.featureId())
    }

    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "功能开关") }
            item {
                SettingsCard {
                    SwitchRow(
                        sp,
                        RemoveSubscribeAdsFeature.KEY_ENABLE,
                        "去除公众号信息流广告",
                        "订阅号消息列表中的推广内容不再显示，关闭后恢复原生表现。",
                        RemoveSubscribeAdsFeature.DEFAULT_ENABLE
                    )
                }
            }
        }
    }
}
