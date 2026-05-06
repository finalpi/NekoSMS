package com.crossbowffs.nekosms.xposed;

import androidx.annotation.NonNull;

import com.crossbowffs.nekosms.utils.Xlog;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class NekoSmsXposedModule extends XposedModule {
    private static final String PHONE_PACKAGE = "com.android.phone";

    private final SmsHandlerHook mSmsHandlerHook = new SmsHandlerHook(this);

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        Xlog.setFrameworkLogger((priority, tag, message, throwable) -> {
            if (throwable == null) {
                log(priority, tag, message);
            } else {
                log(priority, tag, message, throwable);
            }
        });
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        try {
            if (PHONE_PACKAGE.equals(param.getPackageName())) {
                Xlog.i("NekoSMS initializing...");
                mSmsHandlerHook.hookPackage(param);
                Xlog.i("NekoSMS initialization complete!");
            }
        } catch (Throwable e) {
            Xlog.e("Failed to initialize hooks for %s", param.getPackageName(), e);
            throw e;
        }
    }
}
