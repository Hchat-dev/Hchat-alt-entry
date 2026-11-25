package h.Hchat.utils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 同一宿主数据目录内共享采集状态；文件锁同时覆盖进程和 ClassLoader。 */
public final class LogCaptureStore {
    private static final int MAGIC = 0x484C4731;
    private static final int HEADER_SIZE = 21;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_LINE_BYTES = 16 * 1024;
    private final File file;

    public LogCaptureStore(File directory) {
        file = new File(directory, "runtime-log.v1");
    }

    public static final class Snapshot {
        public final boolean enabled;
        public final long revision;
        public final long generation;
        public final List<String> lines;

        Snapshot(boolean enabled, long revision, long generation, List<String> lines) {
            this.enabled = enabled;
            this.revision = revision;
            this.generation = generation;
            this.lines = lines;
        }
    }

    /** 无写入时只检查固定长度文件头，不读取或解码历史记录。 */
    public synchronized boolean hasChanged(long revision) throws IOException {
        if (!file.exists()) return true;
        try (RandomAccessFile data = new RandomAccessFile(file, "r")) {
            try (FileLock lock = data.getChannel().tryLock(0, Long.MAX_VALUE, true)) {
                if (lock == null) throw new IOException("日志文件正由另一进程使用");
                if (data.length() < HEADER_SIZE || data.readInt() != MAGIC) return true;
                data.readBoolean();
                return data.readLong() != revision;
            } catch (OverlappingFileLockException busy) {
                throw new IOException("日志文件正由另一 ClassLoader 使用", busy);
            }
        }
    }
    public synchronized Snapshot update(Boolean enabled, boolean clear, List<String> pending,
                                         long expectedGeneration, boolean initialEnabled) throws IOException {
        try (RandomAccessFile data = new RandomAccessFile(file, "rw")) {
            FileLock lock;
            try {
                lock = data.getChannel().tryLock();
            } catch (OverlappingFileLockException busy) {
                throw new IOException("日志文件正由另一 ClassLoader 使用", busy);
            }
            if (lock == null) throw new IOException("日志文件正由另一进程使用");
            try {
                boolean active = initialEnabled;
                long revision = 0;
                long generation = 0;
                List<String> lines = new ArrayList<>();
                if (data.length() > 0) {
                    if (data.length() < HEADER_SIZE || data.readInt() != MAGIC) {
                        if (!clear) throw new IOException("日志文件头无效；可通过清空日志恢复");
                        // 仅在用户明确清空时重建损坏的文件头，隔离此前排队的旧记录。
                        generation = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
                    } else {
                        active = data.readBoolean();
                        revision = data.readLong();
                        generation = data.readLong();
                    }
                    while (!clear && data.getFilePointer() < data.length()) {
                        int length = data.readInt();
                        if (length < 0 || length > MAX_LINE_BYTES || length > data.length() - data.getFilePointer()) {
                            throw new IOException("日志记录长度无效");
                        }
                        byte[] bytes = new byte[length];
                        data.readFully(bytes);
                        lines.add(new String(bytes, StandardCharsets.UTF_8));
                    }
                }
                boolean changed = data.length() == 0;
                // 关闭后仍允许同一代次的已采集尾部提交；重新开启/清空才隔离旧队列。
                boolean acceptPending = !clear && expectedGeneration == generation;
                if (clear) {
                    lines.clear();
                    generation++;
                    changed = true;
                }
                if (acceptPending && !pending.isEmpty()) {
                    for (String line : pending) {
                        String bounded = line.length() > 4000 ? line.substring(0, 4000) + "…" : line;
                        lines.add(bounded);
                    }
                    changed = true;
                }
                if (enabled != null && active != enabled) {
                    active = enabled;
                    if (active) generation++;
                    changed = true;
                }
                int first = Math.max(0, lines.size() - 2000);
                int bytes = HEADER_SIZE;
                for (int i = lines.size() - 1; i >= first; i--) {
                    bytes += 4 + lines.get(i).getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > MAX_BYTES) { first = i + 1; break; }
                }
                if (first > 0) lines = new ArrayList<>(lines.subList(first, lines.size()));
                if (changed) {
                    revision++;
                    data.seek(0);
                    data.writeInt(MAGIC);
                    data.writeBoolean(active);
                    data.writeLong(revision);
                    data.writeLong(generation);
                    for (String line : lines) {
                        byte[] encoded = line.getBytes(StandardCharsets.UTF_8);
                        data.writeInt(encoded.length);
                        data.write(encoded);
                    }
                    data.setLength(data.getFilePointer());
                }
                return new Snapshot(active, revision, generation, lines);
            } finally {
                lock.release();
            }
        }
    }
}
