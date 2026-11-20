/*
 * Derived from LSPilot/BeanShell-Rust, licensed under Apache-2.0.
 * License: third_party/beanshell-rust/LICENSE
 * Modified for Hchat engine isolation and Android class definition.
 */
package bshrs;

public final class Native {
    private Native() {}

    public static native Object invoke(long handle, String method, Object[] args);
}
