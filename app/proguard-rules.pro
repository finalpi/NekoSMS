-dontobfuscate

# Keep so explicit intents can use class name
-keep class com.crossbowffs.nekosms.app.BlockedSmsReceiver {
    void <init>();
}

# Keep the LibXposed module entry class referenced from META-INF/xposed/java_init.list
-keep class com.crossbowffs.nekosms.xposed.NekoSmsXposedModule {
    void <init>();
}
