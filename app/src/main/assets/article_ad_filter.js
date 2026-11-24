(function () {
    'use strict';
    var config = __HCHAT_ARTICLE_CONFIG__;
    var key = '__HCHAT_' + 'ARTICLE_AD_FILTER_V1__';
    var version = '1.2.0';
    var nonce = typeof config.nonce === 'string' ? config.nonce : '';
    // id 规则用 getElementById 定位，单项 O(1)，不遍历页面。
    var idRules = [
        ['js_top_ad_area', 'header'],
        ['js_mid_ad_area', 'body'],
        ['js_ad_area', 'body'],
        ['js_bottom_ad_area', 'footer'],
        ['js_comment_ad_area', 'comments'],
        ['js_sponsor_ad_area', 'sponsor']
    ];
    // class 规则来自微信 8.0.80 真机容器标识，合并成一次选择器查询。
    var classRules = [
        ['iframe_ad_container', 'body'],
        ['iframe_adv_ad_container', 'body'],
        ['comment-ad-container', 'comments'],
        ['comment-ad-visible', 'comments'],
        ['ad_keyword_bottom_modal_wrap', 'modal'],
        ['ad_control-tips', 'tips']
    ];
    var classSelector = classRules.map(function (rule) { return '.' + rule[0]; }).join(',');
    // 单页广告容器实测不超过 20 个，上限留足余量即可，避免异常页面长期占用内存。
    var MAX_RECORDS = 64;
    var SCAN_INTERVAL = 200;
    function emptySnapshot(status) {
        return {
            status: status, version: version, nonce: nonce,
            hiddenTotal: 0,
            counts: { header: 0, body: 0, footer: 0, comments: 0, sponsor: 0, modal: 0, tips: 0 },
            matchedIds: [], ruleSet: 'wechat-8.0.80-observed-classes'
        };
    }
    var previous = window[key];
    var address;
    try { address = new URL(location.href); } catch (error) { address = {}; }
    var allowed = (address.protocol === 'https:' || address.protocol === 'http:') &&
        address.hostname === 'mp.weixin.qq.com' &&
        !address.username && !address.password &&
        (!address.port || (address.protocol === 'https:' && address.port === '443') ||
            (address.protocol === 'http:' && address.port === '80')) &&
        (address.pathname === '/s' || address.pathname.indexOf('/s/') === 0);
    if (!allowed) {
        if (previous && typeof previous.stop === 'function') previous.stop();
        return JSON.stringify(emptySnapshot('unsupported'));
    }
    if (previous && previous.version === version && previous.nonce === nonce) {
        previous.update(config.enabled === true, config.paused === true);
        return JSON.stringify(previous.snapshot(config.diagnostics === true));
    }
    if (previous && typeof previous.stop === 'function') previous.stop();

    var data = emptySnapshot('disabled');
    var records = [];
    var seen = new WeakSet();
    var observer = null;
    var timer = null;
    var active = false;
    var listening = false;
    var lastScan = 0;
    var wantEnabled = false;
    var wantPaused = false;
    function snapshot(diagnostics) {
        var result = JSON.parse(JSON.stringify(data));
        if (diagnostics === true) {
            result.candidates = [];
            try {
                // 只在原生主动取诊断快照时查看模板属性；不参与隐藏或事件扫描。
                var nodes = document.querySelectorAll('[id],[class]');
                var token = /(^|[\s_-])(ad|ads|advert|advertisement|sponsor)([\s_-]|$)/i;
                for (var n = 0; n < nodes.length && n < 800 && result.candidates.length < 8; n++) {
                    var id = nodes[n].id || '';
                    var cls = typeof nodes[n].className === 'string' ? nodes[n].className : '';
                    if (!token.test(id + ' ' + cls)) continue;
                    result.candidates.push({ id: id.slice(0, 64), className: cls.slice(0, 96) });
                }
            } catch (error) {
                result.diagnosticError = true;
            }
        }
        return result;
    }
    function reveal(record) {
        if (record.display === '') record.node.style.removeProperty('display');
        else record.node.style.setProperty('display', record.display, record.priority);
    }
    function removeRecord(index) {
        try { reveal(records[index]); } catch (error) { data.status = 'error'; }
        records.splice(index, 1);
    }
    function restore() {
        for (var i = records.length - 1; i >= 0; i--) {
            try { reveal(records[i]); } catch (error) { data.status = 'error'; }
        }
        records = [];
    }
    function detach() {
        if (observer) observer.disconnect();
        observer = null;
        if (timer !== null) window.clearTimeout(timer);
        timer = null;
        active = false;
    }
    function unlisten() {
        if (!listening) return;
        window.removeEventListener('pagehide', onPageHide);
        window.removeEventListener('pageshow', onPageShow);
        listening = false;
    }
    function stop(status) {
        detach();
        unlisten();
        data.status = status === 'disabled' ? 'disabled' : 'stopped';
        restore();
        return snapshot();
    }
    // 切到后台时只停扫描、保留已隐藏状态，返回页面不必重新闪烁一次。
    function pause() {
        detach();
        data.status = 'paused';
        return snapshot();
    }
    function onPageHide() {
        if (active) pause();
    }
    function onPageShow() {
        if (wantEnabled && !active) update(true, wantPaused);
    }
    // 精确按 class token 判断所属区域，避免 "xxx_ad" 之类子串误判。
    function classify(node) {
        var name = typeof node.className === 'string' ? node.className : '';
        if (name) {
            var padded = ' ' + name.replace(/\s+/g, ' ') + ' ';
            for (var i = 0; i < classRules.length; i++) {
                if (padded.indexOf(' ' + classRules[i][0] + ' ') >= 0) {
                    return { region: classRules[i][1], label: '.' + classRules[i][0] };
                }
            }
        }
        return { region: 'body', label: '' };
    }
    function hide(node, region, label) {
        if (!node || !node.style) return;
        // 广告占位不能包裹正常正文或整块评论，异常模板直接跳过。
        if (node.querySelector('#js_content, #js_cmt_area')) return;
        if (records.length >= MAX_RECORDS) return;
        var record = null;
        for (var i = 0; i < records.length; i++) {
            if (records[i].node === node) { record = records[i]; break; }
        }
        if (!record) {
            record = {
                node: node,
                display: node.style.getPropertyValue('display'),
                priority: node.style.getPropertyPriority('display')
            };
            records.push(record);
        }
        node.style.setProperty('display', 'none', 'important');
        if (!seen.has(node)) {
            seen.add(node);
            data.hiddenTotal++;
            data.counts[region] = (data.counts[region] || 0) + 1;
            if (label && data.matchedIds.indexOf(label) < 0 && data.matchedIds.length < 16) {
                data.matchedIds.push(label);
            }
        }
    }
    function scan() {
        timer = null;
        if (!active) return;
        lastScan = Date.now();
        data.status = 'scanning';
        try {
            for (var i = records.length - 1; i >= 0; i--) {
                if (!document.documentElement.contains(records[i].node)) removeRecord(i);
            }
            for (var index = 0; index < idRules.length; index++) {
                var byId = document.getElementById(idRules[index][0]);
                if (byId) hide(byId, idRules[index][1], '#' + idRules[index][0]);
            }
            var found = null;
            try {
                found = document.querySelectorAll(classSelector);
            } catch (error) {
                data.status = 'error';
            }
            if (found) {
                var limit = Math.min(found.length, MAX_RECORDS);
                for (var n = 0; n < limit; n++) {
                    var info = classify(found[n]);
                    hide(found[n], info.region, info.label);
                }
            }
            if (data.status !== 'error') data.status = data.hiddenTotal ? 'active' : 'no-match';
        } catch (error) {
            data.status = 'error';
        }
    }
    function schedule() {
        if (!active || timer !== null) return;
        timer = window.setTimeout(scan, Math.max(0, SCAN_INTERVAL - (Date.now() - lastScan)));
    }
    function listen() {
        if (observer) return;
        observer = new MutationObserver(schedule);
        // 只监听节点插入：广告位是异步插入的，不做全树 class/style 监听以免滚动时反复唤醒。
        observer.observe(document, { childList: true, subtree: true });
        ensureLifecycle();
    }
    function ensureLifecycle() {
        if (listening) return;
        window.addEventListener('pagehide', onPageHide);
        window.addEventListener('pageshow', onPageShow);
        listening = true;
    }
    function update(enabled, paused) {
        wantEnabled = enabled === true;
        wantPaused = paused === true;
        if (!wantEnabled) return stop('disabled');
        if (wantPaused) {
            // 后台加载时也保留 pageshow 监听，回到前台能自行续上。
            ensureLifecycle();
            return pause();
        }
        if (active) {
            if (timer !== null) window.clearTimeout(timer);
            timer = null;
            scan();
            return snapshot();
        }
        active = true;
        try {
            listen();
            scan();
        } catch (error) {
            stop();
            data.status = 'error';
        }
        return snapshot();
    }
    window[key] = {
        version: version, nonce: nonce, snapshot: snapshot,
        stop: stop, pause: pause, update: update
    };
    update(config.enabled === true, config.paused === true);
    return JSON.stringify(snapshot(config.diagnostics === true));
}());
