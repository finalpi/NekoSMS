package com.crossbowffs.nekosms.utils;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.crossbowffs.nekosms.consts.PreferenceConsts;
import com.crossbowffs.nekosms.loader.BlockedSmsLoader;

/**
 * Message auto cleanup helper
 * Automatically cleans up old blocked messages based on user configuration
 */
public class MessageCleanupHelper {
    
    /**
     * Check and perform automatic cleanup
     * If auto cleanup is enabled, delete old messages that exceed the configured days
     * 
     * @param context Context
     */
    public static void performAutoCleanup(Context context) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            
            // Check if auto cleanup is enabled
            boolean cleanupEnabled = prefs.getBoolean(
                PreferenceConsts.KEY_CLEANUP_ENABLE, 
                PreferenceConsts.KEY_CLEANUP_ENABLE_DEFAULT
            );
            
            if (!cleanupEnabled) {
                Xlog.v("Auto cleanup is disabled, skipping");
                return;
            }
            
            // Get cleanup days configuration
            String daysStr = prefs.getString(
                PreferenceConsts.KEY_CLEANUP_DAYS, 
                PreferenceConsts.KEY_CLEANUP_DAYS_DEFAULT
            );
            
            int days;
            try {
                days = Integer.parseInt(daysStr);
            } catch (NumberFormatException e) {
                Xlog.e("Invalid cleanup days value: %s, using default", daysStr);
                days = Integer.parseInt(PreferenceConsts.KEY_CLEANUP_DAYS_DEFAULT);
            }
            
            // Validate days value
            if (days <= 0) {
                Xlog.w("Invalid cleanup days: %d, must be positive", days);
                return;
            }
            
            // Perform cleanup
            Xlog.i("Performing auto cleanup for messages older than %d days", days);
            int deletedCount = BlockedSmsLoader.get().deleteOldMessages(context, days);
            Xlog.i("Auto cleanup completed, deleted %d old messages", deletedCount);
            
        } catch (Exception e) {
            // Catch all exceptions to prevent cleanup from affecting main flow
            Xlog.e("Error during auto cleanup", e);
        }
    }
}

