'use strict';
const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
let JSDOM;
try {
    ({ JSDOM } = require('jsdom'));
} catch (error) {
    ({ JSDOM } = require(path.resolve(__dirname, '../../../build/article-ad-filter-tools/node_modules/jsdom')));
}
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/article_ad_filter.js'), 'utf8');
const key = '__HCHAT_' + 'ARTICLE_AD_FILTER_V1__';
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
function fixture(t, html = '', url = 'https://mp.weixin.qq.com/s?private=query') {
    const dom = new JSDOM('<!doctype html><html><body>' + html + '</body></html>', {
        url, runScripts: 'outside-only'
    });
    t.after(() => dom.window.close());
    const w = dom.window;
    const NativeObserver = w.MutationObserver;
    const active = new Set();
    const options = [];
    let created = 0;
    w.MutationObserver = class extends NativeObserver {
        constructor(callback) { super(callback); created++; }
        observe(target, config) { super.observe(target, config); active.add(this); options.push(config); }
        disconnect() { super.disconnect(); active.delete(this); }
    };
    const nativeSetTimeout = w.setTimeout.bind(w);
    const nativeClearTimeout = w.clearTimeout.bind(w);
    const timers = new Set();
    w.setTimeout = function (callback, delay) {
        const id = nativeSetTimeout(() => { timers.delete(id); callback(); }, delay);
        timers.add(id);
        return id;
    };
    w.clearTimeout = function (id) { timers.delete(id); nativeClearTimeout(id); };
    return {
        w, active, timers, options,
        created: () => created,
        inject(enabled = true, nonce = 'page-1', diagnostics = false, paused = false) {
            return JSON.parse(w.eval(source.replace('__HCHAT_ARTICLE_CONFIG__',
                JSON.stringify({ enabled, nonce, diagnostics, paused }))));
        }
    };
}

test('严格限定 http(s)、精确主机和文章路径', t => {
    for (const url of [
        'https://mp.weixin.qq.com/s', 'http://mp.weixin.qq.com/s/abc',
        'https://mp.weixin.qq.com/s/?x=1'
    ]) {
        const f = fixture(t, '<div id="js_bottom_ad_area"></div>', url);
        assert.equal(f.inject().hiddenTotal, 1, url);
        f.w[key].stop();
    }
    for (const url of [
        'https://mp.weixin.qq.com.evil.example/s', 'https://evilmp.weixin.qq.com/s',
        'https://mp.weixin.qq.com/home', 'https://mp.weixin.qq.com/something',
        'https://mp.weixin.qq.com/S', 'https://example.com/s',
        'ftp://mp.weixin.qq.com/s', 'file:///s', 'https://mp.weixin.qq.com/',
        'https://user@mp.weixin.qq.com/s/key', 'https://mp.weixin.qq.com:8443/s/key'
    ]) {
        const f = fixture(t, '<div id="js_bottom_ad_area"></div>', url);
        assert.equal(f.inject().status, 'unsupported', url);
        assert.equal(f.created(), 0);
        assert.equal(f.w.document.querySelector('div').style.display, '');
    }
});

test('保留正文、正常评论、推荐、作者推广和包含广告字样的内容', t => {
    const f = fixture(t, '<article id="js_content">正文广告与作者推广</article>' +
        '<section id="js_cmt_area"><p>评论：广告</p></section>' +
        '<div id="recommend">推荐</div><div id="reader_advice" class="ad advertisement">广告</div>');
    assert.equal(f.inject().status, 'no-match');
    for (const node of f.w.document.body.querySelectorAll('*')) assert.equal(node.style.display, '');
    assert.equal(f.w.document.body.textContent, '正文广告与作者推广评论：广告推荐广告');
});

test('白名单分类计数、隐藏和精确恢复原 display 与 important', t => {
    const ids = ['js_top_ad_area', 'js_mid_ad_area', 'js_ad_area', 'js_bottom_ad_area', 'js_comment_ad_area', 'js_sponsor_ad_area'];
    const f = fixture(t, ids.map((id, i) => '<div id="' + id + '" style="color:red;' +
        (i === 0 ? 'display:inline-flex!important' : i === 1 ? 'display:block' : '') + '"><span>内容</span></div>').join(''));
    const original = ids.map(id => {
        const s = f.w.document.getElementById(id).style;
        return [s.getPropertyValue('display'), s.getPropertyPriority('display')];
    });
    const result = f.inject();
    assert.equal(result.hiddenTotal, 6);
    assert.deepEqual(result.counts, { header: 1, body: 2, footer: 1, comments: 1, sponsor: 1, modal: 0, tips: 0 });
    assert.equal(result.matchedIds.length, 6);
    for (const id of ids) {
        const s = f.w.document.getElementById(id).style;
        assert.equal(s.display, 'none');
        assert.equal(s.getPropertyPriority('display'), 'important');
    }
    const disabled = f.inject(false);
    assert.equal(disabled.status, 'disabled');
    assert.equal(disabled.hiddenTotal, 6);
    ids.forEach((id, i) => {
        const node = f.w.document.getElementById(id);
        assert.deepEqual([node.style.display, node.style.getPropertyPriority('display')], original[i]);
        assert.equal(node.style.color, 'red');
        assert.equal(node.children.length, 1);
    });
    assert.equal(f.w.document.querySelectorAll('style').length, 0);
});

