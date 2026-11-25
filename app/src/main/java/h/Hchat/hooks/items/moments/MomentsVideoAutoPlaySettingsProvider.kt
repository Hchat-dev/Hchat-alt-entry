package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MomentsVideoAutoPlaySettingsProvider : SimpleFeatureSettingsProvider(
    MomentsVideoAutoPlayFeature.ID,
    "禁止朋友圈视频自动播放",
    "朋友圈信息流中的视频只在点击后播放，不再进入页面就静音自动播放",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
