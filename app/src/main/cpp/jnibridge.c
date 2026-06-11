#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define TAG "jnibridge"

JNIEXPORT jint JNICALL
Java_com_ollamabox_ServerBridge_nativeStartServer(
    JNIEnv* env, jobject thiz,
    jstring modelPath, jstring host, jint port, jint ctxSize,
    jstring nativeLibDir) {

    const char* cLibDir = (*env)->GetStringUTFChars(env, nativeLibDir, NULL);

    /* ── Redirect stderr first ── */
    const char* errpath = "/data/user/0/com.ollamabox/files/stderr.log";
    FILE* errf = freopen(errpath, "w", stderr);
    fprintf(stderr, "=== JNI bridge ===\nlib dir: %s\n", cLibDir);
    fflush(stderr);

    /* ── Load all required libs with RTLD_GLOBAL ──
     * Use short SONAME — Android 16 linker resolves from APK, not filesystem */
    struct {
        const char* soname;
        void* handle;
    } libs[] = {
        {"libggml-base.so",  NULL},
        {"libggml.so",       NULL},
        {"libllama.so",      NULL},
        {"libllama-common.so", NULL},
        {"libmtmd.so",       NULL},
        {NULL, NULL}
    };

    for (int i = 0; libs[i].soname; i++) {
        libs[i].handle = dlopen(libs[i].soname, RTLD_NOW | RTLD_GLOBAL);
        fprintf(stderr, "dlopen(%s) = %p\n", libs[i].soname, libs[i].handle);
        if (!libs[i].handle) {
            fprintf(stderr, "  ERROR: %s\n", dlerror());
        }
    }
    fflush(stderr);

    /* ── Load CPU backend via ggml_backend_load ── */
    void* h_ggml = libs[1].handle; /* libggml.so */
    void* (*backend_load)(const char*) = NULL;
    if (h_ggml) {
        backend_load = (void* (*)(const char*))dlsym(h_ggml, "ggml_backend_load");
        fprintf(stderr, "dlsym(libggml, ggml_backend_load) = %p\n", (void*)backend_load);
    }

    if (backend_load) {
        const char* backends[] = {
            "libggml-cpu-android_armv9.2_2.so",
            "libggml-cpu-android_armv9.2_1.so",
            "libggml-cpu-android_armv9.0_1.so",
            "libggml-cpu-android_armv8.6_1.so",
            "libggml-cpu-android_armv8.2_2.so",
            "libggml-cpu-android_armv8.2_1.so",
            "libggml-cpu-android_armv8.0_1.so",
            NULL
        };
        for (int i = 0; backends[i]; i++) {
            void* reg = backend_load(backends[i]);
            fprintf(stderr, "backend_load(%s) = %p\n", backends[i], reg);
            if (reg) break;
        }
    } else {
        fprintf(stderr, "FATAL: ggml_backend_load not available\n");
        fclose(errf);
        return -1;
    }
    fflush(stderr);

    /* ── Load libllama-server-impl.so ── */
    void* h_srv = dlopen("libllama-server-impl.so", RTLD_NOW | RTLD_GLOBAL);
    fprintf(stderr, "dlopen(libllama-server-impl.so) = %p\n", h_srv);
    if (!h_srv) {
        fprintf(stderr, "ERROR: %s\n", dlerror());
        fclose(errf);
        return -2;
    }
    fflush(stderr);

    /* ── Find llama_server ── */
    typedef int (*llama_server_fn)(int, char**);
    llama_server_fn llama_server = (llama_server_fn)dlsym(h_srv, "_Z12llama_serveriPPc");
    if (!llama_server) {
        llama_server = (llama_server_fn)dlsym(h_srv, "llama_server");
    }
    fprintf(stderr, "llama_server = %p\n", (void*)llama_server);
    fflush(stderr);

    if (!llama_server) {
        fprintf(stderr, "FATAL: cannot find llama_server\n");
        fclose(errf);
        return -3;
    }

    /* ── Build argv and call ── */
    const char* cmodel = (*env)->GetStringUTFChars(env, modelPath, NULL);
    const char* chost  = (*env)->GetStringUTFChars(env, host, NULL);
    char port_str[16], ctx_str[16];
    snprintf(port_str, sizeof(port_str), "%d", (int)port);
    snprintf(ctx_str,  sizeof(ctx_str),  "%d", (int)ctxSize);

    char* argv[] = {
        (char*)"llama-server",
        (char*)"--model",   (char*)cmodel,
        (char*)"--host",    (char*)chost,
        (char*)"--port",    port_str,
        (char*)"--ctx-size", ctx_str,
        (char*)"--threads", (char*)"2",
        (char*)"--threads-batch", (char*)"2",
        (char*)"--no-mmap",
        (char*)"--verbose",
        NULL
    };
    int argc = (int)(sizeof(argv) / sizeof(argv[0])) - 1;

    fprintf(stderr, "Calling llama_server(argc=%d)...\n", argc);
    fflush(stderr);

    jint ret = (jint)llama_server(argc, argv);

    fprintf(stderr, "llama_server returned %d\n", (int)ret);
    fflush(stderr);
    fclose(errf);

    (*env)->ReleaseStringUTFChars(env, modelPath, cmodel);
    (*env)->ReleaseStringUTFChars(env, host,   chost);
    (*env)->ReleaseStringUTFChars(env, nativeLibDir, cLibDir);
    return ret;
}
