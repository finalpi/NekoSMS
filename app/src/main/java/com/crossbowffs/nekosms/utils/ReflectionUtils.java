package com.crossbowffs.nekosms.utils;

import java.lang.reflect.*;

public final class ReflectionUtils {
    private ReflectionUtils() { }

    public static Class<?> getClass(ClassLoader classLoader, String name) {
        try {
            return Class.forName(name, true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Class<?> getClassIfExists(ClassLoader classLoader, String name) {
        try {
            return Class.forName(name, true, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public static Field getDeclaredField(Class<?> cls, String fieldName) {
        Field field;
        try {
            field = cls.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        field.setAccessible(true);
        return field;
    }

    public static Field getDeclaredFieldRecursive(Class<?> cls, String fieldName) {
        Class<?> current = cls;
        while (current != null) {
            try {
                return getDeclaredField(current, fieldName);
            } catch (RuntimeException e) {
                if (!(e.getCause() instanceof NoSuchFieldException)) {
                    throw e;
                }
            }
            current = current.getSuperclass();
        }
        throw new RuntimeException(new NoSuchFieldException(fieldName));
    }

    public static Field getField(Class<?> cls, String fieldName) {
        Field field;
        try {
            field = cls.getField(fieldName);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        field.setAccessible(true);
        return field;
    }

    public static Object getFieldValue(Field field, Object object) {
        try {
            return field.get(object);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setFieldValue(Field field, Object object, Object value) {
        try {
            field.set(object, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static Method getDeclaredMethod(Class<?> cls, String methodName, Class<?>... paramTypes) {
        Method method;
        try {
            method = cls.getDeclaredMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        method.setAccessible(true);
        return method;
    }

    public static Constructor<?> getDeclaredConstructor(Class<?> cls, Class<?>... paramTypes) {
        Constructor<?> constructor;
        try {
            constructor = cls.getDeclaredConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        constructor.setAccessible(true);
        return constructor;
    }

    private static Class<?> wrap(Class<?> cls) {
        if (!cls.isPrimitive()) {
            return cls;
        }
        if (cls == boolean.class) return Boolean.class;
        if (cls == byte.class) return Byte.class;
        if (cls == char.class) return Character.class;
        if (cls == short.class) return Short.class;
        if (cls == int.class) return Integer.class;
        if (cls == long.class) return Long.class;
        if (cls == float.class) return Float.class;
        if (cls == double.class) return Double.class;
        if (cls == void.class) return Void.class;
        return cls;
    }

    private static boolean isAssignable(Class<?> parameterType, Object arg) {
        if (arg == null) {
            return !parameterType.isPrimitive();
        }
        return wrap(parameterType).isAssignableFrom(arg.getClass());
    }

    public static Method findMethodBestMatch(Class<?> cls, String methodName, Object... args) {
        Class<?> current = cls;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != args.length) {
                    continue;
                }
                boolean matches = true;
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (!isAssignable(parameterTypes[i], args[i])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new RuntimeException(new NoSuchMethodException(methodName));
    }

    public static Method getMethod(Class<?> cls, String methodName, Class<?>... paramTypes) {
        Method method;
        try {
            method = cls.getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        method.setAccessible(true);
        return method;
    }

    public static Object invoke(Method method, Object thisObject, Object... params) {
        try {
            return method.invoke(thisObject, params);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException)cause;
            } else {
                throw new RuntimeException(e);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static String dumpModifiers(int mod) {
        String modString = Modifier.toString(mod);
        if (modString.isEmpty()) {
            return modString;
        } else {
            return modString + " ";
        }
    }

    private static String dumpParameterList(Class<?>[] ps) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        boolean first = true;
        for (Class<?> p : ps) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append(p.getCanonicalName());
        }
        sb.append(')');
        return sb.toString();
    }

    private static String dumpConstructor(Constructor<?> c) {
        StringBuilder sb = new StringBuilder();
        sb.append(dumpModifiers(c.getModifiers()));
        sb.append(c.getDeclaringClass().getSimpleName());
        sb.append(dumpParameterList(c.getParameterTypes()));
        sb.append(';');
        return sb.toString();
    }

    private static String dumpMethod(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(dumpModifiers(m.getModifiers()));
        sb.append(m.getReturnType().getName());
        sb.append(' ');
        sb.append(m.getName());
        sb.append(dumpParameterList(m.getParameterTypes()));
        sb.append(';');
        return sb.toString();
    }

    private static String dumpField(Field f) {
        StringBuilder sb = new StringBuilder();
        sb.append(dumpModifiers(f.getModifiers()));
        sb.append(f.getType().getName());
        sb.append(' ');
        sb.append(f.getName());
        sb.append(';');
        return sb.toString();
    }

    private static String dumpSuperclass(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        if (cls != null) {
            sb.append(" extends ");
            sb.append(cls.getName());
        }
        return sb.toString();
    }

    private static String dumpInterfaces(Class<?>[] ifaces) {
        StringBuilder sb = new StringBuilder();
        if (ifaces.length > 0) {
            sb.append(" implements ");
            boolean first = true;
            for (Class<?> iface : ifaces) {
                if (first) {
                    first = false;
                } else {
                    sb.append(", ");
                }
                sb.append(iface.getName());
            }
        }
        return sb.toString();
    }

    public static String dumpClass(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        sb.append(dumpModifiers(cls.getModifiers()));
        sb.append("class ");
        sb.append(cls.getName());
        sb.append(dumpSuperclass(cls.getSuperclass()));
        sb.append(dumpInterfaces(cls.getInterfaces()));
        sb.append(" {\n");
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (!c.isSynthetic()) {
                sb.append("    ");
                sb.append(dumpConstructor(c));
                sb.append('\n');
            }
        }
        sb.append("\n");
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.isSynthetic()) {
                sb.append("    ");
                sb.append(dumpMethod(m));
                sb.append('\n');
            }
        }
        sb.append("\n");
        for (Field f : cls.getDeclaredFields()) {
            if (!f.isSynthetic()) {
                sb.append("    ");
                sb.append(dumpField(f));
                sb.append('\n');
            }
        }
        sb.append('}');
        return sb.toString();
    }
}
