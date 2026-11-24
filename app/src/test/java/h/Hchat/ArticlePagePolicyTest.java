package h.Hchat;

import h.Hchat.hooks.items.articleads.ArticlePagePolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class ArticlePagePolicyTest {
    @Test public void acceptsArticleLinksAndLegacyQueryForm() {
        assertTrue(ArticlePagePolicy.isArticleUrl("https://mp.weixin.qq.com/s/article-key"));
        assertTrue(ArticlePagePolicy.isArticleUrl("https://mp.weixin.qq.com/s?__biz=example&mid=1#comment"));
        assertTrue(ArticlePagePolicy.isArticleUrl("https://MP.WEIXIN.QQ.COM:443/s/key"));
    }
    @Test public void rejectsLookalikeHostsAndUserInfo() {
        assertFalse(ArticlePagePolicy.isArticleUrl("https://mp.weixin.qq.com.example.org/s/key"));
        assertFalse(ArticlePagePolicy.isArticleUrl("https://example.org/s?next=https://mp.weixin.qq.com/s/key"));
        assertFalse(ArticlePagePolicy.isArticleUrl("https://user@mp.weixin.qq.com/s/key"));
    }
    @Test public void rejectsNonArticlePages() {
        assertFalse(ArticlePagePolicy.isArticleUrl("https://mp.weixin.qq.com/mp/profile_ext"));
        assertFalse(ArticlePagePolicy.isArticleUrl("https://mp.weixin.qq.com/something"));
        assertFalse(ArticlePagePolicy.isArticleUrl("https://mp.weixin.qq.com/"));
    }
    @Test public void rejectsOtherSchemesAndNonDefaultPorts() {
        assertFalse(ArticlePagePolicy.isArticleUrl("javascript:alert(1)"));
        assertFalse(ArticlePagePolicy.isArticleUrl("file:///s"));
        assertFalse(ArticlePagePolicy.isArticleUrl("https://mp.weixin.qq.com:8443/s/key"));
    }
    @Test public void handlesMissingAndMalformedValues() {
        assertFalse(ArticlePagePolicy.isArticleUrl(null));
        assertFalse(ArticlePagePolicy.isArticleUrl(""));
        assertFalse(ArticlePagePolicy.isArticleUrl("https://mp.weixin.qq.com/s/%broken"));
        assertFalse(ArticlePagePolicy.isArticleUrl("https://mp.weixin.qq.com/%73/key"));
    }
}
