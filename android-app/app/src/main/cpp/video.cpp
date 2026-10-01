#include "video.h"

// ============ GL BLITTER STATE ============
static GLuint g_glProgram = 0;
static GLuint g_glVBO = 0;
static GLint g_glPositionLoc = -1;
static GLint g_glTexCoordLoc = -1;
static GLint g_glSamplerLoc = -1;
static EGLConfig g_eglConfig = nullptr;

static const float g_quadVertices[] = {
        // Pos      // Tex
        -1.0f, -1.0f, 0.0f, 0.0f,
        1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
        1.0f,  1.0f, 1.0f, 1.0f,
};

static const char *g_vShaderSrc = "attribute vec4 a_position;\n"
                                  "attribute vec2 a_texCoord;\n"
                                  "varying vec2 v_texCoord;\n"
                                  "void main() {\n"
                                  "    gl_Position = a_position;\n"
                                  "    v_texCoord = a_texCoord;\n"
                                  "}\n";

static const char *g_fShaderSrc =
        "precision mediump float;\n"
        "varying vec2 v_texCoord;\n"
        "uniform sampler2D s_texture;\n"
        "void main() {\n"
        "    vec4 texColor = texture2D(s_texture, v_texCoord);\n"
        "    gl_FragColor = vec4(texColor.rgb, 1.0);\n"
        "}\n";

static GLuint CompileShader(GLenum type, const char *src) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    return shader;
}

void InitBlitter() {
    if (g_glProgram != 0) return;

    GLuint vs = CompileShader(GL_VERTEX_SHADER, g_vShaderSrc);
    GLuint fs = CompileShader(GL_FRAGMENT_SHADER, g_fShaderSrc);

    g_glProgram = glCreateProgram();
    glAttachShader(g_glProgram, vs);
    glAttachShader(g_glProgram, fs);
    glLinkProgram(g_glProgram);

    glDeleteShader(vs);
    glDeleteShader(fs);

    g_glPositionLoc = glGetAttribLocation(g_glProgram, "a_position");
    g_glTexCoordLoc = glGetAttribLocation(g_glProgram, "a_texCoord");
    g_glSamplerLoc = glGetUniformLocation(g_glProgram, "s_texture");

    glGenBuffers(1, &g_glVBO);
    glBindBuffer(GL_ARRAY_BUFFER, g_glVBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(g_quadVertices), g_quadVertices, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    LOGI("GL Blitter Initialized. Program: %d", g_glProgram);
}

bool setupEGL() {
    std::unique_lock<std::mutex> lock(g_windowMutex);
    // Assume no context was created; only the full path below may set this.
    g_eglContextCreated = false;
    if (!g_nativeWindow)
        return false;

    if (g_eglDisplay != EGL_NO_DISPLAY && g_eglContext != EGL_NO_CONTEXT) {
        // An existing context is reused: only the window surface is rebuilt
        // (this is the rotation path). g_eglContextCreated stays false so the
        // caller does not signal context_reset for a context that never went
        // away.
        if (g_eglSurface == EGL_NO_SURFACE) {
            g_eglSurface = eglCreateWindowSurface(g_eglDisplay, g_eglConfig, g_nativeWindow, nullptr);
            if (g_eglSurface == EGL_NO_SURFACE)
                return false;
            if (!eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext))
                return false;
            eglSwapInterval(g_eglDisplay, 0);
            LOGI("EGL window surface recreated on existing GL context");
        }
        return true;
    }

    g_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_eglDisplay == EGL_NO_DISPLAY)
        return false;

    if (!eglInitialize(g_eglDisplay, nullptr, nullptr))
        return false;

    // CRITICAL: Force EGL to bind explicit OpenGLES API for Qualcomm/Adreno drivers
    eglBindAPI(EGL_OPENGL_ES_API);

    bool useGLES3 = (g_hwRender.version_major >= 3) ||
            (g_hwRender.context_type == RETRO_HW_CONTEXT_OPENGLES3) ||
            (g_hwRender.context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION);
    EGLint renderableType = useGLES3 ? EGL_OPENGL_ES3_BIT : EGL_OPENGL_ES2_BIT;

    EGLint depthSize = 24;
    EGLint stencilSize = g_hwRender.stencil ? 8 : 0;

    const EGLint attribs[] = {
            EGL_RENDERABLE_TYPE, renderableType,
            EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
            EGL_BLUE_SIZE,       8,
            EGL_GREEN_SIZE,      8,
            EGL_RED_SIZE,        8,
            EGL_ALPHA_SIZE,      0,
            EGL_DEPTH_SIZE,      depthSize,
            EGL_STENCIL_SIZE,    stencilSize,
            EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(g_eglDisplay, attribs, &config, 1, &numConfigs) || numConfigs == 0) {
        LOGE("eglChooseConfig failed");
        return false;
    }
    g_eglConfig = config;

    EGLint contextAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE
    };

    g_eglContext = eglCreateContext(g_eglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
    if (g_eglContext == EGL_NO_CONTEXT) {
        contextAttribs[1] = 2;
        g_eglContext = eglCreateContext(g_eglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
    }

    if (g_eglContext == EGL_NO_CONTEXT)
        return false;

    g_eglSurface = eglCreateWindowSurface(g_eglDisplay, config, g_nativeWindow, nullptr);
    if (g_eglSurface == EGL_NO_SURFACE)
        return false;

    if (!eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext))
        return false;

    eglSwapInterval(g_eglDisplay, 0);
    LOGI("EGL Initialized successfully");

    if (g_useHwRender) {
        InitBlitter();
    }

    // A brand new context exists, so the core must be told to (re)create its
    // GPU resources.
    g_eglContextCreated = true;
    return true;
}

