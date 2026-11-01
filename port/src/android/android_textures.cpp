/**
 * @file    android/android_textures.cpp
 */

#include "android/android_textures.h"

#include <cstdlib>
#include <string>

#include "android/android_paths.h"

#if defined(SHANK1_ANDROID_ETC2)
#include "android/etc2_encode.h"
#endif

namespace shank1::android::textures {
namespace {

TargetFormat g_format = TargetFormat::NativeBC;
bool g_resolved = false;
int g_effort = 1;
bool g_cache = true;
std::string g_cache_directory;

#if defined(SHANK1_ANDROID_ETC2)
// Whether the Vulkan device advertises textureCompressionBC. The renderer can set it once it has picked a physical
// device (banjo_renut_select.cpp / backend.cpp on the Android path); until then, assume not, which is what most
// Android GPUs answer anyway and what makes ETC2 the working default.
bool g_device_has_bc = false;
#endif

void Resolve() {
  if (g_resolved)
    return;
  g_resolved = true;
  const std::string format = shank1::android::OptionValue("android_texture_format", "etc2");
  g_effort = 1;
  const std::string effort = shank1::android::OptionValue("android_etc2_effort", "1");
  if (!effort.empty()) {
    const int value = std::atoi(effort.c_str());
    g_effort = value < 0 ? 0 : (value > 2 ? 2 : value);
  }
  g_cache = shank1::android::OptionFlag("android_texture_cache", true);

  if (format == "rgba8") {
    g_format = TargetFormat::Rgba8;
    return;
  }
#if defined(SHANK1_ANDROID_ETC2)
  if (format == "etc2") {
    g_format = TargetFormat::Etc2;
    return;
  }
#endif
  g_format = TargetFormat::NativeBC;

  // The cache lives beside the game's data, in the app's own files directory.
  const std::string &root = shank1::android::GameRoot();
  const auto slash = root.find_last_of('/');
  if (slash != std::string::npos)
    g_cache_directory = root.substr(0, slash) + "/texture_cache";
  else
    g_cache_directory = "texture_cache";
}

} // namespace

TargetFormat Format() {
  Resolve();
#if defined(SHANK1_ANDROID_ETC2)
  // A device that compresses BC natively needs no conversion: the console's blocks are already what Vulkan wants, and
  // transcoding would only cost load time and a generation of quality.
  if (g_format == TargetFormat::Etc2 && g_device_has_bc)
    return TargetFormat::NativeBC;
#else
  if (g_format == TargetFormat::Etc2)
    return TargetFormat::NativeBC;
#endif
  return g_format;
}

bool Etc2Enabled() { return Format() == TargetFormat::Etc2; }

int Effort() {
  Resolve();
  return g_effort;
}

bool CacheEnabled() {
  Resolve();
  return g_cache && !g_cache_directory.empty();
}

const char *CacheDirectory() {
  Resolve();
  return g_cache_directory.c_str();
}

void SetDeviceSupportsBC(bool supported) {
#if defined(SHANK1_ANDROID_ETC2)
  g_device_has_bc = supported;
#else
  (void)supported;
#endif
}

} // namespace shank1::android::textures
