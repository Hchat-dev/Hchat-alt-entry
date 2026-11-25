package h.Hchat.ui.miuix

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import h.Hchat.hooks.items.articleads.RemoveArticleAdsFeature
import h.Hchat.preferences.ConfigStore
import h.Hchat.ui.FeatureSettingsProvider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle

@Composable
internal fun RemoveArticleAdsMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val prefs = remember(context) { ConfigStore(context).getFeaturePrefs(RemoveArticleAdsFeature.ID) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(RemoveArticleAdsFeature.KEY_ENABLE, false)) }
    val scroll = MiuixScrollBehavior()
    PageScaffold(
        title = provider.title(), largeTitle = provider.title(), scrollBehavior = scroll,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "功能开关") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "去除公众号文章内广告",
                        summary = "公众号文章中的文中、文末及评论广告不再显示；默认关闭",
                        onCheckedChange = { value ->
                            enabled = value
                            prefs.edit().putBoolean(RemoveArticleAdsFeature.KEY_ENABLE, value).apply()
                            RemoveArticleAdsFeature.refresh()
                        }
                    )
                }
            }
        }
    }
}
