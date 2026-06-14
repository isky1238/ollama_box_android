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
    jint threadCount, jstring nativeLibDir,
    jstring stderrPath, jstring chatTemplateKwargs, jint timeoutSec,
    jboolean useMmap, jint nGpuLayers) {

    const char* cLibDir = NULL;
    const char* cErrPath = NULL;
    const char* cmodel = NULL;
    const char* chost  = NULL;
    const char* cChatKwargs = NULL;
    jint ret = 0;
    FILE* errf = NULL;
    char port_str[16], ctx_str[16], thread_str[16], timeout_str[16], gpu_layers_str[16];

    /* ── Build reusable string buffers early (safe on all paths) ── */
    snprintf(port_str, sizeof(port_str), "%d", (int)port);
    snprintf(ctx_str,  sizeof(ctx_str),  "%d", (int)ctxSize);
    snprintf(thread_str, sizeof(thread_str), "%d", (int)threadCount);
    snprintf(timeout_str, sizeof(timeout_str), "%d", (int)timeoutSec);
    snprintf(gpu_layers_str, sizeof(gpu_layers_str), "%d", (int)nGpuLayers);

    cLibDir = (*env)->GetStringUTFChars(env, nativeLibDir, NULL);
    cErrPath = (*env)->GetStringUTFChars(env, stderrPath, NULL);
    if (!cLibDir || !cErrPath) {
        if (cLibDir) (*env)->ReleaseStringUTFChars(env, nativeLibDir, cLibDir);
        return -5;
    }

    errf = freopen(cErrPath, "w", stderr);
    fprintf(stderr, "=== JNI bridge v2.0 ===\nlib dir: %s\n", cLibDir);
    fprintf(stderr, "timeout=%d chat_kwargs=%s\n", (int)timeoutSec,
        chatTemplateKwargs ? "present" : "null");
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
            ret = -2;
            goto cleanup;
        }
    }
    fflush(stderr);

    /* ── Load requested backends via ggml_backend_load ── */
    void* h_ggml = libs[1].handle; /* libggml.so */
    void* (*backend_load)(const char*) = NULL;
    size_t (*backend_reg_count)(void) = NULL;
    void* (*backend_reg_get)(size_t) = NULL;
    const char* (*backend_reg_name)(void*) = NULL;
    size_t (*backend_dev_count)(void) = NULL;
    void* (*backend_dev_get)(size_t) = NULL;
    const char* (*backend_dev_name)(void*) = NULL;
    if (h_ggml) {
        backend_load = (void* (*)(const char*))dlsym(h_ggml, "ggml_backend_load");
        backend_reg_count = (size_t (*)(void))dlsym(h_ggml, "ggml_backend_reg_count");
        backend_reg_get = (void* (*)(size_t))dlsym(h_ggml, "ggml_backend_reg_get");
        backend_reg_name = (const char* (*)(void*))dlsym(h_ggml, "ggml_backend_reg_name");
        backend_dev_count = (size_t (*)(void))dlsym(h_ggml, "ggml_backend_dev_count");
        backend_dev_get = (void* (*)(size_t))dlsym(h_ggml, "ggml_backend_dev_get");
        backend_dev_name = (const char* (*)(void*))dlsym(h_ggml, "ggml_backend_dev_name");
        fprintf(stderr, "dlsym(libggml, ggml_backend_load) = %p\n", (void*)backend_load);
    }

    if (backend_load) {
        if (nGpuLayers > 0) {
            void* vulkan_reg = backend_load("libggml-vulkan.so");
            fprintf(stderr, "backend_load(libggml-vulkan.so) = %p\n", vulkan_reg);
            if (!vulkan_reg) {
                fprintf(stderr, "FATAL: Vulkan GPU backend requested but could not be loaded\n");
                ret = -6;
                goto cleanup;
            }
        }

        int backend_loaded = 0;
        const char* backends[] = {
            "libggml-cpu-android_armv9.2_2.so",
            "libggml-cpu-android_armv9.2_1.so",
            "libggml-cpu-android_armv9.0_1.so",
            "libggml-cpu-android_armv8.6_2.so",
            "libggml-cpu-android_armv8.6_1.so",
            "libggml-cpu-android_armv8.2_3.so",
            "libggml-cpu-android_armv8.2_2.so",
            "libggml-cpu-android_armv8.2_1.so",
            "libggml-cpu-android_armv8.0_1.so",
            NULL
        };
        for (int i = 0; backends[i]; i++) {
            void* reg = backend_load(backends[i]);
            fprintf(stderr, "backend_load(%s) = %p\n", backends[i], reg);
            if (reg) {
                backend_loaded = 1;
                break;
            }
        }
        if (!backend_loaded) {
            fprintf(stderr, "FATAL: no compatible CPU backend loaded\n");
            ret = -1;
            goto cleanup;
        }
    } else {
        fprintf(stderr, "FATAL: ggml_backend_load not available\n");
        ret = -1;
        goto cleanup;
    }

    if (backend_reg_count && backend_reg_get && backend_reg_name) {
        size_t count = backend_reg_count();
        fprintf(stderr, "registered backends (%zu):", count);
        for (size_t i = 0; i < count; i++) {
            fprintf(stderr, " %s", backend_reg_name(backend_reg_get(i)));
        }
        fprintf(stderr, "\n");
    }
    if (backend_dev_count && backend_dev_get && backend_dev_name) {
        size_t count = backend_dev_count();
        fprintf(stderr, "registered devices (%zu):", count);
        for (size_t i = 0; i < count; i++) {
            fprintf(stderr, " %s", backend_dev_name(backend_dev_get(i)));
        }
        fprintf(stderr, "\n");
    }
    fflush(stderr);

    /* ── Load libllama-server-impl.so ── */
    void* h_srv = dlopen("libllama-server-impl.so", RTLD_NOW | RTLD_GLOBAL);
    fprintf(stderr, "dlopen(libllama-server-impl.so) = %p\n", h_srv);
    if (!h_srv) {
        fprintf(stderr, "ERROR: %s\n", dlerror());
        ret = -2;
        goto cleanup;
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
        ret = -3;
        goto cleanup;
    }

    /* ── Get Java strings (only on success path) ── */
    cmodel = (*env)->GetStringUTFChars(env, modelPath, NULL);
    chost  = (*env)->GetStringUTFChars(env, host, NULL);
    if (chatTemplateKwargs) {
        cChatKwargs = (*env)->GetStringUTFChars(env, chatTemplateKwargs, NULL);
    }
    if (!cmodel || !chost || (chatTemplateKwargs && !cChatKwargs)) {
        ret = -5;
        goto release_strings;
    }

    /* ── Build argv: server config + timeout + chat template kwargs ── */
    {
        /* Dynamic argv to handle optional --chat-template-kwargs */
        int max_argc = 32, argc = 0;
        const char** argv = (const char**)calloc(max_argc, sizeof(char*));
        if (!argv) { ret = -4; goto release_strings; }

        argv[argc++] = "llama-server";
        argv[argc++] = "--model";  argv[argc++] = cmodel;
        argv[argc++] = "--host";   argv[argc++] = chost;
        argv[argc++] = "--port";   argv[argc++] = port_str;
        argv[argc++] = "--ctx-size"; argv[argc++] = ctx_str;
        argv[argc++] = "--threads";  argv[argc++] = thread_str;
        argv[argc++] = "--threads-batch"; argv[argc++] = thread_str;
        argv[argc++] = "--parallel";     argv[argc++] = "1";
        argv[argc++] = "--ubatch-size";  argv[argc++] = "256";

        /* GPU layer offload: 0 = CPU only, N > 0 = offload N layers to GPU.
         * The Vulkan backend was explicitly registered above. */
        if (nGpuLayers > 0) {
            argv[argc++] = "--n-gpu-layers";
            argv[argc++] = gpu_layers_str;
        }

        /* Disable mmap only when user explicitly wants preloaded RAM.
         * mmap loads the model near-instantly; --no-mmap reads the
         * entire GGUF into private memory which can take 30-80 s on
         * a 1-2 GB file under memory pressure. */
        if (!useMmap) {
            argv[argc++] = "--no-mmap";
        }

        /* HTTP read/write timeout used by current llama.cpp server. */
        argv[argc++] = "--timeout";
        argv[argc++] = timeout_str;

        /* Chat template kwargs: control thinking mode */
        if (cChatKwargs && cChatKwargs[0] != '\0') {
            argv[argc++] = "--chat-template-kwargs";
            argv[argc++] = cChatKwargs;
        }

        argv[argc] = NULL;

        fprintf(stderr, "Calling llama_server(argc=%d)...\n", argc);
        for (int i = 0; i < argc; i++) {
            fprintf(stderr, "  argv[%d] = %s\n", i, argv[i]);
        }
        fflush(stderr);

        ret = (jint)llama_server(argc, (char**)argv);

        fprintf(stderr, "llama_server returned %d\n", (int)ret);
        fflush(stderr);

        free(argv);
    }

release_strings:
    /* Release model/host/chat-template strings after use */
    if (cmodel) (*env)->ReleaseStringUTFChars(env, modelPath, cmodel);
    if (chost)  (*env)->ReleaseStringUTFChars(env, host,   chost);
    if (cChatKwargs) (*env)->ReleaseStringUTFChars(env, chatTemplateKwargs, cChatKwargs);

cleanup:
    if (errf) fflush(errf);
    (*env)->ReleaseStringUTFChars(env, stderrPath, cErrPath);
    (*env)->ReleaseStringUTFChars(env, nativeLibDir, cLibDir);
    return ret;
}

JNIEXPORT jboolean JNICALL
Java_com_ollamabox_ServerBridge_nativeStopServer(JNIEnv* env, jobject thiz) {
    typedef void (*shutdown_fn)(void);
    void* h_srv = dlopen("libllama-server-impl.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h_srv) {
        return JNI_FALSE;
    }
    shutdown_fn request_shutdown = (shutdown_fn)dlsym(h_srv, "ollamabox_server_request_shutdown");
    if (!request_shutdown) {
        return JNI_FALSE;
    }
    request_shutdown();
    return JNI_TRUE;
}
