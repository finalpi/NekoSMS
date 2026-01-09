package com.crossbowffs.nekosms.app;

import android.app.Application;
import com.crossbowffs.nekosms.utils.AsyncUtils;
import com.crossbowffs.nekosms.utils.MessageCleanupHelper;
import com.google.android.material.color.DynamicColors;

public class NekoSmsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Enroll in dynamic colors if available; otherwise fall back to custom
        // theme specified in resources
        DynamicColors.applyToActivitiesIfAvailable(this);

        // Perform auto cleanup in background thread to avoid blocking app startup
        AsyncUtils.run(
            () -> {
                MessageCleanupHelper.performAutoCleanup(this);
                return null;
            },
            (result) -> {
                // Cleanup completed, no additional action needed
            }
        );
    }
}
