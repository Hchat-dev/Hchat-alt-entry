package h.Hchat.hooks.items.script.engine;

import android.content.Context;
import h.Hchat.loader.utils.NativeLibraryLoader;
import me.yun.bsh.rust.BshEngine;

public final class RustScriptLibrary {
    private RustScriptLibrary() {}

    public static void prepare(Context context) {
        Context app = context.getApplicationContext();
        Context owner = app != null ? app : context;
        BshEngine.setLibraryLoader(() -> new NativeLibraryLoader().loadRustBsh(
                owner, RustScriptLibrary.class.getClassLoader()));
    }
}
