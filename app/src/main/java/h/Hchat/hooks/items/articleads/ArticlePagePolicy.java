package h.Hchat.hooks.items.articleads;

import java.net.URI;
import java.util.Locale;

/** 只允许公众号文章路径，避免把网页处理带到登录、支付或其他站点。 */
public final class ArticlePagePolicy {
    private ArticlePagePolicy() {}

    public static boolean isArticleUrl(String value) {
        if (value == null || value.length() > 16384) return false;
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) return false;
            if (uri.getRawUserInfo() != null || uri.getHost() == null) return false;
            if (!"mp.weixin.qq.com".equals(uri.getHost().toLowerCase(Locale.ROOT))) return false;
            int port = uri.getPort();
            if (port != -1 && !("https".equals(scheme) && port == 443)
                    && !("http".equals(scheme) && port == 80)) return false;
            String path = uri.getRawPath();
            return "/s".equals(path) || (path != null && path.startsWith("/s/"));
        } catch (Exception invalid) {
            return false;
        }
    }
}
