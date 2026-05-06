package com.crossbowffs.nekosms.utils;

public final class XposedUtils {
    private XposedUtils() { }

    public static boolean isModuleEnabled() {
        // Modern LibXposed no longer injects the module app into itself,
        // so the legacy self-hook status check is not available anymore.
        return true;
    }

    public static boolean isModuleUpdated() {
        return false;
    }
}
