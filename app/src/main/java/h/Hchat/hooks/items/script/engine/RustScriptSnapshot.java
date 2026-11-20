package h.Hchat.hooks.items.script.engine;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class RustScriptSnapshot {
    public static final int MAX_AST_BYTES = 16 * 1024 * 1024;
    private static final byte[] MAGIC = {'B', 'S', 'H', 'S'};
    private static final int VERSION = 2;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int HEADER_BYTES = 12 + NONCE_BYTES;
    public static final int MAX_SNAPSHOT_BYTES = HEADER_BYTES + MAX_AST_BYTES + TAG_BYTES;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RustScriptSnapshot() {}

    public static byte[] encode(byte[] ast, SecretKey key) throws IOException {
        Objects.requireNonNull(ast, "ast");
        if (ast.length == 0 || ast.length > MAX_AST_BYTES) {
            throw new IOException("Rust 快照 AST 为空或超过大小限制");
        }
        checkKey(key);
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] header = ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC).put((byte) VERSION).put((byte) 1)
                .put((byte) NONCE_BYTES).put((byte) 0)
                .putInt(ast.length + TAG_BYTES).put(nonce).array();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(header);
            byte[] ciphertext = cipher.doFinal(ast);
            return ByteBuffer.allocate(header.length + ciphertext.length)
                    .put(header).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IOException("Rust 快照加密失败", e);
        }
    }

    public static byte[] decode(byte[] snapshot, SecretKey key) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        checkVersion(snapshot);
        if (snapshot.length < HEADER_BYTES + TAG_BYTES + 1 || snapshot.length > MAX_SNAPSHOT_BYTES) {
            throw new IOException("Rust 快照不完整或超过大小限制");
        }
        byte[] data = snapshot.clone();
        ByteBuffer header = ByteBuffer.wrap(data);
        header.position(5);
        if (header.get() != 1 || header.get() != NONCE_BYTES || header.get() != 0) {
            throw new IOException("Rust 快照加密参数不受支持");
        }
        int length = header.getInt();
        if (length <= TAG_BYTES || length > MAX_AST_BYTES + TAG_BYTES
                || length != data.length - HEADER_BYTES) {
            throw new IOException("Rust 快照长度无效");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        header.get(nonce);
        checkKey(key);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(data, 0, HEADER_BYTES);
            // 认证成功前不把任何 AST 字节交给执行器。
            return cipher.doFinal(data, HEADER_BYTES, length);
        } catch (AEADBadTagException e) {
            throw new IOException("Rust 快照认证失败：密钥错误或文件已被篡改", e);
        } catch (GeneralSecurityException e) {
            throw new IOException("Rust 快照解密失败：请检查 AES 密钥", e);
        }
    }

    public static byte[] read(InputStream input, SecretKey key) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] prefix = new byte[5];
        for (int i = 0; i < prefix.length; i++) {
            int value = input.read();
            if (value < 0) throw new IOException("Rust 快照头不完整");
            prefix[i] = (byte) value;
        }
        checkVersion(prefix);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(prefix);
        copyBounded(input, output, MAX_SNAPSHOT_BYTES - prefix.length);
        return decode(output.toByteArray(), key);
    }

    public static void writeAtomic(Path output, byte[] ast, SecretKey key) throws IOException {
        byte[] encrypted = encode(ast, key);
        Path target = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(target.getParent(), ".hchat-rust-", ".snapshot");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary.toFile())) {
                stream.write(encrypted);
                stream.getFD().sync();
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copyBounded(input, output, maximum);
        return output.toByteArray();
    }

    private static void copyBounded(InputStream input, ByteArrayOutputStream output, int maximum)
            throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        for (;;) {
            int count = input.read(buffer, 0, Math.min(buffer.length, maximum - total + 1));
            if (count < 0) return;
            if (count == 0) {
                int value = input.read();
                if (value < 0) return;
                buffer[0] = (byte) value;
                count = 1;
            }
            if (count > maximum - total) throw new IOException("脚本或快照超过大小限制");
            output.write(buffer, 0, count);
            total += count;
        }
    }

    private static void checkVersion(byte[] data) throws IOException {
        if (data.length < 5 || !Arrays.equals(Arrays.copyOf(data, MAGIC.length), MAGIC)) {
            throw new IOException("Rust 快照头无效");
        }
        int version = data[4] & 0xff;
        if (version == 1) {
            throw new IOException("不支持旧 BSHS v1 Java AST 快照，请使用原脚本重新 compileSnapshot");
        }
        if (version != VERSION) throw new IOException("不支持的 Rust 快照版本：" + version);
    }

    private static void checkKey(SecretKey key) throws IOException {
        if (key == null || !"AES".equalsIgnoreCase(key.getAlgorithm())) {
            throw new IOException("Rust 快照需要 AES SecretKey");
        }
    }
}
