package h.Hchat.utils;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import de.robv.android.xposed.XposedBridge;

public final class LogCaptureRegression {
    private static int checks;
    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }
    private static List<String> one(String line) { return Collections.singletonList(line); }
    private static boolean contains(List<String> lines, String value) {
        return lines.stream().anyMatch(line -> line.contains(value));
    }
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("hchat-capture-store-").toFile();
        LogCaptureStore store = new LogCaptureStore(directory);
        LogCaptureStore.Snapshot start = store.update(true, false, Collections.emptyList(), -1, false);
        check(!store.hasChanged(start.revision), "版本不变不应全量读取");
        LogCaptureStore.Snapshot closed = store.update(false, false, one("关闭前尾部"), start.generation, false);
        check(!closed.enabled && contains(closed.lines, "关闭前尾部"), "关闭必须保留本进程尾部");
        LogCaptureStore.Snapshot remote = store.update(null, false, one("其他进程尾部"), start.generation, false);
        check(contains(remote.lines, "其他进程尾部"), "关闭后同代次尾部仍应提交");
        check(store.hasChanged(start.revision), "变更必须能被固定文件头检查发现");
        LogCaptureStore.Snapshot reopened = store.update(true, false, Collections.emptyList(), remote.generation, false);
        LogCaptureStore.Snapshot stale = store.update(null, false, one("过期尾部"), remote.generation, false);
        check(!contains(stale.lines, "过期尾部"), "重新开启后必须隔离旧代次");
        LogCaptureStore.Snapshot cleared = store.update(null, true, one("清空后新日志"), reopened.generation, false);
        check(cleared.lines.equals(one("清空后新日志")), "清空不能丢失清空后已接收的日志");
        try (RandomAccessFile data = new RandomAccessFile(new File(directory, "runtime-log.v1"), "rw");
             FileLock lock = data.getChannel().lock()) {
            boolean busy = false;
            try { store.update(false, false, Collections.emptyList(), cleared.generation, false); }
            catch (java.io.IOException expected) { busy = true; }
            check(busy, "锁争用应返回可重试错误");
        }
        check(store.update(false, false, one("重试尾部"), cleared.generation, false).lines.contains("重试尾部"),
                "锁释放后重试应保留尾部");

        Field storeField = HLog.class.getDeclaredField("store");
        storeField.setAccessible(true);
        storeField.set(null, store);
        HLog.setCaptureEnabled(true);
        HLog.flushCapture();
        try (RandomAccessFile data = new RandomAccessFile(new File(directory, "runtime-log.v1"), "rw");
             FileLock lock = data.getChannel().lock()) {
            HLog.e("争用中的关闭尾部");
            HLog.setCaptureEnabled(false);
            HLog.flushCapture();
            check(!HLog.isCaptureEnabled(), "争用时本地关闭必须立即生效");
            check(!HLog.captureError().isEmpty(), "锁争用必须可见");
        }
        HLog.flushCapture();
        LogCaptureStore.Snapshot persisted = store.update(null, false, Collections.emptyList(), -1, false);
        check(!persisted.enabled, "争用后关闭意图必须重试成功");
        check(contains(persisted.lines, "争用中的关闭尾部"), "重试不能丢关闭尾部");
        check(contains(persisted.lines, "日志采集已关闭"), "关闭自检标记也必须持久化");
        HLog.setCaptureEnabled(true);
        HLog.flushCapture();
        XposedBridge.fail = true;
        HLog.e("日志桥异常仍采集", new IllegalStateException("异常原因"));
        HLog.setStackTraceEnabled(true);
        HLog.e("堆栈日志桥异常仍采集", new IllegalStateException("堆栈原因"));
        HLog.flushCapture();
        HLog.CaptureSnapshot snapshot = HLog.captureSnapshotState();
        check(contains(snapshot.lines, "日志桥异常仍采集"), "桥异常不能中断宿主或内存采集");
        check(contains(snapshot.lines, "堆栈日志桥异常仍采集"), "堆栈模式也必须隔离桥异常");
        long revision = snapshot.revision;
        HLog.e("快照之后的记录");
        check(snapshot.revision == revision && !contains(snapshot.lines, "快照之后的记录"), "快照必须保持独立");
        check(HLog.captureSnapshotState().revision > revision, "后续追加必须有新版本");
        try (RandomAccessFile data = new RandomAccessFile(new File(directory, "runtime-log.v1"), "rw");
             FileLock lock = data.getChannel().lock()) {
            HLog.clearCapture();
            HLog.e("清空争用后的新尾部");
            HLog.flushCapture();
        }
        HLog.flushCapture();
        List<String> finalLines = store.update(null, false, Collections.emptyList(), -1, false).lines;
        check(!contains(finalLines, "日志桥异常仍采集") && contains(finalLines, "清空争用后的新尾部"),
                "争用后的清空应重试且保留新尾部");
        System.out.println("采集存储及 HLog 异常隔离回归通过，断言=" + checks);
        System.out.println("测试隔离目录：" + directory);
    }
}