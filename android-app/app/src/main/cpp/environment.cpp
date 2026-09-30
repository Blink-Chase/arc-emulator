#include "environment.h"
#include "vulkan_bridge.h"
#include <cctype>
#include <cstring>
#include <string>

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
  switch (cmd) {
    case 69:
    case 8388611:
    case (4 | RETRO_ENVIRONMENT_EXPERIMENTAL):
      return true;
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

      // Reject HW render ONLY for software-only DS cores
      if (g_isDsCore.load()) {
        LOGI("SET_HW_RENDER rejected for software DS core");
        return false;
      }

      struct retro_hw_render_callback *hw =
              (struct retro_hw_render_callback *)data;
      LOGI("SET_HW_RENDER called by core (cmd 14), type: %u", hw->context_type);

      if (hw->context_type == RETRO_HW_CONTEXT_VULKAN &&
              !vulkanAcceptHwRenderRequest()) {
        LOGW("SET_HW_RENDER: Vulkan unavailable; core must fall back");
        return false;
      }

      // Explicitly copy fields
      g_hwRender = *hw;
      g_useHwRender = true;

      if (hw->context_type == RETRO_HW_CONTEXT_VULKAN) {
        g_useVulkan = true;
      }

      // Provide frontend function pointers to core
      hw->get_proc_address = GetProcAddress;
      hw->get_current_framebuffer = GetCurrentFramebuffer;
      g_hwRender.get_proc_address = GetProcAddress;
      g_hwRender.get_current_framebuffer = GetCurrentFramebuffer;

      // Force GLES 3.2 for SwanStation / PS1 to allow Adreno GLSL display shaders to compile
      if (hw->context_type == RETRO_HW_CONTEXT_OPENGLES2 ||
              hw->context_type == RETRO_HW_CONTEXT_OPENGLES3 ||
              hw->context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION) {
        hw->version_major = 3;
        hw->version_minor = 2;
        g_hwRender.version_major = 3;
        g_hwRender.version_minor = 2;
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
        return vulkanBeginNegotiation(
                reinterpret_cast<const retro_hw_render_context_negotiation_interface_vulkan *>(
                        interfaceInfo));
      }
      return true;
    }
    case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
      LOGI("SET_HW_SHARED_CONTEXT: unavailable");
          return false;
    case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: {
      if (!data)
        return false;
      auto *interfaceInfo =
              static_cast<retro_hw_render_context_negotiation_interface *>(data);
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
      LOGI("SET_DISK_CONTROL_EXT_INTERFACE: unavailable");
          return false;
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
            g_coreVariables["dolphin_shader_compilation_mode"] = "aot";
            g_coreVariables["dolphin_wait_for_shaders"] = "false";
            g_coreVariables["dolphin_main_cpu_thread"] = "False";
            g_coreVariables["dolphin_fastmem"] = "Enabled";
            g_coreVariables["dolphin_cpu_core"] = "JIT ARM64";
          }
          ApplyPcsx2Renderer();
          if (g_coreVariables.find("pcsx2_fastmem") != g_coreVariables.end()) {
            if (!g_pcsx2FastmemValue.empty()) {
              g_coreVariables["pcsx2_fastmem"] = g_pcsx2FastmemValue;
            } else {
              g_coreVariables["pcsx2_fastmem"] = "disabled";
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
        g_coreVariables["dolphin_shader_compilation_mode"] = "aot";
        g_coreVariables["dolphin_wait_for_shaders"] = "false";
        g_coreVariables["dolphin_main_cpu_thread"] = "False";
        g_coreVariables["dolphin_fastmem"] = "Enabled";
        g_coreVariables["dolphin_cpu_core"] = "JIT ARM64";
      }
      ApplyPcsx2Renderer();
      if (g_coreVariables.find("pcsx2_fastmem") != g_coreVariables.end()) {
        if (!g_pcsx2FastmemValue.empty()) {
          g_coreVariables["pcsx2_fastmem"] = g_pcsx2FastmemValue;
        } else {
          g_coreVariables["pcsx2_fastmem"] = "disabled";
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
      return data != nullptr;
    case RETRO_ENVIRONMENT_SET_VARIABLES: {
      if (!data)
        return false;
      const struct retro_variable *received = (const struct retro_variable *)data;
      unsigned count = 0;
      while (received[count].key != nullptr) {
        std::string key(received[count].key);
        std::string value(received[count].value ? received[count].value : "");
        if (key == "pcsx2_renderer" && value.find("Vulkan") != std::string::npos) {
          vulkanSetCoreSupported(true);
          if (g_pcsx2VulkanValue.empty())
            g_pcsx2VulkanValue = "Vulkan";
        }

        auto firstValueStart = value.find(';');
        if (firstValueStart != std::string::npos) {
          firstValueStart += 2;
          auto firstValueEnd = value.find('|', firstValueStart);
          if (firstValueEnd == std::string::npos)
            firstValueEnd = value.length();
          value = value.substr(firstValueStart, firstValueEnd - firstValueStart);
        }

        if (g_coreVariables.find(key) == g_coreVariables.end()) {
          g_coreVariables[key] = value;
        }
        if (key == "pcsx2_fastmem") {
          ResolveFastmemFromLegacy(received[count].value);
        }

        count++;
      }

      // Parallel N64 Overrides
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
      g_coreVariables["parallel-n64-EnableFBEmulation"] = "True";
      g_coreVariables["parallel-n64-ThreadedVideo"] = "True";

      // Mupen64Plus-Next Overrides (Android ARM64 Optimized)
      g_coreVariables["mupen64plus-cpucore"] = "dynamic_recompiler";
      g_coreVariables["mupen64plus-rsp-hle"] = "hle";
      g_coreVariables["mupen64plus-ExpansionPak"] = "enabled";
      g_coreVariables["mupen64plus-CountPerOp"] = "0";
      g_coreVariables["mupen64plus-EnableFBEmulation"] = "True";

      // SwanStation Overrides
      g_coreVariables["swanstation_controller_1_type"] = "AnalogController";

      if (g_isDolphinCore.load()) {
        g_coreVariables["dolphin_shader_compilation_mode"] = "aot";
        g_coreVariables["dolphin_wait_for_shaders"] = "false";
        g_coreVariables["dolphin_main_cpu_thread"] = "False";
        g_coreVariables["dolphin_fastmem"] = "Enabled";
        g_coreVariables["dolphin_cpu_core"] = "JIT ARM64";
      }
      ApplyPcsx2Renderer();
      if (g_coreVariables.find("pcsx2_fastmem") != g_coreVariables.end()) {
        if (!g_pcsx2FastmemValue.empty()) {
          g_coreVariables["pcsx2_fastmem"] = g_pcsx2FastmemValue;
        } else {
          g_coreVariables["pcsx2_fastmem"] = "disabled";
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
        if (key != "pcsx2_renderer" && key != "pcsx2_fastmem" &&
                key != "melonds_screen_layout" && key != "desmume_screens_layout" &&
                key != "melonds_touch_mode" && key != "desmume_pointer_type" &&
                key != "desmume_pointer_mouse") {
          if (g_coreVariables.find(key) != g_coreVariables.end()) {
            var->value = g_coreVariables[key].c_str();
            return true;
          }
        }
        if (std::string(var->key) == "pcsx2_renderer") {
          static std::string chosen;
          if (vulkanRequested() && vulkanCoreSupportsVulkan() &&
                  !g_pcsx2VulkanValue.empty()) {
            chosen = g_pcsx2VulkanValue;
          } else {
            chosen = "Software (SW)";
          }
          var->value = chosen.c_str();
          return true;
        }
        if (std::string(var->key) == "pcsx2_fastmem") {
          static const std::string fallback = "disabled";
          const std::string &off =
                  g_pcsx2FastmemValue.empty() ? fallback : g_pcsx2FastmemValue;
          var->value = off.c_str();
          return true;
        }
        if (std::string(var->key) == "melonds_screen_layout") {
          static const char *dsCoreLayout = "Top/Bottom";
          var->value = dsCoreLayout;
          return true;
        }
        if (std::string(var->key) == "desmume_screens_layout") {
          static const char *dsCoreLayout = "top/bottom";
          var->value = dsCoreLayout;
          return true;
        }
        if (std::string(var->key) == "melonds_touch_mode") {
          static const char *dsTouchMode = "Touchscreen";
          var->value = dsTouchMode;
          return true;
        }
        if (std::string(var->key) == "melonds_boot_directly") {
          static const char *dsBootDirect = "enabled";
          var->value = dsBootDirect;
          return true;
        }
        if (std::string(var->key) == "melonds_username") {
          static const char *dsUsername = "Player";
          var->value = dsUsername;
          return true;
        }
        if (std::string(var->key) == "desmume_pointer_type") {
          static const char *dsPointerType = "touch";
          var->value = dsPointerType;
          return true;
        }
        if (std::string(var->key) == "desmume_pointer_mouse") {
          static const char *dsPointerMouse = "enabled";
          var->value = dsPointerMouse;
          return true;
        }
        auto found = g_coreVariables.find(std::string(var->key));
        if (found != g_coreVariables.end()) {
          var->value = found->second.c_str();
          return true;
        }
        if (std::string(var->key) == "pcsx2_bios") {
          static const char *pcsx2BiosAuto = "Auto";
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
        *(uint64_t *)data = (1ULL << RETRO_DEVICE_JOYPAD) |
                (1ULL << RETRO_DEVICE_ANALOG) |
                (1ULL << RETRO_DEVICE_MOUSE) |
                (1ULL << RETRO_DEVICE_POINTER);
        return true;
      }
          return false;
    case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
      if (g_useVulkan && vulkanInterface()) {
        *reinterpret_cast<void **>(data) = vulkanInterface();
        return true;
      }
          return false;
    default:
      // Silently ignore experimental queries (0x10000 bit set)
      if (cmd >= 0x10000) {
        return false;
      }
          static unsigned lastUnhandledCmd = 0;
          if (cmd != lastUnhandledCmd) {
            LOGD("EnvironmentCallback Unhandled cmd: %u", cmd);
            lastUnhandledCmd = cmd;
          }
          return false;
  }
}