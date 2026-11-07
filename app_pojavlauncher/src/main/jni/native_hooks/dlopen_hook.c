#include "environ/environ.h"
#include "utils.h"
#include "native_hooks.h"
#include "log.h"

#include <bytehook.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
// WARNING: Hooking dlopen and dlsym does not work for all devices, seems to be a conflict with
// the turnip loader, perhaps Dobby would fare better.
typedef void *(*dlopen_func_t)(const char *, int);
typedef void *(*dlsym_func_t)(void *, const char *);
typedef jint (*JNI_OnLoad_t)(JavaVM *vm, void *reserved);

typedef struct {
    const char *library_name;
    const char *library_rename;
    void *handle;
    JNI_OnLoad_t jni_onload;
} LibraryRedirect;

static LibraryRedirect libRedirectInfo[] = {
        { "libSDL3.so", "libSDL3.so" },
        { "libSDL2.so", "libSDL2.so" },
        { NULL, NULL } // Null termination
};

__attribute__((constructor)) static void init() {
    // We can't hook dlopen so we make do with this.
    libRedirectInfo[0].handle = dlopen("libSDL3.so", RTLD_LOCAL | RTLD_LAZY);
    libRedirectInfo[1].handle = dlopen("libSDL2.so", RTLD_LOCAL | RTLD_LAZY);
}

// Loose matching of lib file name to hard coded list
static LibraryRedirect* get_library_redirect_if_needed(const char *filename) {
    if (filename == NULL)
        return NULL;
    const char *basename = strrchr(filename, '/');
    basename = basename ? basename + 1 : filename;
    for (size_t i = 0; libRedirectInfo[i].library_name != NULL; ++i) {
        if (strstr(basename, libRedirectInfo[i].library_name) != NULL) {
            return &libRedirectInfo[i];
        }
    }
    return NULL;
}

void *custom_dlopen(const char *filename, int flags) {
    LibraryRedirect *redirect = get_library_redirect_if_needed(filename);
    const char *target_filename = filename;
    if (redirect != NULL) {
        target_filename = redirect->library_rename;
        LOGI("Redirecting dlopen: %s -> %s", filename, target_filename);
    }

    void *result = BYTEHOOK_CALL_PREV(
            custom_dlopen,
            dlopen_func_t,
            target_filename,
            flags);

    if (redirect != NULL)
        redirect->handle = result;

    BYTEHOOK_POP_STACK();
    return result;
}

static LibraryRedirect *get_redirect_by_handle(void *handle) {
    if (handle == NULL)
        return NULL;

    for (size_t i = 0; libRedirectInfo[i].library_name != NULL; ++i) {
        if (libRedirectInfo[i].handle == handle)
            return &libRedirectInfo[i];
    }

    return NULL;
}

static jint rerouted_jni_onload(
        LibraryRedirect *redirect,
        JavaVM *vm,
        void *reserved) {

    if (redirect == NULL) {
        return JNI_ERR;
    }

    if (pojav_environ != NULL &&
            pojav_environ->dalvikJavaVMPtr == vm &&
            redirect->jni_onload != NULL) {
        return ((JNI_OnLoad_t) redirect->jni_onload)(vm, reserved);
    }

    return JNI_VERSION_1_4;
}

#define DEFINE_JNI_WRAPPER(id)                                              \
    static jint jni_onload_##id(                                            \
            JavaVM *vm,                                                     \
            void *reserved) {                                               \
        return rerouted_jni_onload(&libRedirectInfo[id], vm, reserved);     \
    }

// This is a bit magicky yeah
DEFINE_JNI_WRAPPER(0)
DEFINE_JNI_WRAPPER(1)

static JNI_OnLoad_t get_jni_onload_wrapper(
        LibraryRedirect *redirect) {
    if (redirect == &libRedirectInfo[0]) {
        return jni_onload_0;
    }

    if (redirect == &libRedirectInfo[1]) {
        return jni_onload_1;
    }

    return NULL;
}

// ---------------------------------------------------------------------------------------------
// Minecraft 26.3+ (RenderPearl) talks to the window system through SDL3 (not GLFW anymore), which
// on Android has two limitations that the game does not know about:
//
//  1) It checks that glGetError resolved through LWJGL and through SDL_GL_GetProcAddress is the
//     very same function. LWJGL gets GL functions with dlsym() on the renderer library
//     (-Dorg.lwjgl.opengl.libname), while SDL asks the EGL library, which can hand out another
//     pointer ("glGetError mismatch"). SDL_GL_GetProcAddress is replaced (for gl* symbols) by a
//     lookup in the renderer library (exported by the launcher as SDL_OPENGL_LIBRARY).
//
//  2) Android's SDL only supports ONE window ("Android only supports one window"), but the
//     OpenGL backend of the game may ask for another one. Window creation is therefore
//     reference counted: asking for a window while one exists returns the existing one, and the
//     window is only really destroyed when every user has destroyed it.
//
// Every call is also logged (jrelog) so problems on the next steps are easy to trace.
// ---------------------------------------------------------------------------------------------
typedef void *(*gl_getprocaddress_func_t)(const char *);
typedef void *(*sdl_create_window_func_t)(const char *, int, int, uint64_t);
typedef void *(*sdl_create_window_props_func_t)(uint32_t);
typedef void (*sdl_destroy_window_func_t)(void *);
typedef void *(*sdl_gl_create_context_func_t)(void *);
typedef bool (*sdl_gl_make_current_func_t)(void *, void *);
typedef bool (*sdl_gl_set_attribute_func_t)(int, int);

