#include "environment.h"

uintptr_t GetCurrentFramebuffer() {
  return 0; // Default window framebuffer
}

retro_proc_address_t GetProcAddress(const char *sym) {
  return (retro_proc_address_t)eglGetProcAddress(sym);
}

bool EnvironmentCallback(unsigned cmd, void *data) {
  // Move logging into switch to avoid spamming high-frequency commands (like
  // GET_VARIABLE_UPDATE)

  switch (cmd) {
  case RETRO_ENVIRONMENT_SHUTDOWN:
    LOGD("SHUTDOWN");
    return true;
  case RETRO_ENVIRONMENT_SET_GEOMETRY: {
    if (!data)
      return false;
    struct retro_game_geometry *geom = (struct retro_game_geometry *)data;
    static unsigned last_w = 0, last_h = 0;
    if (geom->base_width != last_w || geom->base_height != last_h) {
      LOGI("SET_GEOMETRY called: %dx%d", geom->base_width, geom->base_height);
      last_w = geom->base_width;
      last_h = geom->base_height;
    }
    return true;
  }
  case RETRO_ENVIRONMENT_GET_OVERSCAN:
    if (data) {
      LOGD("GET_OVERSCAN");
      *(bool *)data = false;
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_CAN_DUPE:
    if (data) {
      *(bool *)data = false; // Disable frame dupe for now to force rendering
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
    if (data) {
      LOGD("SET_PIXEL_FORMAT: %d", *(int *)data);
      g_pixelFormat.store(*(int *)data);
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    if (data && !g_systemDir.empty()) {
      *(const char **)data = g_systemDir.c_str();
      LOGD("GET_SYSTEM_DIRECTORY: %s", g_systemDir.c_str());
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
    if (data && !g_saveDir.empty()) {
      *(const char **)data = g_saveDir.c_str();
      LOGD("GET_SAVE_DIRECTORY: %s", g_saveDir.c_str());
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
    if (data && !g_systemDir.empty()) {
      *(const char **)data = g_systemDir.c_str();
      LOGD("GET_CORE_ASSETS_DIRECTORY: %s", g_systemDir.c_str());
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_USERNAME:
    if (data) {
      LOGD("GET_USERNAME");
      *(const char **)data = "ArcUser";
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_LANGUAGE:
    if (data) {
      LOGD("GET_LANGUAGE");
      *(unsigned *)data = 0; // RETRO_LANGUAGE_ENGLISH
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
    LOGD("GET_LOG_INTERFACE");
    if (data) {
      struct retro_log_callback *log_cb = (struct retro_log_callback *)data;
      log_cb->log = LogCallback;
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
    if (data) {
      LOGD("GET_CORE_OPTIONS_VERSION");
      *(unsigned *)data = 1;
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_SET_HW_RENDER: {
    struct retro_hw_render_callback *hw =
        (struct retro_hw_render_callback *)data;
    LOGI("SET_HW_RENDER called by core (cmd 14), type: %u", hw->context_type);
    if (!data)
      return false;

    if (hw->context_type == RETRO_HW_CONTEXT_VULKAN) {
      LOGW("SET_HW_RENDER: Core requested Vulkan. Rejecting to force GLES "
           "fallback.");
      return false;
    }

    // Explicitly copy fields to avoid any struct assignment padding issues
    g_hwRender.context_type = hw->context_type;
    g_hwRender.context_reset = hw->context_reset;
    g_hwRender.get_current_framebuffer = hw->get_current_framebuffer;
    g_hwRender.get_proc_address = hw->get_proc_address;
    g_hwRender.depth = hw->depth;
    g_hwRender.stencil = hw->stencil;
    g_hwRender.bottom_left_origin = hw->bottom_left_origin;
    g_hwRender.version_major = hw->version_major;
    g_hwRender.version_minor = hw->version_minor;
    g_hwRender.cache_context = hw->cache_context;
    g_hwRender.context_destroy = hw->context_destroy;
    g_hwRender.debug_context = hw->debug_context;

    g_useHwRender = true;

    // Provide our frontend functions back to the core
    hw->get_proc_address = GetProcAddress;
    hw->get_current_framebuffer = GetCurrentFramebuffer;

    // Save these in our global state too
    g_hwRender.get_proc_address = hw->get_proc_address;
    g_hwRender.get_current_framebuffer = hw->get_current_framebuffer;

    // Force GLES3 for N64 to unlock better features
    if (g_hwRender.version_major < 3) {
      g_hwRender.version_major = 3;
      g_hwRender.version_minor = 0;
    }

    // CRITICAL for ParaLLEl N64: The context MUST be current during LoadGame
    if (std::this_thread::get_id() == g_emuThreadId && g_nativeWindow) {
      setupEGL();
    }
    return true;
  }
  case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
  case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
  case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
  case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    return true;
  case RETRO_ENVIRONMENT_GET_PERF_INTERFACE:
  case RETRO_ENVIRONMENT_GET_SENSOR_INTERFACE:
  case RETRO_ENVIRONMENT_GET_CAMERA_INTERFACE:
    return false;
  case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
    if (data) {
      *(uint64_t *)data = 0;
      return true;
    }
    return false;
  /*
  case RETRO_ENVIRONMENT_GET_JNI_ENV: {
    JNIEnv *env = GetJNIEnv();
    if (env && data) {
      *(JNIEnv **)data = env;
      return true;
    }
    return false;
  }
  */
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
  case RETRO_ENVIRONMENT_SET_VARIABLES: {
    if (!data)
      return false;
    const struct retro_variable *received = (const struct retro_variable *)data;
    unsigned count = 0;
    while (received[count].key != nullptr) {
      std::string key(received[count].key);
      std::string value(received[count].value ? received[count].value : "");

      // Value string is formatted like: "Description;
      // default_value|other_value|etc" We need to extract the default_value
      auto firstValueStart = value.find(';');
      if (firstValueStart != std::string::npos) {
        firstValueStart += 2; // Skip "; "
        auto firstValueEnd = value.find('|', firstValueStart);
        if (firstValueEnd == std::string::npos)
          firstValueEnd = value.length();
        value = value.substr(firstValueStart, firstValueEnd - firstValueStart);
      }

      // Only set if not already overridden
      if (g_coreVariables.find(key) == g_coreVariables.end()) {
        g_coreVariables[key] = value;
      }

      count++;
    }

    // Apply our specific N64 overrides for both Parallel and Mupen64Plus-Next
    // Optimized for mobile stability and GoldenEye compatibility

    // Parallel N64 Overrides
    // Optimized for Snapdragon 8 Gen 5 Stability
    g_coreVariables["parallel-n64-gfxplugin"] = "gliden64";
    g_coreVariables["parallel-n64-rspplugin"] = "hle";
    g_coreVariables["parallel-n64-cpucore"] = "cached_interpreter";
    g_coreVariables["parallel-n64-ExpansionPak"] = "enabled";
    g_coreVariables["parallel-n64-parallel-rdp-synchronous"] = "false";
    g_coreVariables["parallel-n64-screensize"] = "640x480";
    g_coreVariables["parallel-n64-frameduping"] = "True";
    g_coreVariables["parallel-n64-CountPerOp"] = "1";
    g_coreVariables["parallel-n64-virefresh"] = "1500";
    g_coreVariables["parallel-n64-native-texture-lod"] = "True";
    g_coreVariables["parallel-n64-native-tex-rect"] = "True";
    // CRITICAL FIX FOR GOLDENEYE: Enable Framebuffer emulation specifically for Parallel
    g_coreVariables["parallel-n64-EnableFBEmulation"] = "True";
    g_coreVariables["parallel-n64-ThreadedVideo"] = "True";

    // Mupen64Plus-Next Overrides (GLideN64 specific fixes for DK64 and PD)
    g_coreVariables["mupen64plus-cpucore"] = "cached_interpreter";
    g_coreVariables["mupen64plus-rsp-hle"] = "enabled";
    g_coreVariables["mupen64plus-ExpansionPak"] = "enabled";
    g_coreVariables["mupen64plus-CountPerOp"] = "1";
    g_coreVariables["mupen64plus-VideoPlugin"] = "GLideN64";
    g_coreVariables["mupen64plus-rdp-plugin"] = "gliden64";
    g_coreVariables["mupen64plus-EnableFBEmulation"] = "True";
    g_coreVariables["mupen64plus-EnableNativeResTexRects"] = "True";
    g_coreVariables["mupen64plus-ThreadedVideo"] = "True";

    return true;
  }
  case RETRO_ENVIRONMENT_GET_VARIABLE: {
    if (!data)
      return false;
    struct retro_variable *var = (struct retro_variable *)data;
    if (var->key) {
      static std::unordered_map<std::string, int> logCounts;
      int count = ++logCounts[var->key];
      if (count <= 1) {
        LOGD("GET_VARIABLE key: %s", var->key);
      }
      auto found = g_coreVariables.find(std::string(var->key));
      if (found != g_coreVariables.end()) {
        var->value = found->second.c_str();
        return true;
      }
    }
    var->value = nullptr;
    return false;
  }
  case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
    if (data) {
      *(bool *)data = g_variablesUpdated.load();
      if (*(bool *)data)
        g_variablesUpdated.store(false);
    }
  /*
  case RETRO_ENVIRONMENT_GET_PLATFORM_TYPE:
    if (data) {
      *(unsigned *)data = 2; // RETRO_PLATFORM_ANDROID
      return true;
    }
    return false;
  */
  case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
    if (data) {
      struct retro_rumble_interface *rumble =
          (struct retro_rumble_interface *)data;
      rumble->set_rumble_state = set_rumble_state;
      return true;
    }
    return true;
  case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
    if (data) {
      *(float *)data = 60.0f;
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
    if (data) {
      *(unsigned *)data = RETRO_HW_CONTEXT_OPENGLES3;
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES:
    if (data) {
      *(uint64_t *)data =
          (1ULL << RETRO_DEVICE_JOYPAD) | (1ULL << RETRO_DEVICE_ANALOG);
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
    return false; // Not implemented yet
  default:
    LOGD("EnvironmentCallback Unhandled cmd: %u", cmd);
    return false;
  }
}