test('异常广告容器若包裹正文或整块评论则跳过', t => {
    const f = fixture(t, '<div id="js_ad_area"><article id="js_content">正文</article></div>' +
        '<div id="js_comment_ad_area"><section id="js_cmt_area">评论</section></div>');
    assert.equal(f.inject().hiddenTotal, 0);
    assert.equal(f.w.document.getElementById('js_ad_area').style.display, '');
    assert.equal(f.w.document.getElementById('js_comment_ad_area').style.display, '');
});

test('微信 8.0.80 真机结构：文中、评论、关键词弹窗、角标均被处理且不动正文', t => {
    const f = fixture(t,
        '<div id="js_top_ad_area" class="top_banner"><span>顶部推广</span></div>' +
        '<article id="js_content"><p>正文第一段</p>' +
        '<div class="iframe_ad_container iframe_adv_ad_container"><iframe></iframe></div>' +
        '<p>正文第二段</p></article>' +
        '<section id="js_cmt_area"><p>正常评论</p></section>' +
        '<div class="comment-ad-container comment-ad-visible"><span>评论广告</span></div>' +
        '<div class="wx_bottom_modal_wrp ad_keyword_bottom_modal_wrap"><span>关键词广告</span></div>' +
        '<div class="ad_control-tips">广告</div>');
    const result = f.inject();
    assert.equal(result.hiddenTotal, 5);
    assert.deepEqual(result.counts, { header: 1, body: 1, footer: 0, comments: 1, sponsor: 0, modal: 1, tips: 1 });
    assert.deepEqual(result.matchedIds.sort(), [
        '.ad_control-tips', '.ad_keyword_bottom_modal_wrap', '.comment-ad-container',
        '.iframe_ad_container', '#js_top_ad_area'
    ].sort());
    for (const selector of ['.iframe_ad_container', '.comment-ad-container',
        '.ad_keyword_bottom_modal_wrap', '.ad_control-tips', '#js_top_ad_area']) {
        assert.equal(f.w.document.querySelector(selector).style.display, 'none', selector);
    }
    // 正文与正常评论不受影响，同元素叠加的 class 只计一次。
    assert.equal(f.w.document.getElementById('js_content').style.display, '');
    assert.equal(f.w.document.getElementById('js_cmt_area').style.display, '');
    assert.equal(f.w.document.querySelector('article').textContent, '正文第一段正文第二段');
    assert.equal(f.inject(false).status, 'disabled');
    for (const selector of ['.iframe_ad_container', '.comment-ad-container', '.ad_control-tips']) {
        assert.equal(f.w.document.querySelector(selector).style.display, '', selector);
    }
    assert.equal(f.w.document.body.textContent,
        '顶部推广正文第一段正文第二段正常评论评论广告关键词广告广告');
});

test('广告容器若包裹正文或整块评论，class 规则同样跳过', t => {
    const f = fixture(t, '<div class="iframe_ad_container"><article id="js_content">正文</article></div>' +
        '<div class="comment-ad-container"><section id="js_cmt_area">评论</section></div>');
    assert.equal(f.inject().hiddenTotal, 0);
    assert.equal(f.w.document.querySelector('.iframe_ad_container').style.display, '');
    assert.equal(f.w.document.querySelector('.comment-ad-container').style.display, '');
});

test('不监听 class 变化，滚动改样式不会额外唤醒 CPU', async t => {
    const f = fixture(t, '<div class="placeholder"><span>正文</span></div>');
    f.inject();
    const node = f.w.document.querySelector('.placeholder');
    node.className = 'iframe_ad_container iframe_adv_ad_container';
    await wait(250);
    assert.equal(node.style.display, '');
    assert.equal(f.timers.size, 0);
    assert.deepEqual(Object.keys(f.options[0]).sort(), ['childList', 'subtree']);
    assert.ok(!('attributeFilter' in f.options[0]));
    assert.ok(!('attributes' in f.options[0]));
});

