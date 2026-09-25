#include "environment.h"
#include "vulkan_bridge.h"
#include <cctype>
#include <cstring>

uintptr_t GetCurrentFramebuffer() {
  return 0; // Default window framebuffer
}

retro_proc_address_t GetProcAddress(const char *sym) {
  return (retro_proc_address_t)eglGetProcAddress(sym);
}

namespace {

// True if a value string is a boolean-style "off" spelling. Used to pick the
// correct off value from the core's own option list rather than guessing.
bool IsOffValue(const char *v) {
  if (!v)
    return false;
  std::string s(v);
  for (char &c : s)
    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
  return s == "disabled" || s == "off" || s == "false" || s == "no" ||
         s == "0";
}

// Resolved "off" value for pcsx2_fastmem, discovered from the core's own
// option value list so we never force a string the core does not recognise.
// Empty until the core registers/describes its options.
std::string g_pcsx2FastmemValue;
// The exact Vulkan value string advertised by the core's renderer option,
// discovered at option-registration time (it only exists with ENABLE_VULKAN).
std::string g_pcsx2VulkanValue;

// Scan a retro_core_option_value[] for the Vulkan renderer value. Doubles as
// the ENABLE_VULKAN capability probe: no value => no Vulkan in this build.
void ResolveRendererFromValues(const struct retro_core_option_value *values,
                               const char *key) {
  if (!key || !values || std::string(key) != "pcsx2_renderer")
    return;
  for (unsigned i = 0; i < RETRO_NUM_CORE_OPTION_VALUES_MAX; ++i) {
    if (!values[i].value)
      break;
    std::string v(values[i].value);
    if (v.find("Vulkan") != std::string::npos &&
        v.find("paraLLEl") == std::string::npos) {
      g_pcsx2VulkanValue = v;
      vulkanSetCoreSupported(true);
      LOGI("PCSX2 renderer option advertises '%s'", v.c_str());
      return;
    }
  }
}

// Applies the renderer preference at option-registration time: Vulkan when
// requested AND advertised, otherwise the safe software framebuffer path.
void ApplyPcsx2Renderer() {
  auto it = g_coreVariables.find("pcsx2_renderer");
  if (it == g_coreVariables.end())
    return;
  if (vulkanRequested() && vulkanCoreSupportsVulkan() &&
      !g_pcsx2VulkanValue.empty()) {
    it->second = g_pcsx2VulkanValue;
    LOGI("PCSX2 renderer -> '%s' (user preference)", it->second.c_str());
  } else {
    it->second = "Software (SW)";
    LOGI("PCSX2 renderer forced to software framebuffer path");
  }
}

// Scan a NULL-terminated retro_core_option_value[] for an "off" spelling.
void ResolveFastmemFromValues(const struct retro_core_option_value *values,
                              const char *key) {
  if (!key || !values || std::string(key) != "pcsx2_fastmem")
    return;
  for (unsigned i = 0; i < RETRO_NUM_CORE_OPTION_VALUES_MAX; ++i) {
    if (!values[i].value)
      break;
    if (IsOffValue(values[i].value)) {
      g_pcsx2FastmemValue = values[i].value;
      LOGI("PCSX2 fastmem: core offers off value '%s'", values[i].value);
      return;
    }
  }
}

// Parse a legacy "Description; a|b|c" value string and find an off spelling.
void ResolveFastmemFromLegacy(const char *rawValue) {
  if (!rawValue)
    return;
  std::string v(rawValue);
  auto semi = v.find(';');
  std::string list = (semi == std::string::npos) ? std::string() : v.substr(semi + 1);
  size_t start = 0;
  while (!list.empty() && start <= list.size()) {
    auto bar = list.find('|', start);
    std::string token =
        list.substr(start, bar == std::string::npos ? std::string::npos
                                                    : bar - start);
    while (!token.empty() && (token.front() == ' ' || token.front() == '\t'))
      token.erase(token.begin());
    while (!token.empty() && (token.back() == ' ' || token.back() == '\t' ||
                              token.back() == '\r' || token.back() == '\n'))
      token.pop_back();
    if (IsOffValue(token.c_str())) {
      g_pcsx2FastmemValue = token;
      LOGI("PCSX2 fastmem: core offers off value '%s'", token.c_str());
      return;
    }
    if (bar == std::string::npos)
      break;
    start = bar + 1;
  }
}

} // namespace

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
  case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK:
  case RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK:
    // The frontend drives timing and audio from the emulation thread. These
    // optional callbacks are accepted so cores can continue normal startup.
    return data != nullptr;
  case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
    if (data) {
      LOGD("GET_CORE_OPTIONS_VERSION");
      *(unsigned *)data = 1;
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_SET_HW_RENDER: {
    if (!data)
      return false;
    auto pcsx2Renderer = g_coreVariables.find("pcsx2_renderer");
    if (pcsx2Renderer != g_coreVariables.end() &&
        pcsx2Renderer->second == "Software (SW)") {
      LOGI("SET_HW_RENDER rejected: PCSX2 software renderer uses framebuffer callbacks");
      return false;
    }

    struct retro_hw_render_callback *hw =
        (struct retro_hw_render_callback *)data;
    LOGI("SET_HW_RENDER called by core (cmd 14), type: %u", hw->context_type);

    if (hw->context_type == RETRO_HW_CONTEXT_VULKAN &&
        !vulkanAcceptHwRenderRequest()) {
      LOGW("SET_HW_RENDER: Vulkan unavailable (preference/core/window); "
           "core must fall back");
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

    // Vulkan: the full interface (instance/gpu/device/queue + callbacks) is
    // populated later in vulkanBeginNegotiation() once the core registers its
    // negotiation interface; GET_HW_RENDER_INTERFACE is answered only then.
    if (hw->context_type == RETRO_HW_CONTEXT_VULKAN) {
      g_useVulkan = true;
    }
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

    // Some cores (notably ParaLLEl N64) require a current context during
    // retro_load_game. Dolphin must not receive context_reset re-entrantly
    // from SET_HW_RENDER while it is still booting. Vulkan negotiates its own
    // context from the option handlers: EGL here would call context_reset
    // before the negotiation interface exists.
    if (!g_useVulkan && !g_isDolphinCore.load() &&
        std::this_thread::get_id() == g_emuThreadId && g_nativeWindow) {
      setupEGL();
    }
    return true;
  }
  case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: {
    if (!data)
      return false;
    const auto *interfaceInfo =
        static_cast<const retro_hw_render_context_negotiation_interface *>(data);
    LOGI("SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: type=%d version=%u",
         static_cast<int>(interfaceInfo->interface_type),
         interfaceInfo->interface_version);
    if (g_useVulkan && interfaceInfo->interface_type ==
                           RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) {
      // Full bring-up: instance, Android surface, device (via the core's
      // create_device2 + our wrapper), swapchain, present pipeline, and a
      // fully populated g_vulkanInterface for GET_HW_RENDER_INTERFACE.
      return vulkanBeginNegotiation(
          reinterpret_cast<const retro_hw_render_context_negotiation_interface_vulkan *>(
              interfaceInfo));
    }
    // Dolphin probes this while using GLES; acknowledge without enabling Vulkan.
    return true;
  }
  case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
    // A shared EGL context is not available: this frontend owns one context
    // on the emulation thread. Dolphin can fall back to synchronous shaders.
    LOGI("SET_HW_SHARED_CONTEXT: unavailable");
    return false;
  case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
    if (!data)
      return false;
    auto *interfaceInfo =
        static_cast<retro_hw_render_context_negotiation_interface *>(data);
    // Vulkan: support negotiation interface v2 (the version vendored in
    // libretro_vulkan.h). Any other type (Dolphin's GLES probe) gets
    // version zero = unsupported.
    if (g_useVulkan && interfaceInfo->interface_type ==
                           RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) {
      interfaceInfo->interface_version =
          RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN_VERSION;
      return true;
    }
    interfaceInfo->interface_version = 0;
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
  case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
    // Disc switching is not exposed by Arc. Reject the optional interface
    // explicitly rather than claiming support with an incomplete vtable.
    LOGI("SET_DISK_CONTROL_EXT_INTERFACE: unavailable");
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
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
    if (!data)
      return false;
    for (const auto *option =
             static_cast<const retro_core_option_definition *>(data);
         option->key != nullptr; ++option) {
      if (g_coreVariables.find(option->key) == g_coreVariables.end() &&
          option->default_value != nullptr) {
        g_coreVariables.emplace(option->key, option->default_value);
      }
      ResolveFastmemFromValues(option->values, option->key);
      ResolveRendererFromValues(option->values, option->key);
    }
    if (g_isDolphinCore.load()) {
      g_coreVariables["dolphin_shader_compilation_mode"] = "synchronous";
      g_coreVariables["dolphin_wait_for_shaders"] = "false";
    }
    ApplyPcsx2Renderer();
    // PCSX2's fastmem write-protects guest RAM pages so the recompiler can
    // trap and backpatch guest stores. retro_unserialize() restores the 32MB
    // EE main RAM with one large memmove from this thread, which faults on
    // those protected pages (SEGV_ACCERR inside the core's memcpy) and kills
    // the process mid state-load. Force the core's OWN "off" value (discovered
    // from its option list) to route memory accesses through the software
    // handlers: slightly slower, but savestate loads become safe.
    if (g_coreVariables.find("pcsx2_fastmem") != g_coreVariables.end()) {
      if (!g_pcsx2FastmemValue.empty()) {
        g_coreVariables["pcsx2_fastmem"] = g_pcsx2FastmemValue;
        LOGI("PCSX2 fastmem forced to '%s' for savestate-load safety",
             g_pcsx2FastmemValue.c_str());
      } else {
        LOGW("PCSX2 fastmem: core has not exposed an off value yet");
      }
    }
    return true;
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
    if (!data)
      return false;
    const auto *options =
        static_cast<const retro_core_options_v2 *>(data);
    if (options->definitions) {
      for (const auto *option = options->definitions; option->key != nullptr;
           ++option) {
        if (g_coreVariables.find(option->key) == g_coreVariables.end() &&
            option->default_value != nullptr) {
          g_coreVariables.emplace(option->key, option->default_value);
        }
        ResolveFastmemFromValues(option->values, option->key);
        ResolveRendererFromValues(option->values, option->key);
      }
    }
    if (g_isDolphinCore.load()) {
      g_coreVariables["dolphin_shader_compilation_mode"] = "synchronous";
      g_coreVariables["dolphin_wait_for_shaders"] = "false";
    }
    ApplyPcsx2Renderer();
    // See the SET_CORE_OPTIONS note: fastmem's write-protected guest RAM
    // faults the savestate-restore memmove. Force the core's own off value.
    if (g_coreVariables.find("pcsx2_fastmem") != g_coreVariables.end()) {
      if (!g_pcsx2FastmemValue.empty()) {
        g_coreVariables["pcsx2_fastmem"] = g_pcsx2FastmemValue;
        LOGI("PCSX2 fastmem forced to '%s' for savestate-load safety",
             g_pcsx2FastmemValue.c_str());
      } else {
        LOGW("PCSX2 fastmem: core has not exposed an off value yet");
      }
    }
    return true;
  }
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
    if (!data)
      return false;
    const auto *options =
        static_cast<const retro_core_options_intl *>(data);
    const auto *definitions = options->us ? options->us : options->local;
    if (definitions) {
      for (const auto *option = definitions; option->key != nullptr; ++option) {
        if (g_coreVariables.find(option->key) == g_coreVariables.end() &&
            option->default_value != nullptr) {
          g_coreVariables.emplace(option->key, option->default_value);
        }
      }
    }
    return true;
  }
  case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
    // Core options are available through the frontend's defaults even though
    // Arc does not currently expose a separate options visibility menu.
    return data != nullptr;
  case RETRO_ENVIRONMENT_SET_VARIABLES: {
    if (!data)
      return false;
    const struct retro_variable *received = (const struct retro_variable *)data;
    unsigned count = 0;
    while (received[count].key != nullptr) {
      std::string key(received[count].key);
      std::string value(received[count].value ? received[count].value : "");
      // Capability probe for the legacy "Renderer; a|b|c" format: the token
      // list contains "Vulkan" only when the core was built with ENABLE_VULKAN.
      if (key == "pcsx2_renderer" && value.find("Vulkan") != std::string::npos) {
        vulkanSetCoreSupported(true);
        if (g_pcsx2VulkanValue.empty())
          g_pcsx2VulkanValue = "Vulkan";
      }

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
      if (key == "pcsx2_fastmem") {
        ResolveFastmemFromLegacy(received[count].value);
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

    // Dolphin's background shader compiler needs a second shared EGL
    // context. This frontend owns one SurfaceView context, so compile on the
    // emulation thread instead of letting the worker fail during startup.
    if (g_isDolphinCore.load()) {
      g_coreVariables["dolphin_shader_compilation_mode"] = "synchronous";
      g_coreVariables["dolphin_wait_for_shaders"] = "false";
      g_coreVariables["dolphin_main_cpu_thread"] = "False";
      g_coreVariables["dolphin_fastmem"] = "Enabled";
      g_coreVariables["dolphin_cpu_core"] = "JIT ARM64";
    }
    ApplyPcsx2Renderer();
    // See the SET_CORE_OPTIONS note: fastmem's write-protected guest RAM
    // faults the savestate-restore memmove. Force the core's own off value.
    if (g_coreVariables.find("pcsx2_fastmem") != g_coreVariables.end()) {
      if (!g_pcsx2FastmemValue.empty()) {
        g_coreVariables["pcsx2_fastmem"] = g_pcsx2FastmemValue;
        LOGI("PCSX2 fastmem forced to '%s' for savestate-load safety",
             g_pcsx2FastmemValue.c_str());
      } else {
        LOGW("PCSX2 fastmem: core has not exposed an off value yet");
      }
    }
    return true;
  }
  case RETRO_ENVIRONMENT_GET_VARIABLE: {
    if (!data)
      return false;
    struct retro_variable *var = (struct retro_variable *)data;
    if (var->key) {
      std::string key(var->key);
      if (key != "pcsx2_renderer" && key != "pcsx2_fastmem" && key != "melonds_screen_layout" && key != "desmume_screens_layout" && key != "melonds_touch_mode") {
        if (g_coreVariables.find(key) != g_coreVariables.end()) {
          var->value = g_coreVariables[key].c_str();
          return true;
        }
      }
      if (std::string(var->key) == "pcsx2_renderer") {
        // Honour the preference: Vulkan only when requested AND the core
        // actually advertises a Vulkan value; anything else runs software.
        static std::string chosen;
        if (vulkanRequested() && vulkanCoreSupportsVulkan() &&
            !g_pcsx2VulkanValue.empty()) {
          chosen = g_pcsx2VulkanValue;
        } else {
          chosen = "Software (SW)";
        }
        LOGI("PCSX2 renderer option: %s", chosen.c_str());
        var->value = chosen.c_str();
        return true;
      }
      if (std::string(var->key) == "pcsx2_fastmem") {
        // The PCSX2 core's fastmem write-protects guest RAM pages so the EE
        // JIT can trap and backpatch stores. retro_unserialize() restores the
        // 32MB EE main RAM with one large memmove on this thread, which hits a
        // protected page unclaimed and dies with SIGSEGV (SEGV_ACCERR) mid
        // state-load. Return the core's OWN off value (discovered from its
        // option list) so fastmem stays off and savestate loads are safe.
        static const std::string fallback = "disabled";
        const std::string &off =
            g_pcsx2FastmemValue.empty() ? fallback : g_pcsx2FastmemValue;
        var->value = off.c_str();
        LOGI("PCSX2 fastmem option -> '%s'", var->value);
        return true;
      }
      static std::unordered_map<std::string, int> logCounts;
      int count = ++logCounts[var->key];
      if (count <= 1) {
        LOGD("GET_VARIABLE key: %s", var->key);
      }
      if (std::string(var->key) == "melonds_screen_layout" ||
          std::string(var->key) == "desmume_screens_layout") {
        // The FRONTEND owns the DS screen flip (see the row swap in
        // video.cpp / g_dsSwapScreens), because a core that also applies the
        // option would flip the same frame a second time and the two would
        // cancel - the user sees one flipped frame, then an instant snap back.
        // So the core is always told "Top/Bottom" and never asked to change it
        // mid-session; the frontend mirrors the halves instead and the touch
        // mapping is inverted to match (see setTouchInput).
        static const char *dsCoreLayout = "Top/Bottom";
        var->value = dsCoreLayout;
        return true;
      }
      // DS touch screen. melonDS only polls RETRO_DEVICE_POINTER while its
      // "Touch Mode" option is Touch; that option defaults to "Mouse", where
      // mouse X/Y are *relative* deltas, and the core silently falls back to
      // TouchMode::Disabled (no touch input at all) when the option cannot be
      // resolved. Android hands us an absolute touch surface, so force the
      // pointer path instead of letting the core pick its desktop default.
      if (std::string(var->key) == "melonds_touch_mode") {
        static const char *dsTouchMode = "Touch"; // "Mouse" | "Touch" | "Joystick"
        static bool loggedTouchMode = false;
        if (!loggedTouchMode) {
          LOGI("DS touch mode forced to '%s' (core default is Mouse)", dsTouchMode);
          loggedTouchMode = true;
        }
        var->value = dsTouchMode;
        return true;
      }
      // melonDS's ARM JIT keeps generated code in pages whose protection the
      // core toggles at runtime. When the DS wireless path wedges (Mario Kart
      // DS -> Multiplayer is the known trigger), the corruption lands in one
      // of those pages: the next core call then dies with SEGV_ACCERR inside
      // the core .so and takes the whole app with it (the 00:29 log: "CORE:
      // executing reset on emulation thread", SIGSEGV 4 ms later, #01/#02 =
      // melonds .so). Disabling the JIT removes that failure mode entirely;
      // the interpreter is slower but far harder to wedge. Lemuroid never hits
      // this because its DS core is DeSmuME, which has no recompiler at all.
      if (std::string(var->key) == "melonds_jit_enable" ||
          std::string(var->key) == "jit_enable") {
        static const char *dsJitOff = "disabled";
        var->value = dsJitOff;
        return true;
      }
      // Give the emulated DS a real identity. An empty frontend username can
      // leave the generated firmware header blank, and MKDS reads the WIFI
      // calibration plus the console nickname out of that header when it opens
      // multiplayer.
      if (std::string(var->key) == "melonds_username") {
        static const char *dsUsername = "Player";
        var->value = dsUsername;
        return true;
      }
      // Same story for DeSmuME, which must be told to use the stylus/pointer
      // instead of its mouse pointer default.
      if (std::string(var->key) == "desmume_pointer_type") {
        static const char *dsPointerType = "touch";
        var->value = dsPointerType;
        return true;
      }
      auto found = g_coreVariables.find(std::string(var->key));
      if (found != g_coreVariables.end()) {
        if (std::string(var->key) == "pcsx2_bios")
          LOGI("PCSX2 BIOS option: %s", found->second.c_str());
        var->value = found->second.c_str();
        return true;
      }
      if (std::string(var->key) == "pcsx2_bios") {
        // PCSX2 uses "Auto" to scan the system BIOS directory. A filename is
        // not a valid value for this option.
        static const char *pcsx2BiosAuto = "Auto";
        LOGI("PCSX2 BIOS option: %s", pcsx2BiosAuto);
        var->value = pcsx2BiosAuto;
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
      return true;
    }
    return false;
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
  case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
    if (data) {
      *(bool *)data = g_fastForward.load();
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
      // POINTER is advertised too: the touch surface is exposed through
      // RETRO_DEVICE_POINTER, and cores consult this bit to auto-detect a
      // touch-capable frontend before choosing touch over mouse input.
      *(uint64_t *)data = (1ULL << RETRO_DEVICE_JOYPAD) |
                          (1ULL << RETRO_DEVICE_ANALOG) |
                          (1ULL << RETRO_DEVICE_POINTER);
      return true;
    }
    return false;
  case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
    // Vulkan: only ever answered once the backend is fully built, so the core
    // can never dereference an empty interface.
    if (g_useVulkan && vulkanInterface()) {
      *reinterpret_cast<void **>(data) = vulkanInterface();
      return true;
    }
    return false;
  default:
    // Do not log unsupported high-frequency probes on every frame. Dolphin
    // queries GET_FASTFORWARDING (65585) continuously while running.
    static unsigned lastUnhandledCmd = 0;
    if (cmd != lastUnhandledCmd) {
      LOGD("EnvironmentCallback Unhandled cmd: %u", cmd);
      lastUnhandledCmd = cmd;
    }
    return false;
  }
}
