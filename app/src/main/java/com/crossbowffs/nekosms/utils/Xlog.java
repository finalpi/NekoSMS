package com.crossbowffs.nekosms.utils;

import android.util.Log;
import com.crossbowffs.nekosms.BuildConfig;

public final class Xlog {
    public interface FrameworkLogger {
        void log(int priority, String tag, String message, Throwable throwable);
    }

    private static final String LOG_TAG = BuildConfig.LOG_TAG;
    private static final int LOG_LEVEL = BuildConfig.LOG_LEVEL;
    private static final boolean LOG_TO_XPOSED = BuildConfig.LOG_TO_XPOSED;
    private static volatile FrameworkLogger sFrameworkLogger = null;

    private Xlog() { }

    public static void setFrameworkLogger(FrameworkLogger logger) {
        sFrameworkLogger = logger;
    }

    private static void log(int priority, String message, Object... args) {
        if (priority < LOG_LEVEL) {
            return;
        }

        // Perform string formatting (if the caller passed a throwable
        // as the last argument, it should be ignored)
        message = String.format(message, args);

        // If caller also passed a throwable as the last argument,
        // append its stacktrace to the message (yes I know this is
        // not safe, but there isn't a much better alternative)
        Throwable throwable = null;
        if (args.length > 0 && args[args.length - 1] instanceof Throwable) {
            throwable = (Throwable)args[args.length - 1];
            String stacktraceStr = Log.getStackTraceString(throwable);
            message += '\n' + stacktraceStr;
        }

        // Write to the default log tag
        Log.println(priority, LOG_TAG, message);

        // Duplicate to the framework log when running inside LibXposed.
        FrameworkLogger frameworkLogger = sFrameworkLogger;
        if (LOG_TO_XPOSED && frameworkLogger != null) {
            frameworkLogger.log(priority, LOG_TAG, message, throwable);
        }
    }

    public static void v(String message, Object... args) {
        log(Log.VERBOSE, message, args);
    }

    public static void d(String message, Object... args) {
        log(Log.DEBUG, message, args);
    }

    public static void i(String message, Object... args) {
        log(Log.INFO, message, args);
    }

    public static void w(String message, Object... args) {
        log(Log.WARN, message, args);
    }

    public static void e(String message, Object... args) {
        log(Log.ERROR, message, args);
    }
}