test('暂停后停止扫描并保留隐藏，回到页面重新监听', async t => {
    const f = fixture(t, '<div class="iframe_ad_container"><iframe></iframe></div>');
    assert.equal(f.inject().hiddenTotal, 1);
    const node = f.w.document.querySelector('.iframe_ad_container');
    const paused = f.inject(true, 'page-1', false, true);
    assert.equal(paused.status, 'paused');
    assert.equal(f.active.size, 0);
    assert.equal(f.timers.size, 0);
    assert.equal(node.style.display, 'none');
    // 暂停期间新增广告不处理，也不排程
    const late = f.w.document.createElement('div');
    late.className = 'comment-ad-container';
    f.w.document.body.appendChild(late);
    await wait(250);
    assert.equal(late.style.display, '');
    assert.equal(f.timers.size, 0);
    // 恢复后重新监听并补处理
    const resumed = f.inject();
    assert.equal(resumed.status, 'active');
    assert.equal(f.active.size, 1);
    assert.equal(node.style.display, 'none');
    await wait(250);
    assert.equal(late.style.display, 'none');
    assert.equal(f.w[key].snapshot().counts.comments, 1);
});

test('pagehide 暂停而非恢复，pageshow 自动续上', async t => {
    const f = fixture(t, '<div id="js_ad_area"></div>');
    f.inject();
    const node = f.w.document.getElementById('js_ad_area');
    assert.equal(node.style.display, 'none');
    f.w.dispatchEvent(new f.w.Event('pagehide'));
    assert.equal(f.active.size, 0);
    assert.equal(f.timers.size, 0);
    assert.equal(node.style.display, 'none');
    assert.equal(f.w[key].snapshot().status, 'paused');
    f.w.dispatchEvent(new f.w.Event('pageshow'));
    assert.equal(f.active.size, 1);
    assert.equal(node.style.display, 'none');
    assert.equal(f.w[key].snapshot().status, 'active');
    assert.equal(f.w[key].stop().hiddenTotal, 1);
    assert.equal(node.style.display, '');
});

test('页面重建后脚本可从零重新计数，不会被旧基准吞掉', async t => {
    const f = fixture(t, '<div id="js_ad_area"></div>');
    f.inject();
    assert.equal(f.w[key].snapshot().hiddenTotal, 1);
    // 模拟同一 URL 重新加载：新脚本实例从 0 开始
    const fresh = f.inject(true, 'page-1');
    assert.equal(fresh.hiddenTotal, 1);
    assert.equal(f.created(), 1);
});

test('动态插入按 200ms 限频处理，重复注入不叠加 Observer 或计数', async t => {
    const f = fixture(t);
    f.inject();
    const first = f.w[key];
    for (let i = 0; i < 8; i++) f.inject();
    assert.equal(f.w[key], first);
    assert.equal(f.created(), 1);
    assert.equal(f.active.size, 1);
    assert.deepEqual(Object.keys(f.options[0]).sort(), ['childList', 'subtree']);
    const node = f.w.document.createElement('div');
    node.id = 'js_comment_ad_area';
    f.w.document.body.appendChild(node);
    await wait(30);
    assert.equal(node.style.display, '');
    assert.equal(f.timers.size, 1);
    await wait(220);
    assert.equal(node.style.display, 'none');
    assert.equal(first.snapshot().hiddenTotal, 1);
    assert.equal(f.timers.size, 0);
    assert.equal(f.inject().hiddenTotal, 1);
    assert.equal(f.created(), 1);
});

test('禁用取消待执行任务并恢复；重新启用保持当页累计', async t => {
    const f = fixture(t, '<div id="js_bottom_ad_area" style="display:grid!important"></div>');
    f.inject();
    f.w.document.body.appendChild(f.w.document.createElement('span'));
    await wait(0);
    assert.equal(f.timers.size, 1);
    f.inject(false);
    assert.equal(f.active.size, 0);
    assert.equal(f.timers.size, 0);
    const node = f.w.document.getElementById('js_bottom_ad_area');
    assert.equal(node.style.display, 'grid');
    assert.equal(node.style.getPropertyPriority('display'), 'important');
    const late = f.w.document.createElement('div');
    late.id = 'js_mid_ad_area';
    f.w.document.body.appendChild(late);
    await wait(230);
    assert.equal(late.style.display, '');
    assert.equal(f.inject().hiddenTotal, 2);
    assert.equal(f.active.size, 1);
    f.w[key].stop();
    assert.equal(node.style.display, 'grid');
});