void cleanupSurfaceEGL() {
    if (g_eglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_eglSurface != EGL_NO_SURFACE) {
            eglDestroySurface(g_eglDisplay, g_eglSurface);
            g_eglSurface = EGL_NO_SURFACE;
        }
    }
}

void deinitEGL() {
    std::lock_guard<std::mutex> lock(g_windowMutex);
    if (g_eglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_eglSurface != EGL_NO_SURFACE)
            eglDestroySurface(g_eglDisplay, g_eglSurface);
        if (g_eglContext != EGL_NO_CONTEXT)
            eglDestroyContext(g_eglDisplay, g_eglContext);
        eglTerminate(g_eglDisplay);
    }
    g_eglDisplay = EGL_NO_DISPLAY;
    g_eglContext = EGL_NO_CONTEXT;
    g_eglSurface = EGL_NO_SURFACE;
    g_eglConfig = nullptr;
    g_glProgram = 0;
}

void VideoRefreshCallback(const void *data, unsigned width, unsigned height, size_t pitch) {
    const uint64_t frameNumber = ++g_videoRefreshCount;
    if (frameNumber % 100 == 1) {
        LOGI("VIDEO: refresh #%llu data=%p size=%ux%u pitch=%zu hw=%d surface=%p",
                (unsigned long long)frameNumber, data, width, height, pitch,
                g_useHwRender ? 1 : 0, g_eglSurface);
    }

    const uintptr_t dataVal = (uintptr_t)data;
    const bool isRawCpuPointer = (dataVal > 0x10000 && data != RETRO_HW_FRAME_BUFFER_VALID);

    // 1. HARDWARE RENDER PATH
    if (g_useHwRender && !isRawCpuPointer) {
        std::lock_guard<std::mutex> lock(g_windowMutex);

        if (g_nativeWindow && width > 0 && height > 0) {
            if (width != g_prevWidth || height != g_prevHeight) {
                ANativeWindow_setBuffersGeometry(g_nativeWindow, width, height, WINDOW_FORMAT_RGBA_8888);
                g_prevWidth = width;
                g_prevHeight = height;
            }
        }

        if (g_eglDisplay != EGL_NO_DISPLAY && g_eglSurface != EGL_NO_SURFACE) {
            if (data != nullptr) {
                // CASE A: Texture ID blit required (Mupen64Plus / N64)
                if (data != RETRO_HW_FRAME_BUFFER_VALID && g_glProgram != 0) {
                    GLuint textureId = (GLuint)dataVal;

                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    glViewport(0, 0, width, height);

                    glDisable(GL_SCISSOR_TEST);
                    glDisable(GL_DEPTH_TEST);
                    glDisable(GL_STENCIL_TEST);
                    glDisable(GL_BLEND);
                    glDisable(GL_CULL_FACE);

                    glUseProgram(g_glProgram);
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, textureId);

                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

                    glUniform1i(g_glSamplerLoc, 0);

                    glBindBuffer(GL_ARRAY_BUFFER, g_glVBO);
                    glEnableVertexAttribArray(g_glPositionLoc);
                    glVertexAttribPointer(g_glPositionLoc, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void *)0);
                    glEnableVertexAttribArray(g_glTexCoordLoc);
                    glVertexAttribPointer(g_glTexCoordLoc, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void *)(2 * sizeof(float)));

                    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

                    glDisableVertexAttribArray(g_glPositionLoc);
                    glDisableVertexAttribArray(g_glTexCoordLoc);
                    glBindBuffer(GL_ARRAY_BUFFER, 0);
                    glBindTexture(GL_TEXTURE_2D, 0);
                }
                    // CASE B: Core rendered directly into native FBO (SwanStation / Dolphin / PCSX2)
                else if (data == RETRO_HW_FRAME_BUFFER_VALID) {
                    GLint currentFbo = 0;
                    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &currentFbo);
                    if (currentFbo != 0) {
                        glBindFramebuffer(GL_READ_FRAMEBUFFER, currentFbo);
                        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
                        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
                        glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    }
                }

                if (!eglSwapBuffers(g_eglDisplay, g_eglSurface)) {
                    EGLint err = eglGetError();
                    if (err == EGL_BAD_SURFACE || err == EGL_BAD_NATIVE_WINDOW) {
                        LOGW("eglSwapBuffers failed (0x%x), surface abandoned.", err);
                        eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                        eglDestroySurface(g_eglDisplay, g_eglSurface);
                        g_eglSurface = EGL_NO_SURFACE;
                        return;
                    }
                    LOGE("eglSwapBuffers failed: 0x%x", err);
                }
                if (g_saveStateRequested.load()) {
                    glFinish();
                }
            }
        }
        return;
    }

    // 2. SOFTWARE FALLBACK PATH
    if (!data)
        return;

    if (g_eglDisplay != EGL_NO_DISPLAY && g_eglSurface != EGL_NO_SURFACE) {
        cleanupSurfaceEGL();
    }

    ANativeWindow *window = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_windowMutex);
        window = g_nativeWindow;
    }

    if (!window)
        return;

    int format = g_pixelFormat.load();
    int32_t androidFormat = (format == RETRO_PIXEL_FORMAT_XRGB8888)
            ? WINDOW_FORMAT_RGBA_8888
            : WINDOW_FORMAT_RGB_565;

    if (width != g_prevWidth || height != g_prevHeight || androidFormat != g_prevFormat) {
        if (ANativeWindow_setBuffersGeometry(window, width, height, androidFormat) == 0) {
            g_prevWidth = width;
            g_prevHeight = height;
            g_prevFormat = androidFormat;
        }
    }

    ANativeWindow_Buffer buffer;
    int lockRes = ANativeWindow_lock(window, &buffer, nullptr);
    if (lockRes != 0) return;

    const bool swapDs = g_dsSwapScreens.load() && height > 1;
    auto srcRow = [&](unsigned y) -> unsigned {
        return swapDs ? (y < height / 2 ? y + height / 2 : y - height / 2) : y;
    };

    if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
        for (unsigned y = 0; y < height; y++) {
            const uint8_t *srcLine = (const uint8_t *)data + srcRow(y) * pitch;
            uint8_t *dstLine = (uint8_t *)buffer.bits + y * buffer.stride * 4;
            for (unsigned x = 0; x < width; x++) {
                dstLine[x * 4 + 0] = srcLine[x * 4 + 2]; // R
                dstLine[x * 4 + 1] = srcLine[x * 4 + 1]; // G
                dstLine[x * 4 + 2] = srcLine[x * 4 + 0]; // B
                dstLine[x * 4 + 3] = 0xFF;               // A
            }
        }
    } else if (format == RETRO_PIXEL_FORMAT_0RGB1555) {
        for (unsigned y = 0; y < height; y++) {
            const uint16_t *srcLine = (const uint16_t *)((const uint8_t *)data + srcRow(y) * pitch);
            uint16_t *dstLine = (uint16_t *)((uint8_t *)buffer.bits + y * buffer.stride * 2);
            for (unsigned x = 0; x < width; x++) {
                uint16_t pixel = srcLine[x];
                uint16_t r = (pixel >> 10) & 0x1F;
                uint16_t g = (pixel >> 5) & 0x1F;
                uint16_t b = pixel & 0x1F;
                dstLine[x] = (r << 11) | (g << 6) | b;
            }
        }
    } else {
        for (unsigned y = 0; y < height; y++) {
            const uint8_t *srcLine = (const uint8_t *)data + srcRow(y) * pitch;
            uint8_t *dstLine = (uint8_t *)buffer.bits + y * buffer.stride * 2;
            memcpy(dstLine, srcLine, width * 2);
        }
    }

    ANativeWindow_unlockAndPost(window);
}