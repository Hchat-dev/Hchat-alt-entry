package h.Hchat.utils;

import android.os.SystemClock;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 冷启动耗时诊断。
 *
 * 为什么需要它：用户反馈"每次重新打开微信都卡、掉帧"，但开发环境没有设备连接，
 * 无法用 dumpsys 抓数据。这个计时器把启动链路各阶段的耗时直接写进 HLog 缓冲，
 * 用户开启"记录运行日志"后复制出来，就能看到瓶颈在哪一段，不用再靠猜。
 *
 * 设计约束：
 * - 只在诊断开关打开时工作，关闭时每次调用就是一次 volatile 布尔判断；
 * - 主线程 stage 只记时间点，不写日志（写日志的 IPC 本身的开销会污染测量）；
 * - 所有阶段记完后由后台线程一次性输出，避免测量行为本身造成卡顿。
 */
public final class StartupTimer {
    private StartupTimer() {}

    /** 与 HLog 共用采集开关：开了"记录运行日志"才计量。 */
    private static volatile boolean enabled = false;

    private static final Map<String, Long> MARKS = new LinkedHashMap<>();
    private static long processStartRealtime;

    /** 最近一次生成的报告全文，供设置页直接展示。这是进程内静态字段，重启即失效。 */
    private static volatile String lastReport = "";

    public static String lastReport() {
        return lastReport;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (value) {
            synchronized (MARKS) {
                MARKS.clear();
                processStartRealtime = SystemClock.elapsedRealtime();
            }
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 记录一个阶段完成的时间点。线程安全，开销是一次锁 + Map 写。 */
    public static void mark(String stage) {
        if (!enabled) return;
        synchronized (MARKS) {
            MARKS.put(stage, SystemClock.elapsedRealtime());
        }
    }

    /**
     * 输出整段耗时。由后台线程调用。
     * 输出后清空，避免多次调用重复刷日志。
     */
    public static void dumpIfEnabled(String tag) {
        if (!enabled) return;
        final Map<String, Long> snapshot;
        final long start;
        synchronized (MARKS) {
            if (MARKS.isEmpty()) return;
            snapshot = new LinkedHashMap<>(MARKS);
            start = processStartRealtime;
            MARKS.clear();
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("===== 冷启动耗时诊断 =====");
        long prev = start;
        for (Map.Entry<String, Long> e : snapshot.entrySet()) {
            long t = e.getValue();
            sb.append('\n').append(e.getKey())
                    .append(" +").append(t - start).append("ms")
                    .append(" (本段 ").append(t - prev).append("ms)");
            prev = t;
        }
        lastReport = sb.toString();
        HLog.e(tag + " " + lastReport);
    }
}