static gl_getprocaddress_func_t real_SDL_GL_GetProcAddress = NULL;
static sdl_create_window_func_t real_SDL_CreateWindow = NULL;
static sdl_create_window_props_func_t real_SDL_CreateWindowWithProperties = NULL;
static sdl_destroy_window_func_t real_SDL_DestroyWindow = NULL;
static sdl_gl_create_context_func_t real_SDL_GL_CreateContext = NULL;
static sdl_gl_make_current_func_t real_SDL_GL_MakeCurrent = NULL;
static sdl_gl_set_attribute_func_t real_SDL_GL_SetAttribute = NULL;

static void *g_renderer_gl_handle = NULL;
static int g_renderer_gl_handle_tried = 0;
static void *g_sdl_window = NULL;
static int g_sdl_window_refs = 0;
static pthread_mutex_t g_sdl_window_mutex = PTHREAD_MUTEX_INITIALIZER;

static void *amethyst_SDL_GL_GetProcAddress(const char *proc) {
    if (proc != NULL && proc[0] == 'g' && proc[1] == 'l') {
        if (!g_renderer_gl_handle_tried) {
            g_renderer_gl_handle_tried = 1;
            const char *renderer_lib = getenv("SDL_OPENGL_LIBRARY");
            if (renderer_lib != NULL && renderer_lib[0] != '\0') {
                g_renderer_gl_handle = dlopen(renderer_lib, RTLD_LAZY | RTLD_NOLOAD);
                if (g_renderer_gl_handle == NULL)
                    g_renderer_gl_handle = dlopen(renderer_lib, RTLD_LAZY);
                LOGI("Amethyst-Android: SDL_GL_GetProcAddress now resolves GL functions from %s (handle %p)",
                     renderer_lib, g_renderer_gl_handle);
            }
        }
        if (g_renderer_gl_handle != NULL) {
            void *symbol = dlsym(g_renderer_gl_handle, proc);
            if (symbol != NULL) return symbol;
        }
    }
    return real_SDL_GL_GetProcAddress != NULL ? real_SDL_GL_GetProcAddress(proc) : NULL;
}

static void *amethyst_SDL_CreateWindow(const char *title, int w, int h, uint64_t flags) {
    pthread_mutex_lock(&g_sdl_window_mutex);
    void *window;
    if (g_sdl_window != NULL) {
        g_sdl_window_refs++;
        window = g_sdl_window;
        LOGI("Amethyst-Android: SDL_CreateWindow(\"%s\", %d, %d, 0x%llx) -> reusing window %p (refs=%d)",
             title ? title : "", w, h, (unsigned long long) flags, window, g_sdl_window_refs);
    } else {
        window = real_SDL_CreateWindow(title, w, h, flags);
        LOGI("Amethyst-Android: SDL_CreateWindow(\"%s\", %d, %d, 0x%llx) -> %p",
             title ? title : "", w, h, (unsigned long long) flags, window);
        if (window != NULL) { g_sdl_window = window; g_sdl_window_refs = 1; }
    }
    pthread_mutex_unlock(&g_sdl_window_mutex);
    return window;
}

static void *amethyst_SDL_CreateWindowWithProperties(uint32_t props) {
    pthread_mutex_lock(&g_sdl_window_mutex);
    void *window;
    if (g_sdl_window != NULL) {
        g_sdl_window_refs++;
        window = g_sdl_window;
        LOGI("Amethyst-Android: SDL_CreateWindowWithProperties(%u) -> reusing window %p (refs=%d)",
             props, window, g_sdl_window_refs);
    } else {
        window = real_SDL_CreateWindowWithProperties(props);
        LOGI("Amethyst-Android: SDL_CreateWindowWithProperties(%u) -> %p", props, window);
        if (window != NULL) { g_sdl_window = window; g_sdl_window_refs = 1; }
    }
    pthread_mutex_unlock(&g_sdl_window_mutex);
    return window;
}

static void amethyst_SDL_DestroyWindow(void *window) {
    pthread_mutex_lock(&g_sdl_window_mutex);
    if (window != NULL && window == g_sdl_window) {
        if (--g_sdl_window_refs > 0) {
            LOGI("Amethyst-Android: SDL_DestroyWindow(%p) -> still in use (refs=%d), kept", window, g_sdl_window_refs);
            pthread_mutex_unlock(&g_sdl_window_mutex);
            return;
        }
        g_sdl_window = NULL;
        g_sdl_window_refs = 0;
    }
    LOGI("Amethyst-Android: SDL_DestroyWindow(%p)", window);
    pthread_mutex_unlock(&g_sdl_window_mutex);
    real_SDL_DestroyWindow(window);
}

