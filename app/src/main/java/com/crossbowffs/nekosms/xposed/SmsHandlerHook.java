package com.crossbowffs.nekosms.xposed;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Telephony;
import android.telephony.SubscriptionManager;

import com.crossbowffs.nekosms.BuildConfig;
import com.crossbowffs.nekosms.consts.BroadcastConsts;
import com.crossbowffs.nekosms.consts.PreferenceConsts;
import com.crossbowffs.nekosms.data.SmsMessageData;
import com.crossbowffs.nekosms.filters.SmsFilterLoader;
import com.crossbowffs.nekosms.loader.BlockedSmsLoader;
import com.crossbowffs.nekosms.utils.AppOpsUtils;
import com.crossbowffs.nekosms.utils.ContactUtils;
import com.crossbowffs.nekosms.utils.ReflectionUtils;
import com.crossbowffs.nekosms.utils.SmsMessageUtils;
import com.crossbowffs.nekosms.utils.StringUtils;
import com.crossbowffs.nekosms.utils.Xlog;
import com.crossbowffs.remotepreferences.RemotePreferenceAccessException;
import com.crossbowffs.remotepreferences.RemotePreferences;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class SmsHandlerHook {
    private static final String NEKOSMS_PACKAGE = BuildConfig.APPLICATION_ID;
    private static final String TELEPHONY_PACKAGE = "com.android.internal.telephony";
    private static final String SMS_HANDLER_CLASS = TELEPHONY_PACKAGE + ".InboundSmsHandler";
    private static final String HIDDEN_FEATURE_FLAGS_CLASS =
        "com.android.internal.hidden_from_bootclasspath.com.android.internal.telephony.flags.FeatureFlags";
    private static final String FEATURE_FLAGS_CLASS = TELEPHONY_PACKAGE + ".flags.FeatureFlags";
    private static final int MARK_DELETED = 2;
    private static final int EVENT_BROADCAST_COMPLETE = 3;

    private final XposedModule mModule;

    private Context mContext;
    private SmsFilterLoader mFilterLoader;
    private RemotePreferences mPreferences;

    public SmsHandlerHook(XposedModule module) {
        mModule = module;
    }

    private static Class<?> resolveClass(ClassLoader classLoader, Object type) {
        if (type instanceof Class<?>) {
            return (Class<?>)type;
        }
        if (type instanceof String) {
            return ReflectionUtils.getClass(classLoader, (String)type);
        }
        throw new IllegalArgumentException("Unsupported parameter type " + type);
    }

    private static Constructor<?> getDeclaredConstructor(ClassLoader classLoader, Object... parameterTypes) {
        Class<?> cls = ReflectionUtils.getClass(classLoader, SMS_HANDLER_CLASS);
        Class<?>[] resolvedTypes = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            resolvedTypes[i] = resolveClass(classLoader, parameterTypes[i]);
        }
        return ReflectionUtils.getDeclaredConstructor(cls, resolvedTypes);
    }

    private static Method getDeclaredMethod(ClassLoader classLoader, String methodName, Object... parameterTypes) {
        Class<?> cls = ReflectionUtils.getClass(classLoader, SMS_HANDLER_CLASS);
        Class<?>[] resolvedTypes = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            resolvedTypes[i] = resolveClass(classLoader, parameterTypes[i]);
        }
        return ReflectionUtils.getDeclaredMethod(cls, methodName, resolvedTypes);
    }

    private static Object getObjectField(Object object, String fieldName) {
        return ReflectionUtils.getFieldValue(
            ReflectionUtils.getDeclaredFieldRecursive(object.getClass(), fieldName),
            object);
    }

    private static Object callDeclaredMethod(String clsName, Object obj, String methodName, Object... args) {
        Class<?> cls = ReflectionUtils.getClass(obj.getClass().getClassLoader(), clsName);
        Method method = ReflectionUtils.findMethodBestMatch(cls, methodName, args);
        return ReflectionUtils.invoke(method, obj, args);
    }

    private static Object callMethod(Object obj, String methodName, Object... args) {
        Method method = ReflectionUtils.findMethodBestMatch(obj.getClass(), methodName, args);
        return ReflectionUtils.invoke(method, obj, args);
    }

    private static Object callStaticMethod(Class<?> cls, String methodName, Object... args) {
        Method method = ReflectionUtils.findMethodBestMatch(cls, methodName, args);
        return ReflectionUtils.invoke(method, null, args);
    }

    public void hookPackage(XposedModuleInterface.PackageReadyParam param) {
        printDeviceInfo(param);
        hookSmsHandler(param.getClassLoader());
    }

    private void grantWriteSmsPermissions(Context context) {
        PackageManager packageManager = context.getPackageManager();
        PackageInfo packageInfo;
        try {
            packageInfo = packageManager.getPackageInfo(NEKOSMS_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Xlog.e("App package not found, ignoring", e);
            return;
        }

        int uid = packageInfo.applicationInfo.uid;
        try {
            Xlog.i("Checking if we have OP_WRITE_SMS permission");
            if (AppOpsUtils.checkOp(context, AppOpsUtils.OP_WRITE_SMS, uid, NEKOSMS_PACKAGE)) {
                Xlog.i("Already have OP_WRITE_SMS permission");
            } else {
                Xlog.i("Giving our package OP_WRITE_SMS permission");
                AppOpsUtils.allowOp(context, AppOpsUtils.OP_WRITE_SMS, uid, NEKOSMS_PACKAGE);
            }
        } catch (Exception e) {
            Xlog.e("Failed to grant OP_WRITE_SMS permission", e);
        }
    }

    private void deleteFromRawTable19(Object smsHandler, Object smsReceiver) {
        Xlog.i("Removing raw SMS data from database for Android v19+");
        callDeclaredMethod(SMS_HANDLER_CLASS, smsHandler, "deleteFromRawTable",
            getObjectField(smsReceiver, "mDeleteWhere"),
            getObjectField(smsReceiver, "mDeleteWhereArgs"));
    }

    private void deleteFromRawTable24(Object smsHandler, Object smsReceiver) {
        Xlog.i("Removing raw SMS data from database for Android v24+");
        callDeclaredMethod(SMS_HANDLER_CLASS, smsHandler, "deleteFromRawTable",
            getObjectField(smsReceiver, "mDeleteWhere"),
            getObjectField(smsReceiver, "mDeleteWhereArgs"),
            MARK_DELETED);
    }

    private void deleteFromRawTable(Object smsHandler, Object smsReceiver) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            deleteFromRawTable24(smsHandler, smsReceiver);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            deleteFromRawTable19(smsHandler, smsReceiver);
        }
    }

    private void sendBroadcastComplete(Object smsHandler) {
        Xlog.i("Notifying completion of SMS broadcast");
        callMethod(smsHandler, "sendMessage", EVENT_BROADCAST_COMPLETE);
    }

    private void finishSmsBroadcast(Object smsHandler, Object smsReceiver) {
        long token = Binder.clearCallingIdentity();
        try {
            deleteFromRawTable(smsHandler, smsReceiver);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        sendBroadcastComplete(smsHandler);
    }

    private void broadcastBlockedSms(Uri messageUri) {
        Intent intent = new Intent(BroadcastConsts.ACTION_RECEIVE_SMS);
        intent.setComponent(new ComponentName(NEKOSMS_PACKAGE, BroadcastConsts.RECEIVER_NAME));
        intent.putExtra(BroadcastConsts.EXTRA_MESSAGE, messageUri);
        mContext.sendBroadcast(intent);
    }

    private boolean getBooleanPref(String key, boolean defValue) {
        try {
            return mPreferences.getBoolean(key, defValue);
        } catch (RemotePreferenceAccessException e) {
            Xlog.e("Failed to read preference: %s", key, e);
            return defValue;
        }
    }

    private Object interceptConstructor(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
            afterConstructorHandler((Context)chain.getArg(1));
        } catch (Throwable e) {
            Xlog.e("Error occurred in constructor hook", e);
            throw e;
        }
        return result;
    }

    private void afterConstructorHandler(Context context) {
        if (mContext == null) {
            mContext = context;
            mFilterLoader = new SmsFilterLoader(context);
            mPreferences = new RemotePreferences(context,
                PreferenceConsts.REMOTE_PREFS_AUTHORITY,
                PreferenceConsts.FILE_MAIN,
                true);
            grantWriteSmsPermissions(context);
        }
    }

    private void putPhoneIdAndSubIdExtra(Object inboundSmsHandler, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                Object phone = getObjectField(inboundSmsHandler, "mPhone");
                int phoneId = (Integer)callMethod(phone, "getPhoneId");
                callStaticMethod(SubscriptionManager.class, "putPhoneIdAndSubIdExtra", intent, phoneId);
            } catch (Exception e) {
                Xlog.e("Failed to call putPhoneIdAndSubIdExtra", e);
            }
        }
    }

    private void putSubscriptionIdExtraIfValid(Intent intent, int subId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if ((boolean)callStaticMethod(SubscriptionManager.class, "isValidSubscriptionId", subId)) {
                    callStaticMethod(SubscriptionManager.class, "putSubscriptionIdExtra", intent, subId);
                }
            } catch (Exception e) {
                Xlog.e("Failed to call putSubscriptionIdExtra", e);
            }
        }
    }

    private Object interceptDispatchIntent(XposedInterface.Chain chain, int receiverIndex) throws Throwable {
        try {
            return beforeDispatchIntentHandler(chain, receiverIndex);
        } catch (Throwable e) {
            Xlog.e("Error occurred in dispatchIntent() hook", e);
            throw e;
        }
    }

    private Object beforeDispatchIntentHandler(XposedInterface.Chain chain, int receiverIndex) throws Throwable {
        Intent intent = (Intent)chain.getArg(0);
        String action = intent.getAction();

        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(action)) {
            return chain.proceed();
        }

        if (!getBooleanPref(PreferenceConsts.KEY_ENABLE, PreferenceConsts.KEY_ENABLE_DEFAULT)) {
            Xlog.i("SMS blocking disabled, exiting");
            return chain.proceed();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            putPhoneIdAndSubIdExtra(chain.getThisObject(), intent);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            putSubscriptionIdExtraIfValid(intent, (Integer)chain.getArg(6));
        }

        SmsMessageData message = SmsMessageUtils.getMessageFromIntent(intent);
        String sender = message.getSender();
        String body = message.getBody();
        Xlog.i("Received a new SMS message");
        if (getBooleanPref(PreferenceConsts.KEY_VERBOSE_LOGGING, PreferenceConsts.KEY_VERBOSE_LOGGING_DEFAULT)) {
            Xlog.i("Sender: %s", StringUtils.escape(sender));
            Xlog.i("Body: %s", StringUtils.escape(body));
        } else {
            Xlog.v("Sender: %s", StringUtils.escape(sender));
            Xlog.v("Body: %s", StringUtils.escape(body));
        }

        boolean allowContacts = getBooleanPref(
            PreferenceConsts.KEY_WHITELIST_CONTACTS,
            PreferenceConsts.KEY_WHITELIST_CONTACTS_DEFAULT);
        if (allowContacts && ContactUtils.isContact(mContext, sender)) {
            Xlog.i("Allowing message (contact whitelist)");
            return chain.proceed();
        }

        if (!mFilterLoader.shouldBlockMessage(sender, body)) {
            return chain.proceed();
        }

        Uri messageUri = BlockedSmsLoader.get().insert(mContext, message);
        broadcastBlockedSms(messageUri);
        finishSmsBroadcast(chain.getThisObject(), chain.getArg(receiverIndex));
        return null;
    }

    private void hookConstructor19(ClassLoader classLoader) {
        Xlog.i("Hooking InboundSmsHandler constructor for Android v19+");
        mModule.hook(getDeclaredConstructor(classLoader,
            String.class,
            Context.class,
            TELEPHONY_PACKAGE + ".SmsStorageMonitor",
            TELEPHONY_PACKAGE + ".PhoneBase",
            TELEPHONY_PACKAGE + ".CellBroadcastHandler"))
            .intercept(this::interceptConstructor);
    }

    private void hookConstructor24(ClassLoader classLoader) {
        Xlog.i("Hooking InboundSmsHandler constructor for Android v24+");
        mModule.hook(getDeclaredConstructor(classLoader,
            String.class,
            Context.class,
            TELEPHONY_PACKAGE + ".SmsStorageMonitor",
            TELEPHONY_PACKAGE + ".Phone",
            TELEPHONY_PACKAGE + ".CellBroadcastHandler"))
            .intercept(this::interceptConstructor);
    }

    private void hookConstructor30(ClassLoader classLoader) {
        Xlog.i("Hooking InboundSmsHandler constructor for Android v30+");
        mModule.hook(getDeclaredConstructor(classLoader,
            String.class,
            Context.class,
            TELEPHONY_PACKAGE + ".SmsStorageMonitor",
            TELEPHONY_PACKAGE + ".Phone"))
            .intercept(this::interceptConstructor);
    }

    private void hookConstructor34(ClassLoader classLoader) {
        Xlog.i("Hooking InboundSmsHandler constructor for Android v34+");
        mModule.hook(getDeclaredConstructor(classLoader,
            String.class,
            Context.class,
            TELEPHONY_PACKAGE + ".SmsStorageMonitor",
            TELEPHONY_PACKAGE + ".Phone",
            Looper.class))
            .intercept(this::interceptConstructor);
    }

    private void hookConstructor36(ClassLoader classLoader) {
        Xlog.i("Hooking InboundSmsHandler constructor for Android v36+ (Android 16)");

        Class<?> featureFlagsClass = null;
        String[] featureFlagsClassNames = { HIDDEN_FEATURE_FLAGS_CLASS, FEATURE_FLAGS_CLASS };
        for (String className : featureFlagsClassNames) {
            featureFlagsClass = ReflectionUtils.getClassIfExists(classLoader, className);
            if (featureFlagsClass != null) {
                Xlog.i("Found FeatureFlags class: %s", className);
                break;
            }
            Xlog.w("FeatureFlags class not found at %s, trying next", className);
        }

        if (featureFlagsClass != null) {
            try {
                mModule.hook(getDeclaredConstructor(classLoader,
                    String.class,
                    Context.class,
                    TELEPHONY_PACKAGE + ".SmsStorageMonitor",
                    TELEPHONY_PACKAGE + ".Phone",
                    Looper.class,
                    featureFlagsClass))
                    .intercept(this::interceptConstructor);
                Xlog.i("Successfully hooked Android 16 constructor with FeatureFlags");
                return;
            } catch (RuntimeException e) {
                Xlog.w("Android 16 constructor with FeatureFlags failed: %s", e.getMessage());
            }
        }

        try {
            mModule.hook(getDeclaredConstructor(classLoader,
                String.class,
                Context.class,
                TELEPHONY_PACKAGE + ".SmsStorageMonitor",
                TELEPHONY_PACKAGE + ".Phone",
                Looper.class))
                .intercept(this::interceptConstructor);
            Xlog.i("Successfully hooked Android 34 constructor (without FeatureFlags)");
            return;
        } catch (RuntimeException e) {
            Xlog.w("Android 34 constructor failed: %s", e.getMessage());
        }

        try {
            mModule.hook(getDeclaredConstructor(classLoader,
                String.class,
                Context.class,
                TELEPHONY_PACKAGE + ".Phone",
                Looper.class))
                .intercept(this::interceptConstructor);
            Xlog.i("Successfully hooked constructor variant 3 (no storageMonitor)");
            return;
        } catch (RuntimeException e) {
            Xlog.w("Variant 3 failed: %s", e.getMessage());
        }

        try {
            mModule.hook(getDeclaredConstructor(classLoader,
                String.class,
                Context.class,
                Looper.class))
                .intercept(this::interceptConstructor);
            Xlog.i("Successfully hooked constructor variant 4 (context and looper only)");
            return;
        } catch (RuntimeException e) {
            Xlog.w("Variant 4 failed: %s", e.getMessage());
        }

        Xlog.e("All Android 16 constructor variants failed. Cannot hook InboundSmsHandler.");
        throw new RuntimeException("Failed to hook InboundSmsHandler constructor for Android 16");
    }

    private void hookDispatchIntent19(ClassLoader classLoader) {
        Xlog.i("Hooking dispatchIntent() for Android v19+");
        mModule.hook(getDeclaredMethod(classLoader, "dispatchIntent",
            Intent.class,
            String.class,
            int.class,
            BroadcastReceiver.class))
            .intercept(chain -> interceptDispatchIntent(chain, 3));
    }

    private void hookDispatchIntent21(ClassLoader classLoader) {
        Xlog.i("Hooking dispatchIntent() for Android v21+");
        mModule.hook(getDeclaredMethod(classLoader, "dispatchIntent",
            Intent.class,
            String.class,
            int.class,
            BroadcastReceiver.class,
            UserHandle.class))
            .intercept(chain -> interceptDispatchIntent(chain, 3));
    }

    private void hookDispatchIntent23(ClassLoader classLoader) {
        Xlog.i("Hooking dispatchIntent() for Android v23+");
        mModule.hook(getDeclaredMethod(classLoader, "dispatchIntent",
            Intent.class,
            String.class,
            int.class,
            Bundle.class,
            BroadcastReceiver.class,
            UserHandle.class))
            .intercept(chain -> interceptDispatchIntent(chain, 4));
    }

    private void hookDispatchIntent29(ClassLoader classLoader) {
        Xlog.i("Hooking dispatchIntent() for Android v29+");
        mModule.hook(getDeclaredMethod(classLoader, "dispatchIntent",
            Intent.class,
            String.class,
            int.class,
            Bundle.class,
            BroadcastReceiver.class,
            UserHandle.class,
            int.class))
            .intercept(chain -> interceptDispatchIntent(chain, 4));
    }

    private void hookDispatchIntent30(ClassLoader classLoader) {
        Xlog.i("Hooking dispatchIntent() for Android v30+");
        mModule.hook(getDeclaredMethod(classLoader, "dispatchIntent",
            Intent.class,
            String.class,
            String.class,
            Bundle.class,
            BroadcastReceiver.class,
            UserHandle.class,
            int.class))
            .intercept(chain -> interceptDispatchIntent(chain, 4));
    }

    private void hookDispatchIntent31(ClassLoader classLoader) {
        Xlog.i("Hooking dispatchIntent() for Android v31+");
        mModule.hook(getDeclaredMethod(classLoader, "dispatchIntent",
            Intent.class,
            String.class,
            String.class,
            Bundle.class,
            SMS_HANDLER_CLASS + "$SmsBroadcastReceiver",
            UserHandle.class,
            int.class))
            .intercept(chain -> interceptDispatchIntent(chain, 4));
    }

    private void hookDispatchIntent36(ClassLoader classLoader) {
        Xlog.i("Hooking dispatchIntent() for Android v36+ (Android 16)");
        try {
            mModule.hook(getDeclaredMethod(classLoader, "dispatchIntent",
                Intent.class,
                String.class,
                String.class,
                Bundle.class,
                SMS_HANDLER_CLASS + "$SmsBroadcastReceiver",
                UserHandle.class,
                int.class))
                .intercept(chain -> interceptDispatchIntent(chain, 4));
            Xlog.i("Successfully hooked dispatchIntent variant 1 (same as v31)");
        } catch (RuntimeException e1) {
            try {
                mModule.hook(getDeclaredMethod(classLoader, "dispatchIntent",
                    Intent.class,
                    String.class,
                    String.class,
                    Bundle.class,
                    SMS_HANDLER_CLASS + "$SmsBroadcastReceiver",
                    int.class))
                    .intercept(chain -> interceptDispatchIntent(chain, 4));
                Xlog.i("Successfully hooked dispatchIntent variant 2 (without UserHandle)");
            } catch (RuntimeException e2) {
                Xlog.w("All Android 16 dispatchIntent variants failed, falling back to Android 31 method");
                throw e1;
            }
        }
    }

    private void hookConstructor(ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT >= 36) {
            hookConstructor36(classLoader);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hookConstructor34(classLoader);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hookConstructor30(classLoader);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            hookConstructor24(classLoader);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            hookConstructor19(classLoader);
        }
    }

    private void hookDispatchIntent(ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                hookDispatchIntent36(classLoader);
            } catch (RuntimeException e) {
                Xlog.w("Android 16 dispatchIntent hook failed, trying fallback to Android 31", e);
                hookDispatchIntent31(classLoader);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hookDispatchIntent31(classLoader);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hookDispatchIntent30(classLoader);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                hookDispatchIntent29(classLoader);
            } catch (RuntimeException e) {
                hookDispatchIntent23(classLoader);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hookDispatchIntent23(classLoader);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            hookDispatchIntent21(classLoader);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            hookDispatchIntent19(classLoader);
        }
    }

    private void hookSmsHandler(ClassLoader classLoader) {
        hookConstructor(classLoader);
        hookDispatchIntent(classLoader);
    }

    private static String getConstructorSignature(Constructor<?> constructor) {
        StringBuilder signature = new StringBuilder(constructor.getDeclaringClass().getSimpleName() + "(");
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            signature.append(parameterTypes[i].getName());
            if (i < parameterTypes.length - 1) {
                signature.append(", ");
            }
        }
        signature.append(")");
        return signature.toString();
    }

    private void printDeviceInfo(XposedModuleInterface.PackageReadyParam param) {
        Xlog.i("Phone manufacturer: %s", Build.MANUFACTURER);
        Xlog.i("Phone model: %s", Build.MODEL);
        Xlog.i("Android version: %s (API %d)", Build.VERSION.RELEASE, Build.VERSION.SDK_INT);
        Xlog.i("Xposed framework version: %d", mModule.getFrameworkVersionCode());
        Xlog.i("NekoSMS version: %s (%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        try {
            Class<?> cls = ReflectionUtils.getClass(param.getClassLoader(), SMS_HANDLER_CLASS);
            Constructor<?>[] constructors = cls.getDeclaredConstructors();
            Xlog.i("Found %d constructors for %s:", constructors.length, SMS_HANDLER_CLASS);
            for (Constructor<?> constructor : constructors) {
                Xlog.i("  %s", getConstructorSignature(constructor));
            }
        } catch (Exception e) {
            Xlog.e("Failed to dump SMS handler constructors", e);
        }
    }
}
