/**
 * @file    android/android_paths.cpp
 */

#include "android/android_paths.h"

#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <unordered_map>

namespace shank1::android {
namespace {

std::string g_game_root;
std::string g_config;
bool g_game_root_is_pack = false;

bool StartsWith(const char *value, const char *prefix) { return std::strncmp(value, prefix, std::strlen(prefix)) == 0; }

std::mutex g_options_mutex;
std::unordered_map<std::string, std::string> g_options;
bool g_options_loaded = false;

// A line scanner for the launcher's own file, not a TOML parser: the keys the Android side reads are flat
// `key = value` lines and the engine does the real parsing.
void LoadOptions() {
  std::lock_guard<std::mutex> lock(g_options_mutex);
  if (g_options_loaded)
    return;
  g_options_loaded = true;
  if (g_config.empty())
    return;
  std::ifstream file(g_config);
  std::string line;
  while (std::getline(file, line)) {
    const auto equals = line.find('=');
    if (equals == std::string::npos)
      continue;
    std::string key = line.substr(0, equals);
    std::string value = line.substr(equals + 1);
    auto trim = [](std::string &text) {
      const char *spaces = " \t\r\n\"";
      const auto first = text.find_first_not_of(spaces);
      const auto last = text.find_last_not_of(spaces);
      text = first == std::string::npos ? std::string() : text.substr(first, last - first + 1);
    };
    trim(key);
    trim(value);
    if (!key.empty())
      g_options[key] = value;
  }
}

} // namespace

void ParseCommandLine(int argc, char **argv) {
  for (int i = 1; i < argc; ++i) {
    const char *argument = argv[i];
    if (argument == nullptr)
      continue;
    if (StartsWith(argument, "--game-root=")) {
      g_game_root = argument + std::strlen("--game-root=");
      // The pack's name is the launcher's convention; the extension is what the SDK's PackDevice keys on.
      const auto dot = g_game_root.rfind(".nxpack");
      g_game_root_is_pack = dot != std::string::npos && dot + 7 == g_game_root.size();
    } else if (StartsWith(argument, "--config=")) {
      g_config = argument + std::strlen("--config=");
    }
  }
}

const std::string &GameRoot() { return g_game_root; }

bool GameRootIsPack() { return g_game_root_is_pack; }

const std::string &ConfigPath() { return g_config; }

std::string OptionValue(const char *key, const char *fallback) {
  LoadOptions();
  std::lock_guard<std::mutex> lock(g_options_mutex);
  const auto found = g_options.find(key != nullptr ? key : "");
  if (found == g_options.end())
    return fallback != nullptr ? std::string(fallback) : std::string();
  return found->second;
}

bool OptionFlag(const char *key, bool fallback) {
  const std::string value = OptionValue(key, fallback ? "true" : "false");
  if (value == "true" || value == "1" || value == "on" || value == "yes")
    return true;
  if (value == "false" || value == "0" || value == "off" || value == "no")
    return false;
  return fallback;
}

} // namespace shank1::android
