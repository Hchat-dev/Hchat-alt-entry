package h.Hchat;

import h.Hchat.utils.LogCaptureStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.Collections;

import static org.junit.Assert.*;

/** 关闭、清空和并发文件锁的回归用例，不依赖 Android 或手机。 */
public class LogCaptureStoreReviewTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void closingCaptureKeepsAlreadyQueuedTail() throws Exception {
        LogCaptureStore store = new LogCaptureStore(temporary.newFolder());
        LogCaptureStore.Snapshot opened = store.update(true, false,
                Collections.emptyList(), -1, false);
        LogCaptureStore.Snapshot closed = store.update(false, false,
                Collections.singletonList("queued-before-close"), opened.generation, false);
        assertFalse(closed.enabled);
        assertEquals(Collections.singletonList("queued-before-close"), closed.lines);
    }

    @Test public void clearCannotRestoreThePendingBatchItJustCleared() throws Exception {
        LogCaptureStore store = new LogCaptureStore(temporary.newFolder());
        LogCaptureStore.Snapshot opened = store.update(true, false,
                Collections.emptyList(), -1, false);
        LogCaptureStore.Snapshot cleared = store.update(null, true,
                Collections.singletonList("discard-on-clear"), opened.generation, false);
        assertTrue(cleared.lines.isEmpty());
        LogCaptureStore.Snapshot staleWriter = store.update(null, false,
                Collections.singletonList("old-process-pending"), opened.generation, false);
        assertTrue(staleWriter.lines.isEmpty());
        assertEquals(cleared.generation, staleWriter.generation);
    }

    @Test public void explicitClearRepairsAnInvalidHeader() throws Exception {
        File directory = temporary.newFolder();
        try (RandomAccessFile file = new RandomAccessFile(new File(directory, "runtime-log.v1"), "rw")) {
            file.writeByte(1);
        }
        LogCaptureStore store = new LogCaptureStore(directory);
        try {
            store.update(null, false, Collections.emptyList(), -1, true);
            fail("Corrupt logs must report an error until the user requests clearing");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        LogCaptureStore.Snapshot repaired = store.update(true, true,
                Collections.singletonList("old-pending"), -1, true);
        assertTrue(repaired.lines.isEmpty());
        assertTrue(repaired.generation > 0);
        assertEquals(Collections.singletonList("after-repair"), store.update(null, false,
                Collections.singletonList("after-repair"), repaired.generation, true).lines);
    }

    @Test public void explicitClearRepairsATruncatedRecord() throws Exception {
        File directory = temporary.newFolder();
        LogCaptureStore store = new LogCaptureStore(directory);
        LogCaptureStore.Snapshot initial = store.update(true, false,
                Collections.emptyList(), -1, true);
        try (RandomAccessFile file = new RandomAccessFile(new File(directory, "runtime-log.v1"), "rw")) {
            file.seek(file.length());
            file.writeInt(Integer.MAX_VALUE);
        }
        LogCaptureStore.Snapshot repaired = store.update(null, true,
                Collections.emptyList(), initial.generation, true);
        assertTrue(repaired.enabled);
        assertTrue(repaired.lines.isEmpty());
        assertTrue(repaired.generation > initial.generation);
    }

    @Test public void temporaryLockContentionAllowsTheSameRequestToRetry() throws Exception {
        File directory = temporary.newFolder();
        LogCaptureStore store = new LogCaptureStore(directory);
        LogCaptureStore.Snapshot initial = store.update(null, false,
                Collections.emptyList(), -1, false);
        try (RandomAccessFile file = new RandomAccessFile(new File(directory, "runtime-log.v1"), "rw");
             FileLock held = file.getChannel().lock()) {
            try {
                store.update(true, false, Collections.emptyList(), initial.generation, false);
                fail("A concurrent file lock must be reported instead of pretending success");
            } catch (IOException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        LogCaptureStore.Snapshot retried = store.update(true, false,
                Collections.emptyList(), initial.generation, false);
        assertTrue(retried.enabled);
    }
}
