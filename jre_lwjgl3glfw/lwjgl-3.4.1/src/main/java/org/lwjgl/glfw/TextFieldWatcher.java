package org.lwjgl.glfw;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Detects when a text field of Minecraft gets (or loses) the focus, so the launcher can show the
 * on-screen keyboard by itself (anvils, signs, books, dialogs, login screens, search boxes...).
 *
 * Minecraft 26.1 and newer are not obfuscated, so the screen and its focused widget can be inspected
 * by reflection using their real names. On older (obfuscated) versions the class is simply not found
 * and the watcher disables itself, leaving the other detection methods in charge.
 * Called from glfwPollEvents, i.e. from the game's own render thread.
 */
final class TextFieldWatcher {
    private static final long INTERVAL_NS = 150_000_000L;
    private static final int MAX_RESOLVE_ATTEMPTS = 40;
    private static final int MAX_FAILURES = 10;

    private static boolean sDisabled;
    private static boolean sResolved;
    private static int sAttempts;
    private static int sFailures;
    private static long sLastPoll;
    private static boolean sFocused;
    private static Method sGetInstance;
    private static Field sScreenField;
    private static final Map<Class<?>, Method> sGetFocusedCache = new HashMap<>();
    private static final Map<Class<?>, Boolean> sTextWidgetCache = new HashMap<>();

    private TextFieldWatcher() {}

    static void poll() {
        if (sDisabled) return;
        long now = System.nanoTime();
        if (now - sLastPoll < INTERVAL_NS) return;
        sLastPoll = now;
        try {
            if (!sResolved && !resolve()) return;
            boolean focused = isTextFieldFocused();
            if (focused != sFocused) {
                sFocused = focused;
                CallbackBridge.nativeNotifyLauncher(CallbackBridge.TEXT_FIELD, CallbackBridge.TEXT_FIELD_FOCUS, focused ? 1 : 0);
            }
        } catch (Throwable t) {
            if (++sFailures >= MAX_FAILURES) sDisabled = true;
        }
    }

    private static boolean resolve() {
        ClassLoader[] loaders = {
                GLFW.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
                sGetInstance = minecraft.getMethod("getInstance");
                sScreenField = minecraft.getDeclaredField("screen");
                sScreenField.setAccessible(true);
                sResolved = true;
                return true;
            } catch (Throwable ignored) {}
        }
        // Obfuscated version (or Minecraft not loaded yet): give up after a few seconds
        if (++sAttempts >= MAX_RESOLVE_ATTEMPTS) sDisabled = true;
        return false;
    }

    private static boolean isTextFieldFocused() throws Exception {
        Object minecraft = sGetInstance.invoke(null);
        if (minecraft == null) return false;
        Object screen = sScreenField.get(minecraft);
        if (screen == null) return false;

        // Signs and books are edited without an EditBox
        String screenName = screen.getClass().getName();
        if (screenName.contains("SignEditScreen") || screenName.contains("BookEditScreen")) return true;

        // Follow the focus down the widget tree (screen -> container -> widget)
        Object current = screen;
        for (int depth = 0; depth < 8; depth++) {
            Method getFocused = findGetFocused(current.getClass());
            if (getFocused == null) break;
            Object next = getFocused.invoke(current);
            if (next == null) break;
            current = next;
        }
        if (current == screen || !isTextWidget(current.getClass())) return false;
        Method isFocused = current.getClass().getMethod("isFocused");
        isFocused.setAccessible(true);
        return Boolean.TRUE.equals(isFocused.invoke(current));
    }

    private static Method findGetFocused(Class<?> type) {
        if (sGetFocusedCache.containsKey(type)) return sGetFocusedCache.get(type);
        Method method = null;
        try {
            method = type.getMethod("getFocused");
            method.setAccessible(true);
        } catch (Throwable ignored) {}
        sGetFocusedCache.put(type, method);
        return method;
    }

    private static boolean isTextWidget(Class<?> type) {
        Boolean cached = sTextWidgetCache.get(type);
        if (cached != null) return cached;
        boolean result = false;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().endsWith("EditBox")) { result = true; break; }
        }
        sTextWidgetCache.put(type, result);
        return result;
    }
}