static void *amethyst_SDL_GL_CreateContext(void *window) {
    void *context = real_SDL_GL_CreateContext(window);
    LOGI("Amethyst-Android: SDL_GL_CreateContext(%p) -> %p", window, context);
    return context;
}

static bool amethyst_SDL_GL_MakeCurrent(void *window, void *context) {
    bool result = real_SDL_GL_MakeCurrent(window, context);
    LOGI("Amethyst-Android: SDL_GL_MakeCurrent(%p, %p) -> %d", window, context, (int) result);
    return result;
}

static bool amethyst_SDL_GL_SetAttribute(int attr, int value) {
    bool result = real_SDL_GL_SetAttribute(attr, value);
    LOGI("Amethyst-Android: SDL_GL_SetAttribute(%d, %d) -> %d", attr, value, (int) result);
    return result;
}

// Returns the replacement for an SDL3 symbol (remembering the real one), or NULL to leave it alone
static void *amethyst_wrap_sdl_symbol(const char *symbol, void *real) {
    if (symbol == NULL || real == NULL) return NULL;
    if (strcmp(symbol, "SDL_GL_GetProcAddress") == 0) {
        real_SDL_GL_GetProcAddress = (gl_getprocaddress_func_t) real;
        return (void *) amethyst_SDL_GL_GetProcAddress;
    } else if (strcmp(symbol, "SDL_CreateWindow") == 0) {
        real_SDL_CreateWindow = (sdl_create_window_func_t) real;
        return (void *) amethyst_SDL_CreateWindow;
    } else if (strcmp(symbol, "SDL_CreateWindowWithProperties") == 0) {
        real_SDL_CreateWindowWithProperties = (sdl_create_window_props_func_t) real;
        return (void *) amethyst_SDL_CreateWindowWithProperties;
    } else if (strcmp(symbol, "SDL_DestroyWindow") == 0) {
        real_SDL_DestroyWindow = (sdl_destroy_window_func_t) real;
        return (void *) amethyst_SDL_DestroyWindow;
    } else if (strcmp(symbol, "SDL_GL_CreateContext") == 0) {
        real_SDL_GL_CreateContext = (sdl_gl_create_context_func_t) real;
        return (void *) amethyst_SDL_GL_CreateContext;
    } else if (strcmp(symbol, "SDL_GL_MakeCurrent") == 0) {
        real_SDL_GL_MakeCurrent = (sdl_gl_make_current_func_t) real;
        return (void *) amethyst_SDL_GL_MakeCurrent;
    } else if (strcmp(symbol, "SDL_GL_SetAttribute") == 0) {
        real_SDL_GL_SetAttribute = (sdl_gl_set_attribute_func_t) real;
        return (void *) amethyst_SDL_GL_SetAttribute;
    }
    return NULL;
}

void *custom_dlsym(void *handle, const char *symbol) {
    void *result = BYTEHOOK_CALL_PREV(
            custom_dlsym,
            dlsym_func_t,
            handle,
            symbol);
    LibraryRedirect *redirect = get_redirect_by_handle(handle);
    if ((redirect != NULL) && result != NULL && symbol != NULL
        && strstr(redirect->library_name, "libSDL3") != NULL) {
        void *wrapper = amethyst_wrap_sdl_symbol(symbol, result);
        if (wrapper != NULL) {
            LOGI("Amethyst-Android: Intercepted dlsym on %s: %p", symbol, result);
            BYTEHOOK_POP_STACK();
            return wrapper;
        }
    }
    if ((redirect != NULL) && strcmp(symbol, "JNI_OnLoad") == 0) {
        if (strstr(redirect->library_name, "libSDL3")) {
            redirect->jni_onload = (JNI_OnLoad_t) result;
            // This outputs in the minecraft logs
            LOGI("Amethyst-Android: Intercepted dlsym on SDL3 JNI_OnLoad: %p", result);
            BYTEHOOK_POP_STACK();
            return get_jni_onload_wrapper(redirect);
        }
    }
    BYTEHOOK_POP_STACK();
    return result;
}

void create_dlopen_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    // FIXME: Hooking dlopen causes a crash with Turnip loader, so let's stop doing that entirely
    // Maybe using android_dlopen_ext would help but I can't be bothered to do that yet
    bytehook_stub_t stub_dlopen = (void *)(uintptr_t)0xD15AB1ED;
//            bytehook_hook_all_p(NULL, "dlopen", &custom_dlopen, NULL, NULL);
    bytehook_stub_t stub_dlsym =
            bytehook_hook_all_p(NULL, "dlsym", &custom_dlsym, NULL, NULL);
    LOGI("Successfully initialized dlopen hooks, stub: %p %p", stub_dlopen, stub_dlsym);
}