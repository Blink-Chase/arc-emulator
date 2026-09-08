#include "video.h"

// ============ GL BLITTER STATE ============
static GLuint g_glProgram = 0;
static GLuint g_glVBO = 0;
static GLint g_glPositionLoc = -1;
static GLint g_glTexCoordLoc = -1;
static GLint g_glSamplerLoc = -1;

static const float g_quadVertices[] = {
    // Pos      // Tex
    -1.0f, -1.0f, 0.0f, 0.0f, 1.0f, -1.0f, 1.0f, 0.0f,
    -1.0f, 1.0f,  0.0f, 1.0f, 1.0f, 1.0f,  1.0f, 1.0f,
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
  glBufferData(GL_ARRAY_BUFFER, sizeof(g_quadVertices), g_quadVertices,
               GL_STATIC_DRAW);
  glBindBuffer(GL_ARRAY_BUFFER, 0);

  LOGI("GL Blitter Initialized. Program: %d", g_glProgram);
}

bool setupEGL() {
  std::lock_guard<std::mutex> lock(g_windowMutex);
  if (!g_nativeWindow)
    return false;

  if (g_eglDisplay != EGL_NO_DISPLAY) {
    return true; // Already initialized
  }

  g_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (g_eglDisplay == EGL_NO_DISPLAY)
    return false;

  if (!eglInitialize(g_eglDisplay, nullptr, nullptr))
    return false;

  bool useGLES3 = (g_hwRender.version_major >= 3) ||
                  (g_hwRender.context_type == RETRO_HW_CONTEXT_OPENGLES3);
  EGLint renderableType = useGLES3 ? EGL_OPENGL_ES3_BIT : EGL_OPENGL_ES2_BIT;

  EGLint depthSize = 24;
  EGLint stencilSize = g_hwRender.stencil ? 8 : 0;

  const EGLint attribs[] = {EGL_RENDERABLE_TYPE,
                            renderableType,
                            EGL_SURFACE_TYPE,
                            EGL_WINDOW_BIT,
                            EGL_BLUE_SIZE,
                            8,
                            EGL_GREEN_SIZE,
                            8,
                            EGL_RED_SIZE,
                            8,
                            EGL_ALPHA_SIZE,
                            0, // Force opaque surface to prevent broken screenshots
                            EGL_DEPTH_SIZE,
                            depthSize,
                            EGL_STENCIL_SIZE,
                            stencilSize,
                            EGL_NONE};

  EGLConfig config;
  EGLint numConfigs;
  if (!eglChooseConfig(g_eglDisplay, attribs, &config, 1, &numConfigs) ||
      numConfigs == 0) {
    LOGE("eglChooseConfig failed");
    return false;
  }

  EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION,
                             (EGLint)(useGLES3 ? 3 : 2), EGL_NONE};
  g_eglContext =
      eglCreateContext(g_eglDisplay, config, EGL_NO_CONTEXT, contextAttribs);
  if (g_eglContext == EGL_NO_CONTEXT)
    return false;

  g_eglSurface =
      eglCreateWindowSurface(g_eglDisplay, config, g_nativeWindow, nullptr);
  if (g_eglSurface == EGL_NO_SURFACE)
    return false;

  if (!eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext))
    return false;

  LOGI("EGL Initialized successfully (GLES %d)", useGLES3 ? 3 : 2);

  if (g_useHwRender) {
    if (g_glProgram == 0) {
      InitBlitter();
    }
    if (g_hwRender.context_reset) {
      LOGI("VIDEO: Calling core context_reset");
      g_hwRender.context_reset();
    }
  }

  return true;
}

void cleanupSurfaceEGL() {
  // We don't lock here because this is called from JNI with g_windowMutex
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
    eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE,
                   EGL_NO_CONTEXT);
    if (g_eglSurface != EGL_NO_SURFACE)
      eglDestroySurface(g_eglDisplay, g_eglSurface);
    if (g_eglContext != EGL_NO_CONTEXT)
      eglDestroyContext(g_eglDisplay, g_eglContext);
    eglTerminate(g_eglDisplay);
  }
  g_eglDisplay = EGL_NO_DISPLAY;
  g_eglContext = EGL_NO_CONTEXT;
  g_eglSurface = EGL_NO_SURFACE;
  g_glProgram = 0;
}

