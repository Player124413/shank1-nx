// Xenos (DXT) blocks -> ETC2/EAC blocks, for the Android port's texture upload.
//
// The Xbox 360 game stores its textures as BC1/BC2/BC3/BC4/BC5 (DXT1..DXT5, DXT5A, DXN). Android's Vulkan
// implementations are only required to support ETC2/EAC (Vulkan 1.0 makes the ETC2 formats mandatory for sampled
// images; BC is optional and Mali, PowerVR and Xclipse do not offer it), so the renderer transcodes each block as it
// copies a texture out of guest memory: BC1 -> ETC2_RGB8 (or ETC2_RGBA8 when the texture's alpha is used), BC2/BC3 ->
// ETC2_RGBA8, BC4 -> EAC_R11, BC5 -> EAC_RG11. Compressed blocks stay compressed (BC1 8 bytes -> ETC2 8 bytes), which
// keeps the mobile memory bandwidth the reason not to decompress to RGBA8: a 4x4 BC1 block is 8 bytes, its RGBA8
// form is 64.
//
// The encoders write the *individual* ETC2 mode (the ETC1-compatible one) only: the differential, T, H and planar
// modes are not used, so every block this file writes is decoded exactly as ETC1 + EAC. That keeps the encoder small
// and the result bit-exact reproducible (docs/android.md, "Textures: ETC2 or not?").
//
// Bit layouts (all big-endian, MSB first, as the GPU reads them):
//
//   ETC2 RGB8 block (8 bytes), the individual mode (diff bit 0): R1 bits 63..60, R2 bits 59..56, G1 bits 55..52,
//   G2 bits 51..48, B1 bits 47..44, B2 bits 43..40, table codeword 1 bits 39..37, table codeword 2 bits 37..34 (39..37
//   and 36..34), diff bit 33, flip bit 32, then 16 two-bit texel indices: texel (x, y) is index number x * 4 + y, its
//   low bit at bit x * 4 + y and its high bit at bit 16 + x * 4 + y.
//
//   EAC block (8 bytes, the alpha half of ETC2_RGBA8 and each half of EAC_R11/RG11): base codeword byte 0; the 4-bit
//   table codeword in the low nibble of byte 1 and the multiplier (4 bits) in its high nibble for ETC2_RGBA8's alpha
//   (EAC_R11 reads the multiplier from bits 55..52 and the table from bits 51..48 of the 64-bit word, and scales
//   both by 8); then 16 three-bit indices at bits 45 - 3 * (x * 4 + y). ETC2_RGBA8's 16-byte block is the EAC alpha
//   half first, the RGB block second; EAC_RG11's is R first, G second.
//
// Every layout above was checked against a reference decoder (detex's decompress-etc.c / decompress-eac.c and
// texture2ddecoder's decode_etc1/decode_etc2/decode_etc2a8/decode_eacr), see tools/etc2_check.py.

#pragma once

#include <stdint.h>
#include <string.h>

