/*
 * Derived from LSPilot/BeanShell-Rust, licensed under Apache-2.0.
 * License: third_party/beanshell-rust/LICENSE
 * Modified for Hchat engine isolation and Android class definition.
 */
package bshrs;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Bridge {
    private Bridge() {}
    public static volatile boolean catchCallbackErrors = false;

    private static final java.util.concurrent.ConcurrentHashMap<Long, EngineContext> ENGINES =
            new java.util.concurrent.ConcurrentHashMap<Long, EngineContext>();
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<Frame>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, EngineContext> GENERATED_OWNERS =
            new java.util.concurrent.ConcurrentHashMap<Long, EngineContext>();

    private static final class EngineContext {
        final long id;
        volatile boolean closed;
        volatile java.util.function.BiConsumer<String, Throwable> callbackErrorHandler;
        final java.util.concurrent.locks.ReentrantReadWriteLock execution = new java.util.concurrent.locks.ReentrantReadWriteLock();
        ClassLoader[] loaders = new ClassLoader[0];
        ScriptLoader generated;
        final Map<String, Class<?>> generatedClasses = new HashMap<String, Class<?>>();
        final Map<Long, Throwable> errors = new HashMap<Long, Throwable>();
        final Map<Long, java.lang.ref.WeakReference<Object>> boxedObjects = new HashMap<>();

        EngineContext(long id) { this.id = id; }

        void checkOpen() {
            if (closed || ENGINES.get(id) != this) {
                throw new IllegalStateException("Script engine is closed: " + id);
            }
        }

        synchronized ClassLoader[] loaders() {
            checkOpen();
            return loaders;
        }

        synchronized void add(ClassLoader loader) {
            checkOpen();
            if (loader == null) return;
            for (ClassLoader existing : loaders) if (existing == loader) return;
            ClassLoader[] next = java.util.Arrays.copyOf(loaders, loaders.length + 1);
            next[loaders.length] = loader;
            loaders = next;
        }

        synchronized void close() {
            closed = true;
            loaders = new ClassLoader[0];
            generated = null;
            generatedClasses.clear();
            errors.clear();
            boxedObjects.clear();
            callbackErrorHandler = null;
        }
    }

    private static final class Frame {
        final EngineContext context;
        final Frame previous;
        Frame(EngineContext context, Frame previous) {
            this.context = context;
            this.previous = previous;
        }
    }

    public static void registerEngine(long engine) {
        EngineContext created = new EngineContext(engine);
        ENGINES.putIfAbsent(engine, created);
    }

    private static EngineContext engine(long id) {
        // Rust 的无归属类型探测只能借用当前引擎，不能创建全局上下文。
        if (id == 0 && CURRENT.get() != null) return current();
        EngineContext context = ENGINES.get(id);
        if (context == null) throw new IllegalStateException("Script engine is not registered or closed: " + id);
        context.checkOpen();
        return context;
    }

    private static EngineContext current() {
        Frame frame = CURRENT.get();
        if (frame == null) throw new IllegalStateException("No script engine context");
        frame.context.checkOpen();
        return frame.context;
    }

    public static void setCallbackErrorHandler(long id, java.util.function.BiConsumer<String, Throwable> handler) {
        engine(id).callbackErrorHandler = handler;
    }

    public static void addScriptLoader(long engine, ClassLoader loader) {
        engine(engine).add(loader);
    }

    public static void addScriptLoader(ClassLoader loader) {
        current().add(loader);
    }

    public static Long enter(long engine) {
        return enterContext(engine(engine));
    }

    private static Long enterContext(EngineContext context) {
        context.execution.readLock().lock();
        try { context.checkOpen(); }
        catch (RuntimeException error) { context.execution.readLock().unlock(); throw error; }
        Frame previous = CURRENT.get();
        CURRENT.set(new Frame(context, previous));
        return previous == null ? null : previous.context.id;
    }

    public static void exit(Long previous) {
        Frame frame = CURRENT.get();
        Frame restore = frame == null ? null : frame.previous;
        Long expected = restore == null ? null : restore.context.id;
        if (!java.util.Objects.equals(expected, previous)) {
            throw new IllegalStateException("Unbalanced script engine context");
        }
        // 恢复原帧，避免 ID 复用把已关闭的引擎换成新实例。
        if (restore == null) CURRENT.remove();
        else CURRENT.set(restore);
        if (frame != null) frame.context.execution.readLock().unlock();
    }

    public static Runnable lockForClose(long id) {
        EngineContext context = engine(id);
        if (isCurrent(id)) throw new IllegalStateException("不能在脚本回调中关闭自身引擎");
        context.execution.writeLock().lock();
        return new Runnable() {
            @Override public void run() { context.execution.writeLock().unlock(); }
        };
    }

    public static boolean isCurrent(long engine) {
        Frame frame = CURRENT.get();
        return frame != null && frame.context.id == engine;
    }

    public static void releaseEngine(long engine) {
        EngineContext context = ENGINES.remove(engine);
        if (context != null) {
            java.util.Iterator<Map.Entry<Long, EngineContext>> owners = GENERATED_OWNERS.entrySet().iterator();
            while (owners.hasNext()) {
                if (owners.next().getValue() == context) owners.remove();
            }
            context.close();
        }
    }

    public static String drainErrors() {
        EngineContext context = current();
        synchronized (context) {
            context.checkOpen();
            StringBuilder out = new StringBuilder();
            for (Throwable error : context.errors.values()) {
                if (out.length() > 0) out.append('\n');
                out.append("Exception in thread: ").append(describe(error));
            }
            context.errors.clear();
            return out.toString();
        }
    }

    static Class<?> resolve(String name) throws ClassNotFoundException {
        EngineContext context = current();
        switch (name) {
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "void": return void.class;
            default: break;
        }
        if (name.endsWith("[]")) {
            Class<?> base = resolve(name.substring(0, name.length() - 2));
            return Array.newInstance(base, 0).getClass();
        }
        synchronized (context) {
            context.checkOpen();
            Class<?> generated = context.generatedClasses.get(name);
            if (generated != null) return generated;
        }
        for (ClassLoader scriptLoader : context.loaders()) {
            Class<?> found = tryResolve(name, scriptLoader);
            if (found != null) {
                return found;
            }
        }
        Class<?> found = tryResolve(name, Bridge.class.getClassLoader());
        if (found != null) {
            return found;
        }
        return Class.forName(name);
    }
    private static Class<?> tryResolve(String name, ClassLoader loader) {
        String candidate = name;
        while (true) {
            try {
                return Class.forName(candidate, false, loader);
            } catch (ClassNotFoundException ignored) {
            } catch (LinkageError ignored) {
            }
            int separator = candidate.lastIndexOf('.');
            if (separator < 0) {
                return null;
            }
            candidate = candidate.substring(0, separator) + '$' + candidate.substring(separator + 1);
        }
    }

    static Object classFor(String name) throws ClassNotFoundException {
        return resolve(name);
    }

    static boolean classExists(String name) {
        try {
            resolve(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static String className(Object o) {
        return o.getClass().getName();
    }

    static Object box(String kind, String text) {
        switch (kind) {
            case "string": return text;
            case "null": return null;
            case "boolean": return Boolean.valueOf(Boolean.parseBoolean(text));
            case "char": return Character.valueOf(text.isEmpty() ? (char) 0 : text.charAt(0));
            case "byte": return Byte.valueOf(text);
            case "short": return Short.valueOf(text);
            case "int": return Integer.valueOf(text);
            case "long": return Long.valueOf(text);
            case "float": return Float.valueOf(text);
            case "double": return Double.valueOf(text);
            default: throw new IllegalArgumentException("unknown kind: " + kind);
        }
    }

    static int tag(Object o) {
        if (o == null) return 0;
        if (o instanceof Boolean) return 1;
        if (o instanceof Character) return 2;
        if (o instanceof Byte) return 3;
        if (o instanceof Short) return 4;
        if (o instanceof Integer) return 5;
        if (o instanceof Long) return 6;
        if (o instanceof Float) return 7;
        if (o instanceof Double) return 8;
        if (o instanceof String) return 9;
        return 10;
    }

    static boolean asBool(Object o) { return ((Boolean) o).booleanValue(); }
    static char asChar(Object o) { return ((Character) o).charValue(); }
    static long asLong(Object o) { return ((Number) o).longValue(); }
    static double asDouble(Object o) { return ((Number) o).doubleValue(); }
    static String asString(Object o) { return (String) o; }

    static Object newInstance(String cn, Object[] args) throws Throwable {
        Class<?> c = resolve(cn);
        if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) {
            throw new InstantiationException("Cannot instantiate " + cn);
        }
        Constructor<?> ctor = selectCtor(c, args);
        Object[] callArgs = normalizeVarargs(ctor.isVarArgs(), ctor.getParameterTypes(), args);
        try {
            return ctor.newInstance(callArgs);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static Object call(Object target, String name, Object[] args) throws Throwable {
        if (target == null) {
            throw new NullPointerException("Cannot invoke " + name + "() on null");
        }
        Class<?> c = target.getClass();
        Method m = selectMethod(c, name, args, false);
        if (m == null) {
            String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            m = selectMethod(c, "get" + cap, args, false);
            if (m == null) m = selectMethod(c, "is" + cap, args, false);
        }
        if (m == null) {
            throw new NoSuchMethodException(c.getName() + "." + name + "/" + args.length);
        }
        Object[] callArgs = normalizeVarargs(m.isVarArgs(), m.getParameterTypes(), args);
        try {
            return m.invoke(target, callArgs);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } catch (IllegalAccessException e) {
            Method viaInterface = interfaceMethod(c, name, args.length);
            if (viaInterface == null) throw e;
            try {
                return viaInterface.invoke(target, args);
            } catch (InvocationTargetException ite) {
                throw ite.getCause();
            }
        }
    }

    static Method interfaceMethod(Class<?> c, String name, int argc) {
        for (Class<?> iface : c.getInterfaces()) {
            Method m = publicMethod(iface, name, argc);
            if (m != null) return m;
            Method nested = interfaceMethod(iface, name, argc);
            if (nested != null) return nested;
        }
        if (c.getSuperclass() != null) {
            return interfaceMethod(c.getSuperclass(), name, argc);
        }
        return null;
    }

    static Method publicMethod(Class<?> c, String name, int argc) {
        if (!Modifier.isPublic(c.getModifiers())) return null;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argc
                    && Modifier.isPublic(m.getModifiers())) {
                return m;
            }
        }
        return null;
    }

    static Object callStatic(String cn, String name, Object[] args) throws Throwable {
        Class<?> c = resolve(cn);
        Method m = selectMethod(c, name, args, true);
        if (m == null) {
            throw new NoSuchMethodException(cn + "." + name + "/" + args.length);
        }
        Object[] callArgs = normalizeVarargs(m.isVarArgs(), m.getParameterTypes(), args);
        try {
            return m.invoke(null, callArgs);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static Object get(Object target, String name) throws Throwable {
        if (target == null) throw new NullPointerException("field " + name + " of null");
        return getFieldValue(target.getClass(), target, name);
    }

    static void set(Object target, String name, Object value) throws Throwable {
        if (target == null) throw new NullPointerException("field " + name + " of null");
        setFieldValue(target.getClass(), target, name, value);
    }

    static Object getStatic(String cn, String name) throws Throwable {
        Class<?> c = resolve(cn);
        Field f = findField(c, name);
        if (f != null) {
            f.setAccessible(true);
            return f.get(null);
        }
        Method m = selectMethod(c, name, new Object[0], true);
        if (m != null) {
            try { return m.invoke(null); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        }
        throw new NoSuchFieldException(cn + "." + name);
    }

    static void setStatic(String cn, String name, Object value) throws Throwable {
        Class<?> c = resolve(cn);
        Field f = findField(c, name);
        if (f == null) throw new NoSuchFieldException(cn + "." + name);
        f.setAccessible(true);
        f.set(null, value);
    }

    static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        try { return c.getField(name); }
        catch (NoSuchFieldException ignored) { return null; }
    }

    static Object getFieldValue(Class<?> c, Object target, String name) throws Throwable {
        Field f = findField(c, name);
        if (f != null) {
            f.setAccessible(true);
            return f.get(target);
        }
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method m = selectMethod(c, "get" + cap, new Object[0], false);
        if (m == null) m = selectMethod(c, "is" + cap, new Object[0], false);
        if (m == null) throw new NoSuchFieldException(c.getName() + "." + name);
        try { return m.invoke(target); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    static void setFieldValue(Class<?> c, Object target, String name, Object value) throws Throwable {
        Field f = findField(c, name);
        if (f != null) {
            f.setAccessible(true);
            f.set(target, value);
            return;
        }
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method m = selectMethod(c, "set" + cap, new Object[] { value }, false);
        if (m == null) throw new NoSuchFieldException(c.getName() + "." + name);
        try { m.invoke(target, value); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    static int length(Object array) {
        return Array.getLength(array);
    }

    static Object index(Object array, int i) {
        return Array.get(array, i);
    }

    static void setIndex(Object array, int i, Object value) {
        Array.set(array, i, value);
    }

    static Object newArray(String component, int length) throws Throwable {
        return Array.newInstance(resolve(component), length);
    }

    static boolean isInstance(Object o, String cn) {
        try {
            return resolve(cn).isInstance(o);
        } catch (Throwable t) {
            return false;
        }
    }

    static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getName() + (msg == null ? "" : ": " + msg);
    }

    private static Object invokeDefault(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            Method invokeDefault = InvocationHandler.class.getMethod(
                    "invokeDefault", Object.class, Method.class, Object[].class);
            return invokeDefault.invoke(null, proxy, method, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } catch (NoSuchMethodException e) {
            throw new UnsupportedOperationException(
                    "interface default methods require Android 13 (API 33)");
        }
    }

    static boolean isInterface(String name) {
        try {
            return resolve(name).isInterface();
        } catch (Throwable t) {
            return false;
        }
    }
    static long scriptHandle(Object value) {
        if (value == null || !Proxy.isProxyClass(value.getClass())) return 0;
        InvocationHandler handler = Proxy.getInvocationHandler(value);
        if (!(handler instanceof Handler)) return 0;
        Handler owned = (Handler) handler;
        if (!owned.boxed) return 0;
        owned.context.checkOpen();
        if (current() != owned.context) throw new IllegalArgumentException("脚本对象不属于当前插件");
        return owned.handle;
    }

    static Object boxedProxyEngine(long runtime, String[] interfaces, long handle) throws Throwable {
        EngineContext context = engine(runtime);
        synchronized (context) {
            context.checkOpen();
            java.lang.ref.WeakReference<Object> reference = context.boxedObjects.get(handle);
            Object value = reference == null ? null : reference.get();
            if (value == null) {
                Long previous = enter(runtime);
                try {
                    value = proxy(interfaces, handle);
                    ((Handler) Proxy.getInvocationHandler(value)).boxed = true;
                }
                finally { exit(previous); }
                context.boxedObjects.put(handle, new java.lang.ref.WeakReference<>(value));
            }
            return value;
        }
    }

    static long[] drainBoxedEngine(long runtime) {
        EngineContext context = engine(runtime);
        synchronized (context) {
            context.checkOpen();
            long[] removed = new long[context.boxedObjects.size()];
            int count = 0;
            java.util.Iterator<Map.Entry<Long, java.lang.ref.WeakReference<Object>>> entries = context.boxedObjects.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<Long, java.lang.ref.WeakReference<Object>> entry = entries.next();
                if (entry.getValue().get() == null) { removed[count++] = entry.getKey(); entries.remove(); }
            }
            return java.util.Arrays.copyOf(removed, count);
        }
    }

    static Object proxy(String[] ifaces, long handle) throws Throwable {
        Class<?>[] types = new Class<?>[ifaces.length];
        for (int i = 0; i < ifaces.length; i++) {
            types[i] = resolve(ifaces[i]);
        }
        ClassLoader loader = proxyLoader(types);
        return Proxy.newProxyInstance(loader, types, new Handler(handle));
    }
    private static ClassLoader proxyLoader(Class<?>[] types) {
        List<ClassLoader> candidates = new ArrayList<ClassLoader>();
        for (Class<?> type : types) {
            ClassLoader loader = type.getClassLoader();
            if (loader != null && !candidates.contains(loader)) {
                candidates.add(loader);
            }
        }
        if (candidates.isEmpty()) {
            return Bridge.class.getClassLoader();
        }
        outer:
        for (ClassLoader candidate : candidates) {
            for (Class<?> type : types) {
                try {
                    if (Class.forName(type.getName(), false, candidate) != type) {
                        continue outer;
                    }
                } catch (Throwable ignored) {
                    continue outer;
                }
            }
            return candidate;
        }
        return candidates.get(0);
    }

    static final class Handler implements InvocationHandler {
        private final long handle;
        private boolean boxed;
        private final java.lang.ref.WeakReference<Thread> owner;
        private final EngineContext context;

        Handler(long handle) {
            this.handle = handle;
            this.owner = new java.lang.ref.WeakReference<Thread>(Thread.currentThread());
            this.context = current();
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            boolean externalCallback = CURRENT.get() == null;
            Long previous = enterContext(context);
            try {
                String name = method.getName();
                int argc = args == null ? 0 : args.length;
                if (argc == 0 && "toString".equals(name)) return "bsh-proxy-" + handle;
                if (argc == 0 && "hashCode".equals(name)) return (int) handle;
                if (argc == 1 && "equals".equals(name)) return proxy == args[0];
                if (method.isDefault()) return invokeDefault(proxy, method, args);
                try {
                    return bshrs.Native.invoke(handle, name, args);
                } catch (Throwable error) {
                    java.util.function.BiConsumer<String, Throwable> handler = context.callbackErrorHandler;
                    if (externalCallback && method.getReturnType() == void.class
                            && error instanceof RuntimeException && handler != null) {
                        handler.accept(name, error);
                        return null;
                    }
                    if (Thread.currentThread() == owner.get() || !catchCallbackErrors) {
                        throw error;
                    }
                    synchronized (context) {
                        context.checkOpen();
                        if (context.errors.size() >= 64 && !context.errors.containsKey(handle)) {
                            throw error;
                        }
                        context.errors.put(handle, error);
                    }
                    return defaultValue(method.getReturnType());
                }
            } finally {
                exit(previous);
            }
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) return null;
            if (type == boolean.class) return false;
            if (type == char.class) return (char) 0;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            return 0d;
        }
    }
    static String[] methodParams(Object target, String className, String name, int argc, boolean isStatic)
            throws Throwable {
        Class<?> c = target == null ? resolve(className) : target.getClass();
        Method m = selectMethod(c, name, new Object[argc], isStatic);
        if (m == null) return null;
        return typeNames(m.getParameterTypes());
    }

    static String[] ctorParams(String className, int argc) throws Throwable {
        Class<?> c = resolve(className);
        Constructor<?> ctor = selectCtor(c, new Object[argc]);
        return typeNames(ctor.getParameterTypes());
    }

    static String[] typeNames(Class<?>[] types) {
        String[] out = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            out[i] = types[i].getTypeName();
        }
        return out;
    }
    public static native Object invoke(long handle, String method, Object[] args);

    public static Object dispatchGenerated(long handle, String method, Object[] args) {
        EngineContext context = GENERATED_OWNERS.get(handle);
        if (context == null) throw new IllegalStateException("生成类所属的 Rust 引擎已关闭");
        Long previous = enterContext(context);
        try { return Native.invoke(handle, method, args); }
        finally { exit(previous); }
    }

    static final class ScriptLoader extends ClassLoader {
        private final EngineContext context;

        ScriptLoader(EngineContext context) {
            super(Bridge.class.getClassLoader());
            this.context = context;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (context) {
                context.checkOpen();
                Class<?> found = context.generatedClasses.get(name);
                if (found != null) return found;
                found = findLoadedClass(name);
                if (found != null) return found;
            }
            try { return super.loadClass(name, resolve); }
            catch (ClassNotFoundException missing) {
                for (ClassLoader loader : context.loaders()) {
                    try { return loader.loadClass(name); }
                    catch (ClassNotFoundException ignored) { }
                }
                throw missing;
            }
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    static Class<?> defineScriptClass(String name, byte[] bytes) {
        EngineContext context = current();
        synchronized (context) {
            context.checkOpen();
            if (context.generatedClasses.containsKey(name)) {
                throw new LinkageError("Duplicate script class: " + name);
            }
            if (context.generated == null) context.generated = new ScriptLoader(context);
            String vm = System.getProperty("java.vm.name", "");
            Class<?> defined = "Dalvik".equals(vm) || "ART".equals(vm)
                    ? AndroidClassDefiner.define(name, bytes, context.generated)
                    : context.generated.define(name, bytes);
            context.generatedClasses.put(name, defined);
            return defined;
        }
    }

    static Object newScriptInstance(Class<?> c, long handle) throws Throwable {
        GENERATED_OWNERS.put(handle, current());
        Constructor<?> ctor = c.getDeclaredConstructor(long.class);
        ctor.setAccessible(true);
        try {
            return ctor.newInstance(handle);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
    static String overridableMethods(String className) throws Throwable {
        Class<?> c = resolve(className);
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<String>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isPrivate(mod) || Modifier.isFinal(mod)) continue;
                if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                String key = m.getName() + "/" + m.getParameterCount();
                if (!seen.add(key)) continue;
                sb.append(m.getName()).append('|').append(m.getReturnType().getTypeName());
                for (Class<?> p : m.getParameterTypes()) {
                    sb.append('|').append(p.getTypeName());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    static String typeName(Object o) {
        if (o == null) return "null";
        Class<?> c = o.getClass();
        if (c.isArray()) {
            return c.getComponentType().getTypeName() + "[]";
        }
        return c.getTypeName();
    }

    static Constructor<?> selectCtor(Class<?> c, Object[] args) throws NoSuchMethodException {
        Constructor<?> best = null;
        int bestScore = -1;
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            if (!matchesArity(ctor.isVarArgs(), ctor.getParameterCount(), args.length)) continue;
            int score = ctor.isVarArgs()
                    ? scoreVarargs(ctor.getParameterTypes(), args)
                    : score(ctor.getParameterTypes(), args);
            if (score > bestScore) {
                bestScore = score;
                best = ctor;
            }
        }
        if (best == null) {
            throw new NoSuchMethodException(c.getName() + "." + "<init>" + "/" + args.length);
        }
        try {
            best.setAccessible(true);
        } catch (Throwable ignored) {
        }
        return best;
    }

    static Method selectMethod(Class<?> c, String name, Object[] args, boolean staticOnly) {
        Method best = null;
        int bestScore = -1;
        List<Method> candidates = new ArrayList<Method>();
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (staticOnly && !Modifier.isStatic(m.getModifiers())) continue;
            if (!staticOnly && Modifier.isStatic(m.getModifiers()) && !m.getDeclaringClass().isInterface()) {
            }
            if (!matchesArity(m.isVarArgs(), m.getParameterCount(), args.length)) continue;
            candidates.add(m);
        }
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (staticOnly && !Modifier.isStatic(m.getModifiers())) continue;
                if (!matchesArity(m.isVarArgs(), m.getParameterCount(), args.length)) continue;
                candidates.add(m);
            }
        }
        for (Method m : candidates) {
            int score = m.isVarArgs()
                    ? scoreVarargs(m.getParameterTypes(), args)
                    : score(m.getParameterTypes(), args);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        if (best != null) {
            try {
                best.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    static Object[] normalizeVarargs(boolean varargs, Class<?>[] params, Object[] args) {
        if (!varargs || params.length == 0) return args;
        int fixed = params.length - 1;
        Class<?> arrayType = params[fixed];
        if (args.length == params.length && args[fixed] != null
                && arrayType.isInstance(args[fixed])) {
            return args;
        }
        if (args.length < fixed) return args;
        Class<?> component = arrayType.getComponentType();
        Object rest = Array.newInstance(component, args.length - fixed);
        for (int i = fixed; i < args.length; i++) {
            Array.set(rest, i - fixed, args[i]);
        }
        Object[] out = new Object[params.length];
        System.arraycopy(args, 0, out, 0, fixed);
        out[fixed] = rest;
        return out;
    }

    static boolean matchesArity(boolean varargs, int paramCount, int argc) {
        if (paramCount == argc) return true;
        return varargs && argc >= paramCount - 1;
    }

    static int scoreVarargs(Class<?>[] params, Object[] args) {
        int fixed = params.length - 1;
        Class<?> component = params[fixed].getComponentType();
        int score = 0;
        for (int i = 0; i < fixed; i++) {
            score += scoreOne(params[i], args[i]);
        }
        for (int i = fixed; i < args.length; i++) {
            score += scoreOne(component, args[i]);
        }
        return score;
    }

    static int scoreOne(Class<?> p, Object a) {
        if (a == null) {
            return p.isPrimitive() ? 0 : 1;
        }
        Class<?> ac = a.getClass();
        if (p.isAssignableFrom(ac)) {
            return 3;
        }
        if (p.isPrimitive()) {
            if ((p == int.class && (a instanceof Integer || a instanceof Byte
                    || a instanceof Short || a instanceof Character))
                    || (p == long.class && (a instanceof Long || a instanceof Integer
                    || a instanceof Byte || a instanceof Short || a instanceof Character))
                    || (p == double.class && a instanceof Number)
                    || (p == float.class && a instanceof Number)
                    || (p == short.class && (a instanceof Short || a instanceof Byte))
                    || (p == byte.class && a instanceof Byte)
                    || (p == char.class && a instanceof Character)
                    || (p == boolean.class && a instanceof Boolean)) {
                return 2;
            }
            return -1;
        }
        return 0;
    }

    static int score(Class<?>[] params, Object[] args) {
        int score = 0;
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i];
            Object a = args[i];
            if (a == null) {
                score += p.isPrimitive() ? 0 : 1;
                continue;
            }
            Class<?> ac = a.getClass();
            if (p.isAssignableFrom(ac)) {
                score += 3;
            } else if (p.isPrimitive()) {
                if ((p == int.class && (a instanceof Integer || a instanceof Byte
                        || a instanceof Short || a instanceof Character))
                        || (p == long.class && (a instanceof Long || a instanceof Integer
                        || a instanceof Byte || a instanceof Short || a instanceof Character))
                        || (p == double.class && a instanceof Number)
                        || (p == float.class && a instanceof Number)
                        || (p == short.class && (a instanceof Short || a instanceof Byte))
                        || (p == byte.class && a instanceof Byte)
                        || (p == char.class && a instanceof Character)
                        || (p == boolean.class && a instanceof Boolean)) {
                    score += 2;
                } else {
                    score -= 1;
                }
            }
        }
        return score;
    }

    static Object classForEngine(long runtime, String name) throws Throwable {
        Long previous = enter(runtime);
        try { return classFor(name); }
        finally { exit(previous); }
    }
    static boolean classExistsEngine(long runtime, String name) {
        Long previous = enter(runtime);
        try { return classExists(name); }
        finally { exit(previous); }
    }
    static Object newInstanceEngine(long runtime, String cn, Object[] args) throws Throwable {
        Long previous = enter(runtime);
        try { return newInstance(cn, args); }
        finally { exit(previous); }
    }
    static Object callStaticEngine(long runtime, String cn, String name, Object[] args) throws Throwable {
        Long previous = enter(runtime);
        try { return callStatic(cn, name, args); }
        finally { exit(previous); }
    }
    static Object getStaticEngine(long runtime, String cn, String name) throws Throwable {
        Long previous = enter(runtime);
        try { return getStatic(cn, name); }
        finally { exit(previous); }
    }
    static void setStaticEngine(long runtime, String cn, String name, Object value) throws Throwable {
        Long previous = enter(runtime);
        try { setStatic(cn, name, value); }
        finally { exit(previous); }
    }
    static Object newArrayEngine(long runtime, String component, int length) throws Throwable {
        Long previous = enter(runtime);
        try { return newArray(component, length); }
        finally { exit(previous); }
    }
    static boolean isInstanceEngine(long runtime, Object value, String name) {
        Long previous = enter(runtime);
        try { return isInstance(value, name); }
        finally { exit(previous); }
    }
    static boolean isInterfaceEngine(long runtime, String name) {
        Long previous = enter(runtime);
        try { return isInterface(name); }
        finally { exit(previous); }
    }
    static Object proxyEngine(long runtime, String[] ifaces, long handle) throws Throwable {
        Long previous = enter(runtime);
        try { return proxy(ifaces, handle); }
        finally { exit(previous); }
    }
    static String[] methodParamsEngine(long runtime, Object target, String cn, String name, int argc, boolean isStatic) throws Throwable {
        Long previous = enter(runtime);
        try { return methodParams(target, cn, name, argc, isStatic); }
        finally { exit(previous); }
    }
    static String[] ctorParamsEngine(long runtime, String cn, int argc) throws Throwable {
        Long previous = enter(runtime);
        try { return ctorParams(cn, argc); }
        finally { exit(previous); }
    }
    static String[][] methodOverloadsEngine(long runtime, Object target, String cn, String name, int argc, boolean isStatic) throws Throwable {
        Long previous = enter(runtime);
        try {
            Class<?> type = target == null ? resolve(cn) : target.getClass();
            List<Method> methods = new ArrayList<Method>();
            java.util.Collections.addAll(methods, type.getMethods());
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                java.util.Collections.addAll(methods, c.getDeclaredMethods());
            }
            List<String[]> signatures = new ArrayList<String[]>();
            Set<String> seen = new HashSet<String>();
            for (Method method : methods) {
                if (!method.getName().equals(name) || method.isBridge() || method.isSynthetic()) continue;
                if (isStatic && !Modifier.isStatic(method.getModifiers())) continue;
                if (!matchesArity(method.isVarArgs(), method.getParameterCount(), argc)) continue;
                String[] parameters = typeNames(method.getParameterTypes());
                if (seen.add(java.util.Arrays.toString(parameters))) signatures.add(parameters);
            }
            return signatures.toArray(new String[signatures.size()][]);
        } finally { exit(previous); }
    }
    static String[] samInfoEngine(long runtime, String cn) {
        Long previous = enter(runtime);
        try {
            Class<?> type;
            try { type = resolve(cn); }
            catch (ClassNotFoundException missing) { return new String[0]; }
            if (!type.isInterface()) return new String[0];
            Method sam = null;
            for (Method method : type.getMethods()) {
                if (!Modifier.isAbstract(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) continue;
                try {
                    Object.class.getMethod(method.getName(), method.getParameterTypes());
                    continue;
                } catch (NoSuchMethodException ignored) { }
                if (sam == null) {
                    sam = method;
                } else if (!sam.getName().equals(method.getName())
                        || !java.util.Arrays.equals(sam.getParameterTypes(), method.getParameterTypes())) {
                    return new String[0];
                } else if (sam.getReturnType().isAssignableFrom(method.getReturnType())) {
                    sam = method;
                } else if (!method.getReturnType().isAssignableFrom(sam.getReturnType())) {
                    return new String[0];
                }
            }
            return sam == null ? new String[0]
                    : new String[] { Integer.toString(sam.getParameterCount()), sam.getReturnType().getTypeName() };
        } finally { exit(previous); }
    }
}