void VideoRefreshCallback(const void *data, unsigned width, unsigned height,
                          size_t pitch) {
  g_videoRefreshCount++;

  if (g_useHwRender) {
    std::lock_guard<std::mutex> lock(g_windowMutex);

    // Scale up the hardware buffer geometry so that the SurfaceView natively
    // stretches it
    if (g_nativeWindow && width > 0 && height > 0) {
      if (width != g_prevWidth || height != g_prevHeight) {
        ANativeWindow_setBuffersGeometry(g_nativeWindow, width, height,
                                         WINDOW_FORMAT_RGBA_8888);
        g_prevWidth = width;
        g_prevHeight = height;
      }
    }

    if (g_eglDisplay != EGL_NO_DISPLAY && g_eglSurface != EGL_NO_SURFACE) {
      if (data != nullptr) {
        if (data != RETRO_HW_FRAME_BUFFER_VALID && g_glProgram != 0) {
          GLuint textureId = (GLuint)(uintptr_t)data;

          // Use the game resolution for viewport to match the buffer geometry
          glViewport(0, 0, width, height);

          // ISOLATE BLITTER STATE
          // GoldenEye sets a small scissor box for HUD elements; we MUST disable it to capture the whole screen.
          glDisable(GL_SCISSOR_TEST);
          glDisable(GL_DEPTH_TEST);
          glDisable(GL_STENCIL_TEST);
          glDisable(GL_BLEND);
          glDisable(GL_CULL_FACE);

          glUseProgram(g_glProgram);
          glActiveTexture(GL_TEXTURE0);
          glBindTexture(GL_TEXTURE_2D, textureId);

          // Better texture filtering for N64 resolutions
          glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
          glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
          glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
          glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

          glUniform1i(g_glSamplerLoc, 0);

          glBindBuffer(GL_ARRAY_BUFFER, g_glVBO);
          glEnableVertexAttribArray(g_glPositionLoc);
          glVertexAttribPointer(g_glPositionLoc, 2, GL_FLOAT, GL_FALSE,
                                4 * sizeof(float), (void *)0);
          glEnableVertexAttribArray(g_glTexCoordLoc);
          glVertexAttribPointer(g_glTexCoordLoc, 2, GL_FLOAT, GL_FALSE,
                                4 * sizeof(float), (void *)(2 * sizeof(float)));

          glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

          glDisableVertexAttribArray(g_glPositionLoc);
          glDisableVertexAttribArray(g_glTexCoordLoc);
          glBindBuffer(GL_ARRAY_BUFFER, 0);
          glBindTexture(GL_TEXTURE_2D, 0);
        }

        if (!eglSwapBuffers(g_eglDisplay, g_eglSurface)) {
          EGLint err = eglGetError();
          if (err == EGL_BAD_SURFACE || err == EGL_BAD_NATIVE_WINDOW) {
              LOGW("eglSwapBuffers failed (0x%x), surface abandoned. Clearing.", err);
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

  if (!data)
    return;

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

  if (width != g_prevWidth || height != g_prevHeight ||
      androidFormat != g_prevFormat) {
    if (ANativeWindow_setBuffersGeometry(window, width, height,
                                         androidFormat) == 0) {
      g_prevWidth = width;
      g_prevHeight = height;
      g_prevFormat = androidFormat;
    }
  }

  ANativeWindow_Buffer buffer;
  if (ANativeWindow_lock(window, &buffer, nullptr) != 0)
    return;

  if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
    for (unsigned y = 0; y < height; y++) {
      const uint8_t *srcLine = (const uint8_t *)data + y * pitch;
      uint8_t *dstLine = (uint8_t *)buffer.bits + y * buffer.stride * 4;
      for (unsigned x = 0; x < width; x++) {
        dstLine[x * 4 + 0] = srcLine[x * 4 + 2]; // R
        dstLine[x * 4 + 1] = srcLine[x * 4 + 1]; // G
        dstLine[x * 4 + 2] = srcLine[x * 4 + 0]; // B
        dstLine[x * 4 + 3] = 0xFF;               // A
      }
    }
  } else {
    for (unsigned y = 0; y < height; y++) {
      const uint8_t *srcLine = (const uint8_t *)data + y * pitch;
      uint8_t *dstLine = (uint8_t *)buffer.bits + y * buffer.stride * 2;
      memcpy(dstLine, srcLine, width * 2);
    }
  }

  ANativeWindow_unlockAndPost(window);
}
