package h.Hchat.hooks.items.messageforward

object MessageForwardSettings {
    const val PREFS_NAME = "Hchat_message_forward_config"
    const val KEY_ENABLE = "message_forward_enable"
    const val KEY_MULTI_MOMENTS_ENABLE = "message_forward_multi_moments_enable"
    const val KEY_FAVORITE_FORWARD_ENABLE = "message_forward_favorite_enable"
    const val KEY_SNS_FORWARD_ENABLE = "message_forward_sns_enable"

    /** 解除相册「一次最多 9 张图」的上限。 */
    const val KEY_GALLERY_LIMIT = "message_forward_gallery_limit"

    /** 群发助手改用微信原生好友选择页（选完直接发，不进原生编辑页）。 */
    const val KEY_NATIVE_MASS_PICKER = "message_forward_native_mass_picker"

    /** 转发文字消息时直接走微信群发助手，跳过通道选择。 */
    const val KEY_TEXT_NATIVE_MASS = "message_forward_text_native_mass"

    const val DEFAULT_ENABLE = false
}
