package bshrs;

import com.android.dx.cf.direct.DirectClassFile;
import com.android.dx.cf.direct.StdAttributeFactory;
import com.android.dx.command.dexer.DxContext;
import com.android.dx.dex.DexOptions;
import com.android.dx.dex.cf.CfOptions;
import com.android.dx.dex.cf.CfTranslator;
import com.android.dx.dex.file.DexFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import dalvik.system.InMemoryDexClassLoader;

final class AndroidClassDefiner {
    private AndroidClassDefiner() {}

    static Class<?> define(String name, byte[] bytes, ClassLoader parent) {
        try {
            byte[] dex = convertClassToDex(name, bytes);
            ClassLoader loader = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), parent);
            Class<?> defined = loader.loadClass(name);
            if (defined.getClassLoader() != loader) {
                throw new LinkageError("Script class shadows an existing class: " + name);
            }
            return defined;
        } catch (IOException | ClassNotFoundException error) {
            throw new IllegalStateException("Cannot define Android script class: " + name, error);
        }
    }

    private static byte[] convertClassToDex(String name, byte[] bytes) throws IOException {
        DexOptions dexOptions = new DexOptions();
        dexOptions.minSdkVersion = 26;
        CfOptions cfOptions = new CfOptions();
        DxContext dxContext = new DxContext();
        DirectClassFile classFile = new DirectClassFile(bytes, name.replace('.', '/') + ".class", true);
        classFile.setAttributeFactory(StdAttributeFactory.THE_ONE);
        DexFile dexFile = new DexFile(dexOptions);
        dexFile.add(CfTranslator.translate(dxContext, classFile, bytes, cfOptions, dexOptions, dexFile));
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            dexFile.writeTo(output, null, false);
            return output.toByteArray();
        }
    }
}