namespace shank1::etc2 {

// ETC1 intensity modifier sets (ETC2's individual mode uses the same ones), in the order the specs print them:
// {-b, -a, +a, +b}. A texel's two selector bits pick +a (0), +b (1), -a (2) or -b (3), so kSelection maps a
// position in these arrays to the selector bits that reach it.
static const int16_t kModifiers[8][4] = {
    {-8, -2, 2, 8},       {-17, -5, 5, 17},    {-29, -9, 9, 29},    {-42, -13, 13, 42},
    {-60, -18, 18, 60},   {-80, -24, 24, 80},  {-106, -33, 33, 106}, {-183, -47, 47, 183},
};

// kModifiers[table][k] -> the selector bits that select it (0 -> +a, 1 -> +b, 2 -> -a, 3 -> -b).
static const uint8_t kSelection[4] = {3, 2, 0, 1};

// EAC modifier sets (ETC2_RGBA8's alpha and EAC_R11/RG11): [table codeword][index], in 8-bit units.
static const int8_t kEacModifiers[16][8] = {
    {-3, -6, -9, -15, 2, 5, 8, 14},  {-3, -7, -10, -13, 2, 6, 9, 12},
    {-2, -5, -8, -13, 1, 4, 7, 12},  {-2, -4, -6, -13, 1, 3, 5, 12},
    {-3, -6, -8, -12, 2, 5, 7, 11},  {-3, -7, -9, -11, 2, 6, 8, 10},
    {-4, -7, -8, -11, 3, 6, 7, 10},  {-3, -5, -8, -11, 2, 4, 7, 10},
    {-2, -6, -8, -10, 1, 5, 7, 9},   {-2, -5, -8, -10, 1, 4, 7, 9},
    {-2, -4, -8, -10, 1, 3, 7, 9},   {-2, -5, -7, -10, 1, 4, 6, 9},
    {-3, -4, -7, -10, 2, 3, 6, 9},   {-1, -2, -3, -10, 0, 1, 2, 9},
    {-4, -6, -8, -9, 3, 5, 7, 8},    {-3, -5, -7, -9, 2, 4, 6, 8},
};

// 4x4 texels, in the selector order every block uses: p = x * 4 + y.
inline int TexelX(int p) { return p >> 2; }
inline int TexelY(int p) { return p & 3; }

inline int Clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

// ---------------------------------------------------------------------------------------------------------------
// DXT decoding (the guest's blocks -> 4x4 texels in p order).
// ---------------------------------------------------------------------------------------------------------------

inline void Expand565(uint16_t c, uint8_t rgb[3]) {
  const int r = (c >> 11) & 0x1F, g = (c >> 5) & 0x3F, b = c & 0x1F;
  rgb[0] = uint8_t((r << 3) | (r >> 2));
  rgb[1] = uint8_t((g << 2) | (g >> 4));
  rgb[2] = uint8_t((b << 3) | (b >> 2));
}

// stores 16 texels as RGBA in p order; returns true when the block has punch-through (1-bit) alpha.
inline bool DecodeBC1(const uint8_t block[8], uint8_t rgba[16 * 4]) {
  uint16_t c0, c1;
  memcpy(&c0, block, 2);
  memcpy(&c1, block + 2, 2);
  uint8_t palette[4][4];
  Expand565(c0, palette[0]);
  Expand565(c1, palette[1]);
  palette[0][3] = palette[1][3] = 255;
  const bool punch = c0 <= c1;
  if (!punch) {
    for (int i = 0; i < 3; ++i) {
      palette[2][i] = uint8_t((2 * palette[0][i] + palette[1][i]) / 3);
      palette[3][i] = uint8_t((palette[0][i] + 2 * palette[1][i]) / 3);
    }
    palette[2][3] = palette[3][3] = 255;
  } else {
    for (int i = 0; i < 3; ++i) {
      palette[2][i] = uint8_t((palette[0][i] + palette[1][i]) / 2);
      palette[3][i] = 0;
    }
    palette[2][3] = 255;
    palette[3][3] = 0;
  }
  uint32_t bits;
  memcpy(&bits, block + 4, 4);
  bool has_punch = false;
  for (int y = 0; y < 4; ++y) {
    for (int x = 0; x < 4; ++x) {
      const int index = int((bits >> (2 * (4 * y + x))) & 3);
      const int p = x * 4 + y;
      memcpy(rgba + p * 4, palette[index], 4);
      if (punch && index == 3) has_punch = true;
    }
  }
  return has_punch;
}

// DXT3/DXT5 alpha -> 16 alphas in p order.
inline void DecodeBC3Alpha(const uint8_t block[8], uint8_t alpha[16]) {
  const int a0 = block[0], a1 = block[1];
  int palette[8];
  palette[0] = a0;
  palette[1] = a1;
  if (a0 > a1) {
    for (int i = 1; i < 7; ++i) palette[i + 1] = ((7 - i) * a0 + i * a1) / 7;
  } else {
    for (int i = 1; i < 5; ++i) palette[i + 1] = ((5 - i) * a0 + i * a1) / 5;
    palette[6] = 0;
    palette[7] = 255;
  }
  uint64_t bits = 0;
  for (int i = 0; i < 6; ++i) bits |= uint64_t(block[2 + i]) << (8 * i);
  for (int y = 0; y < 4; ++y) {
    for (int x = 0; x < 4; ++x) {
      const int index = int((bits >> (3 * (4 * y + x))) & 7);
      alpha[x * 4 + y] = uint8_t(palette[index]);
    }
  }
}

inline void DecodeBC3(const uint8_t block[16], uint8_t rgba[16 * 4]) {
  DecodeBC1(block + 8, rgba);
  uint8_t alpha[16];
  DecodeBC3Alpha(block, alpha);
  for (int p = 0; p < 16; ++p) rgba[p * 4 + 3] = alpha[p];
}

// DXT5A (BC4): one channel.
inline void DecodeBC4(const uint8_t block[8], uint8_t values[16]) { DecodeBC3Alpha(block, values); }

// DXN (BC5): two channels (X in the first block, Y in the second).
inline void DecodeBC5(const uint8_t block[16], uint8_t x_values[16], uint8_t y_values[16]) {
  DecodeBC4(block, x_values);
  DecodeBC4(block + 8, y_values);
}

// ---------------------------------------------------------------------------------------------------------------
// ETC2 RGB8 encoding: the individual mode, one 8-byte block from 16 RGBA texels.
// ---------------------------------------------------------------------------------------------------------------

namespace detail {

struct SubBlock {
  int error = 0x7FFFFFFF;
  int table = 0;
  int base[3] = {0, 0, 0};
  uint8_t index[8] = {0};  // per texel of the subblock, in subblock order
};

// The 8 texels of one subblock, as p indices: 'flip' 0 takes the left half (x = 0, 1), 1 the top half (y = 0, 1).
inline void SubBlockIndices(int flip, int sub, int out[8]) {
  for (int i = 0; i < 8; ++i) {
    out[i] = flip == 0 ? (int(sub) * 2 + (i >> 2)) * 4 + (i & 3) : (i >> 2) * 4 + int(sub) * 2 + (i & 3);
  }
}

// Squared error of one channel over the subblock with 'base' (4-bit) and 'table', also filling the closest
// modifier index per texel.
inline int ChannelError(const uint8_t *values, int count, int base, int table, uint8_t *index) {
  const int16_t *mods = kModifiers[table];
  const int center = base * 17;
  int error = 0;
  for (int i = 0; i < count; ++i) {
    const int v = values[i];
    int best = 0x7FFFFFFF, best_index = 0;
    for (int m = 0; m < 4; ++m) {
      const int d = v - Clamp255(center + mods[m]);
      const int e = d * d;
      if (e < best) {
        best = e;
        best_index = m;
      }
    }
    error += best;
    if (index) index[i] = uint8_t(best_index);
  }
  return error;
}

}  // namespace detail

namespace detail {
inline void PackRGB(int flip, int table[2], const int base[2][3], const uint8_t index[2][8], uint8_t out[8]) {
  uint64_t word = uint64_t(flip) << 32;  // the diff bit (33) stays 0: individual mode
  word |= uint64_t(table[0] & 7) << 37;
  word |= uint64_t(table[1] & 7) << 34;
  for (int s = 0; s < 2; ++s) {
    word |= uint64_t(base[s][0] & 0xF) << (60 - 4 * s);
    word |= uint64_t(base[s][1] & 0xF) << (52 - 4 * s);
    word |= uint64_t(base[s][2] & 0xF) << (44 - 4 * s);
    int order[8];
    SubBlockIndices(flip, s, order);
    for (int i = 0; i < 8; ++i) {
      const int value = kSelection[index[s][i] & 3];
      word |= uint64_t(value & 1) << order[i];
      word |= uint64_t(value >> 1) << (16 + order[i]);
    }
  }
  for (int i = 0; i < 8; ++i) out[i] = uint8_t(word >> (56 - 8 * i));
}
}  // namespace detail

// Encodes the 16 RGBA texels of one block (p order) into an 8-byte ETC2 individual-mode block. 'effort' 0 tries the
// three intensity tables the subblock's spread suggests and one base colour each, 1 tries all eight tables and the
// base colour's neighbours: about four times slower, and a little better.
inline void EncodeRGB(const uint8_t rgba[16 * 4], uint8_t out[8], int effort = 0) {
  uint8_t channels[3][16];
  for (int p = 0; p < 16; ++p)
    for (int c = 0; c < 3; ++c) channels[c][p] = rgba[p * 4 + c];

  struct Candidate {
    int error = 0x7FFFFFFF;
    int table = 0;
    int base[3] = {0, 0, 0};
    uint8_t index[8] = {0};  // per texel of the subblock, in subblock order
  };

  int best_error = 0x7FFFFFFF, best_flip = 0;
  Candidate best[2];

  for (int flip = 0; flip < 2; ++flip) {
    Candidate sub[2];
    int total = 0;
    for (int s = 0; s < 2; ++s) {
      int order[8];
      detail::SubBlockIndices(flip, s, order);
      uint8_t values[3][8];
      for (int c = 0; c < 3; ++c)
        for (int i = 0; i < 8; ++i) values[c][i] = channels[c][order[i]];

      // The intensity tables worth trying: the subblock's darkest and brightest texels pick them.
      int lo = 255, hi = 0;
      for (int p = 0; p < 16; ++p) {
        const bool in_sub = flip == 0 ? (p >> 2) / 2 == s : (p & 3) / 2 == s;
        if (!in_sub) continue;
        for (int c = 0; c < 3; ++c) {
          lo = rgba[p * 4 + c] < lo ? rgba[p * 4 + c] : lo;
          hi = rgba[p * 4 + c] > hi ? rgba[p * 4 + c] : hi;
        }
      }
      const int spread = hi - lo;
      int table_from = 0, table_to = 8;
      if (effort == 0) {
        const int guess = spread < 12 ? 0 : spread < 26 ? 1 : spread < 44 ? 2 : spread < 64 ? 3
                          : spread < 96 ? 4 : spread < 140 ? 5 : spread < 220 ? 6 : 7;
        table_from = guess > 0 ? guess - 1 : 0;
        table_to = guess < 7 ? guess + 2 : 8;
      }

      for (int t = table_from; t < table_to; ++t) {
        const int mod_sum = kModifiers[t][0] + kModifiers[t][1] + kModifiers[t][2] + kModifiers[t][3];
        // Each channel's base colour, from its mean (the modifiers average near zero for a table that fits).
        int base[3];
        for (int c = 0; c < 3; ++c) {
          int sum = 0;
          for (int i = 0; i < 8; ++i) sum += values[c][i];
          int b = (sum - 2 * mod_sum) / 136;  // round((mean - mod_sum / 4) / 17)
          base[c] = b < 0 ? 0 : (b > 15 ? 15 : b);
        }
        const int tries = effort == 0 ? 1 : 3;
        for (int k = 0; k < tries; ++k) {
          const int delta = k == 0 ? 0 : (k == 1 ? -1 : 1);
          int b3[3];
          for (int c = 0; c < 3; ++c) {
            const int v = base[c] + delta;
            b3[c] = v < 0 ? 0 : (v > 15 ? 15 : v);
          }
          // The texel indices are shared by the three channels, so the error is scored together.
          uint8_t index[8];
          int error = 0;
          for (int i = 0; i < 8; ++i) {
            int best_e = 0x7FFFFFFF, best_m = 0;
            for (int m = 0; m < 4; ++m) {
              int e = 0;
              for (int c = 0; c < 3; ++c) {
                const int d = int(values[c][i]) - Clamp255(b3[c] * 17 + kModifiers[t][m]);
                e += d * d;
              }
              if (e < best_e) {
                best_e = e;
                best_m = m;
              }
            }
            index[i] = uint8_t(best_m);
            error += best_e;
            if (error >= sub[s].error) break;
          }
          if (error < sub[s].error) {
            sub[s].error = error;
            sub[s].table = t;
            sub[s].base[0] = b3[0];
            sub[s].base[1] = b3[1];
            sub[s].base[2] = b3[2];
            memcpy(sub[s].index, index, 8);
          }
        }
      }
      total += sub[s].error;
    }
    if (total < best_error) {
      best_error = total;
      best_flip = flip;
      best[0] = sub[0];
      best[1] = sub[1];
    }
  }

  int table[2] = {best[0].table, best[1].table};
  int base[2][3] = {{best[0].base[0], best[0].base[1], best[0].base[2]},
                    {best[1].base[0], best[1].base[1], best[1].base[2]}};
  uint8_t index[2][8];
  memcpy(index[0], best[0].index, 8);
  memcpy(index[1], best[1].index, 8);
  detail::PackRGB(best_flip, table, base, index, out);
}


// ---------------------------------------------------------------------------------------------------------------
// EAC encoding: 8-byte blocks of one 4x4 channel (ETC2_RGBA8's alpha, EAC_R11, and each half of EAC_RG11).
// ---------------------------------------------------------------------------------------------------------------

namespace detail {
inline void FinishEac(int base, int table, int mult, const uint8_t index[16], uint8_t out[8]) {
  out[0] = uint8_t(base);
  out[1] = uint8_t(uint8_t(mult & 0xF) << 4 | uint8_t(table & 0xF));
  uint64_t selectors = 0;
  for (int i = 0; i < 16; ++i) selectors |= uint64_t(index[i] & 7) << (45 - 3 * i);
  for (int i = 0; i < 6; ++i) out[2 + i] = uint8_t(selectors >> (40 - 8 * i));
}
}  // namespace detail

inline void EncodeEac(const uint8_t values[16], uint8_t out[8], int effort = 0) {
  int lo = 255, hi = 0;
  for (int i = 0; i < 16; ++i) {
    lo = values[i] < lo ? values[i] : lo;
    hi = values[i] > hi ? values[i] : hi;
  }

  int best_error = 0x7FFFFFFF, best_base = lo, best_table = 13, best_mult = 1;
  uint8_t best_index[16] = {0};

  if (lo == hi) {
    // One value: table 13's fourth modifier is 0, so base = value, multiplier 1 and every index points at it.
    for (int i = 0; i < 16; ++i) best_index[i] = 4;
    best_error = 0;
  } else {
    const int range = hi - lo;
    const int center = (lo + hi + 1) / 2;
    const int iterations = effort == 0 ? 2 : 4;
    // Each table: assign every texel its nearest modifier, then refit the base colour and the multiplier by least
    // squares (both are linear in the texel values) and assign again. The tables are cheap to try; the fit is what
    // stops the multiplier from being rounded away, which is what a pure guess from the range does.
    for (int table = 0; table < 16; ++table) {
      const int8_t *mods = kEacModifiers[table];
      int span = mods[7] - mods[0];
      if (span <= 0) span = 1;
      int mult = range / span;
      mult = mult < 1 ? 1 : (mult > 15 ? 15 : mult);
      const int mid = (mods[0] + mods[7]) / 2;
      int base = Clamp255(center - mult * mid);
      uint8_t index[16];
      for (int iteration = 0; iteration < iterations; ++iteration) {
        // Assign, and score.
        int error = 0;
        int sum_m = 0, sum_v = 0, sum_mm = 0, sum_mv = 0;
        for (int i = 0; i < 16; ++i) {
          int best_e = 0x7FFFFFFF, best_s = 0;
          for (int s = 0; s < 8; ++s) {
            const int v = Clamp255(base + mods[s] * mult);
            const int d = int(values[i]) - v;
            const int e = d * d;
            if (e < best_e) {
              best_e = e;
              best_s = s;
            }
          }
          index[i] = uint8_t(best_s);
          error += best_e;
          const int m = mods[best_s];
          sum_m += m;
          sum_v += values[i];
          sum_mm += m * m;
          sum_mv += m * values[i];
        }
        if (error < best_error) {
          best_error = error;
          best_base = base;
          best_table = table;
          best_mult = mult;
          memcpy(best_index, index, 16);
          if (!best_error) return detail::FinishEac(best_base, best_table, best_mult, best_index, out);
        }
        // Refit: least squares over (1, modifier) against the values.
        const int denom = 16 * sum_mm - sum_m * sum_m;
        if (denom > 16) {
          int fitted_mult = (16 * sum_mv - sum_m * sum_v) / denom;
          fitted_mult = fitted_mult < 1 ? 1 : (fitted_mult > 15 ? 15 : fitted_mult);
          int fitted_base = (sum_v - fitted_mult * sum_m + 8) / 16;
          fitted_base = Clamp255(fitted_base);
          if (fitted_mult == mult && fitted_base == base) break;
          mult = fitted_mult;
          base = fitted_base;
        } else {
          break;
        }
      }
    }
  }
  detail::FinishEac(best_base, best_table, best_mult, best_index, out);
}

// EAC_R11 (and each 8-byte half of EAC_RG11): the same 16-texel encoding with the table codeword and the multiplier
// in bits 51..48 and 55..52 (which is byte 1, low nibble and high nibble) and the base in bits 63..56 (byte 0),
// exactly where ETC2_RGBA8's alpha half keeps them; that half's decoder reads a value 8 times finer, and the 11-bit
// one's modifiers are 8 times coarser, so the same bytes carry the same 8-bit values.
inline void EncodeEacR11(const uint8_t values[16], uint8_t out[8], int effort = 0) { EncodeEac(values, out, effort); }

// ---------------------------------------------------------------------------------------------------------------
// The transcoders the renderer calls, one per guest format.
// ---------------------------------------------------------------------------------------------------------------

// ETC2_RGB8 from a BC1 block (drops the texels' alpha).
inline void BC1ToRGB(const uint8_t block[8], uint8_t out[8], int effort = 0) {
  uint8_t rgba[16 * 4];
  DecodeBC1(block, rgba);
  for (int p = 0; p < 16; ++p) rgba[p * 4 + 3] = 255;
  EncodeRGB(rgba, out, effort);
}

// ETC2_RGBA8 from a BC1 block: its 1-bit alpha becomes 0 or 255 (the punch-through texels).
inline void BC1ToRGBA(const uint8_t block[8], uint8_t out[16], int effort = 0) {
  uint8_t rgba[16 * 4];
  DecodeBC1(block, rgba);
  uint8_t alpha[16];
  for (int p = 0; p < 16; ++p) alpha[p] = rgba[p * 4 + 3];
  EncodeRGB(rgba, out + 8, effort);  // the alpha half comes first
  EncodeEac(alpha, out, 0);
}

// ETC2_RGBA8 from a BC2 (DXT3, 4-bit explicit alpha) or BC3 (DXT5) block.
inline void BC3ToRGBA(const uint8_t block[16], uint8_t out[16], int effort = 0) {
  uint8_t rgba[16 * 4];
  DecodeBC3(block, rgba);
  uint8_t alpha[16];
  for (int p = 0; p < 16; ++p) alpha[p] = rgba[p * 4 + 3];
  EncodeRGB(rgba, out + 8, effort);
  EncodeEac(alpha, out, 0);
}

// ETC2_RGBA8 from a BC2 block: its alpha is four bits per texel, and the RGB half is a BC1 block.
inline void BC2ToRGBA(const uint8_t block[16], uint8_t out[16], int effort = 0) {
  uint8_t rgba[16 * 4];
  DecodeBC1(block + 8, rgba);
  uint8_t alpha[16];
  for (int y = 0; y < 4; ++y) {
    const uint16_t row = uint16_t(block[2 * y] | (block[2 * y + 1] << 8));
    for (int x = 0; x < 4; ++x) alpha[x * 4 + y] = uint8_t((row >> (4 * x)) & 0xF) * 17;
  }
  EncodeRGB(rgba, out + 8, effort);
  EncodeEac(alpha, out, 0);
}

// EAC_R11 from a BC4 block.
inline void BC4ToEacR11(const uint8_t block[8], uint8_t out[8], int effort = 0) {
  uint8_t values[16];
  DecodeBC4(block, values);
  EncodeEacR11(values, out, effort);
}

// EAC_RG11 from a BC5 block.
inline void BC5ToEacRG11(const uint8_t block[16], uint8_t out[16], int effort = 0) {
  uint8_t x_values[16], y_values[16];
  DecodeBC5(block, x_values, y_values);
  EncodeEacR11(x_values, out, effort);
  EncodeEacR11(y_values, out + 8, effort);
}

}  // namespace shank1::etc2
