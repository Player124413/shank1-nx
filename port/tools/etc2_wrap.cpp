// A C interface to src/android/etc2_encode.h, compiled once by tools/etc2_check.py and loaded with ctypes, so the
// tool measures the very code the renderer runs (not a Python re-implementation of it).
#include "../src/android/etc2_encode.h"

extern "C" {

// ETC2 RGB8 block (8 bytes) from 16 RGBA texels (64 bytes, p = x * 4 + y order).
void shank1_etc2_encode_rgb(const uint8_t *rgba, uint8_t *out, int effort) {
  shank1::etc2::EncodeRGB(rgba, out, effort);
}

// ETC2_RGBA8 block (16 bytes: alpha half, RGB half) from 16 RGBA texels (64 bytes).
void shank1_etc2_encode_rgba(const uint8_t *rgba, uint8_t *out, int effort) {
  uint8_t alpha[16];
  for (int p = 0; p < 16; ++p) alpha[p] = rgba[p * 4 + 3];
  shank1::etc2::EncodeRGB(rgba, out + 8, effort);
  shank1::etc2::EncodeEac(alpha, out, 0);
}

// EAC block (8 bytes) from 16 values (the alpha half of ETC2_RGBA8 / an EAC_R11 half).
void shank1_eac_encode(const uint8_t *values, uint8_t *out, int effort) {
  shank1::etc2::EncodeEac(values, out, effort);
}

void shank1_bc1_decode(const uint8_t *block, uint8_t *rgba) {
  shank1::etc2::DecodeBC1(block, rgba);
}

void shank1_bc3_decode(const uint8_t *block, uint8_t *rgba) {
  shank1::etc2::DecodeBC3(block, rgba);
}

void shank1_bc1_to_rgb(const uint8_t *block, uint8_t *out, int effort) {
  shank1::etc2::BC1ToRGB(block, out, effort);
}

void shank1_bc1_to_rgba(const uint8_t *block, uint8_t *out, int effort) {
  shank1::etc2::BC1ToRGBA(block, out, effort);
}

void shank1_bc3_to_rgba(const uint8_t *block, uint8_t *out, int effort) {
  shank1::etc2::BC3ToRGBA(block, out, effort);
}

void shank1_bc4_to_eac(const uint8_t *block, uint8_t *out, int effort) {
  shank1::etc2::BC4ToEacR11(block, out, effort);
}

void shank1_bc5_to_eac(const uint8_t *block, uint8_t *out, int effort) {
  shank1::etc2::BC5ToEacRG11(block, out, effort);
}

}  // extern "C"