test('nonce 变化清理旧状态与定时器并重置累计', async t => {
    const f = fixture(t, '<div id="js_ad_area" style="display:flex"></div>');
    f.inject();
    const previous = f.w[key];
    f.w.document.body.appendChild(f.w.document.createElement('span'));
    await wait(0);
    const result = f.inject(false, 'page-2');
    assert.notEqual(f.w[key], previous);
    assert.equal(previous.snapshot().status, 'stopped');
    assert.equal(result.nonce, 'page-2');
    assert.equal(result.hiddenTotal, 0);
    assert.equal(f.active.size, 0);
    assert.equal(f.timers.size, 0);
    assert.equal(f.w.document.getElementById('js_ad_area').style.display, 'flex');
});

test('stop 幂等恢复且快照为深拷贝', async t => {
    const f = fixture(t, '<div id="js_ad_area"></div>');
    f.inject();
    f.w.document.body.appendChild(f.w.document.createElement('span'));
    await wait(0);
    assert.equal(f.timers.size, 1);
    assert.equal(f.w[key].stop().hiddenTotal, 1);
    assert.equal(f.timers.size, 0);
    assert.equal(f.active.size, 0);
    assert.equal(f.w.document.getElementById('js_ad_area').style.display, '');
    assert.equal(f.w[key].snapshot().status, 'stopped');
    const copy = f.w[key].snapshot();
    copy.counts.body = 999;
    assert.equal(f.w[key].snapshot().counts.body, 1);
});

test('同窗口离开文章路径后再次注入会释放原状态', t => {
    const f = fixture(t, '<div id="js_ad_area" style="display:flex"></div>');
    f.inject();
    f.w.history.replaceState({}, '', '/home');
    assert.equal(f.inject().status, 'unsupported');
    assert.equal(f.active.size, 0);
    assert.equal(f.w.document.getElementById('js_ad_area').style.display, 'flex');
});

test('DOM 查询异常被捕获且正文保留', t => {
    const f = fixture(t, '<article id="js_content">正常正文</article>');
    f.w.document.querySelectorAll = function () { throw new Error('模拟异常'); };
    assert.equal(f.inject().status, 'error');
    assert.equal(f.w.document.querySelector('article').textContent, '正常正文');
    assert.equal(f.w.document.querySelector('article').style.display, '');
    f.w[key].stop();
    assert.equal(f.active.size, 0);
});

test('主动刷新补查延后设置 ID 的容器且不新增观察器', t => {
    const f = fixture(t, '<div id="placeholder"></div>');
    f.inject();
    const node = f.w.document.getElementById('placeholder');
    node.id = 'js_comment_ad_area';
    assert.equal(node.style.display, '');
    assert.equal(f.inject().hiddenTotal, 1);
    assert.equal(node.style.display, 'none');
    assert.equal(f.created(), 1);
});

test('诊断仅返回有限模板属性，未知候选不会被隐藏', t => {
    const f = fixture(t, '<article id="js_content">secret-正文</article>' +
        Array.from({ length: 20 }, (_, i) => '<div id="new_comment_ad_' + i + '" class="ad-slot">secret-comment</div>').join(''));
    assert.equal(f.inject().hiddenTotal, 0);
    assert.equal(f.inject().candidates, undefined);
    const details = f.inject(true, 'page-1', true);
    assert.equal(details.candidates.length, 8);
    assert.equal(details.candidates[0].id, 'new_comment_ad_0');
    assert.equal(f.w.document.getElementById('new_comment_ad_0').style.display, '');
    assert.ok(!JSON.stringify(details).includes('secret-'));
});

test('移出的广告恢复原样并可重新插入处理', async t => {
    const f = fixture(t, '<div id="js_ad_area" style="display:flex"></div>');
    f.inject();
    const node = f.w.document.getElementById('js_ad_area');
    node.remove();
    await wait(250);
    assert.equal(node.style.display, 'flex');
    f.w.document.body.appendChild(node);
    await wait(250);
    assert.equal(node.style.display, 'none');
    assert.equal(f.w[key].snapshot().hiddenTotal, 1);
    f.inject(false);
    assert.equal(node.style.display, 'flex');
});

test('快照不包含文章、账号、Cookie 或 URL', t => {
    const f = fixture(t, '<div id="js_ad_area">绝不输出正文-secret</div>');
    f.w.document.cookie = 'account=private-secret';
    f.inject();
    const result = f.w[key].snapshot();
    assert.ok(result.matchedIds.length <= 16);
    const json = JSON.stringify(result);
    assert.ok(!json.includes('private-secret'));
    assert.ok(!json.includes('绝不输出正文-secret'));
    assert.ok(!json.includes('private=query'));
    assert.ok(!json.includes('https://'));
});
