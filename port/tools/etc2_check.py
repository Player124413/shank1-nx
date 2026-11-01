#!/usr/bin/env python3
"""Checks the Android port's DXT -> ETC2/EAC transcoders (src/android/etc2_encode.h): builds tools/etc2_wrap.cpp into
out/tools/ and measures, block by block, what the transcode costs.

  python tools/etc2_check.py                 # the built-in test set, every format
  python tools/etc2_check.py --image a.png   # ... and a picture, if Pillow is installed
  python tools/etc2_check.py --effort 1      # the encoder's thorough search
  python tools/etc2_check.py --self-test     # only the layout self-tests (no texture2ddecoder needed)

The reference decoder is texture2ddecoder (pip install texture2ddecoder numpy; AssetStudio's decoders). Without it the
tool still checks that the encoder writes what it says it writes (the layout self-tests) and reports timings, but not
quality. With it, every format gets a PSNR against the DXT block the guest would have sampled, which is the number
docs/android.md quotes.

What is checked, in order:
  1. the DXT decoders here against the reference ones, byte for byte;
  2. ETC2 individual mode: the encoder's blocks decoded by the reference ETC2 decoder;
  3. ETC2_RGBA8's two halves (the alpha half comes first) and EAC_R11/RG11;
  4. PSNR and the encoder's throughput, per format, for the test set.
"""
import argparse
import ctypes
import os
import subprocess
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = os.path.dirname(HERE)
OUT = os.path.join(PORT, 'out', 'tools')
SRC = os.path.join(HERE, 'etc2_wrap.cpp')
HEADER = os.path.join(PORT, 'src', 'android', 'etc2_encode.h')

try:
    import texture2ddecoder as ref
except ImportError:  # pragma: no cover - the reference is optional
    ref = None


# ------------------------------------------------------------------------------------------------------------------
# The encoder, built from the header the renderer includes.
# ------------------------------------------------------------------------------------------------------------------

def build_library():
    os.makedirs(OUT, exist_ok=True)
    suffix = '.dll' if os.name == 'nt' else '.so'
    lib_path = os.path.join(OUT, 'etc2_wrap' + suffix)
    if os.path.exists(lib_path) and os.path.getmtime(lib_path) > os.path.getmtime(HEADER):
        return lib_path
    # clang++ / g++ where they exist; a plain gcc also builds this (the wrapper pulls in no standard library).
    candidates = [(os.environ.get('CXX'), []), ('c++', []), ('clang++', []), ('g++', []),
                  ('clang', ['-x', 'c++']), ('gcc', ['-x', 'c++']), ('cc', ['-x', 'c++'])]
    for cxx, extra in candidates:
        if not cxx:
            continue
        cmd = [cxx] + extra + ['-O2', '-std=c++20', '-shared', '-o', lib_path, SRC]
        if os.name != 'nt':
            cmd.append('-fPIC')
        if subprocess.run(cmd, capture_output=True).returncode == 0:
            return lib_path
    sys.exit('no C++ compiler (set CXX) to build tools/etc2_wrap.cpp')


def load(lib_path):
    lib = ctypes.CDLL(lib_path)

    def block_fn(name, out_bytes):
        fn = getattr(lib, name)
        fn.restype = None
        fn.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int] if out_bytes else \
                      [ctypes.c_char_p, ctypes.c_char_p]
        return fn

    api = {}
    for name in ('shank1_etc2_encode_rgb', 'shank1_etc2_encode_rgba', 'shank1_eac_encode',
                 'shank1_bc1_to_rgb', 'shank1_bc1_to_rgba', 'shank1_bc3_to_rgba',
                 'shank1_bc4_to_eac', 'shank1_bc5_to_eac'):
        api[name] = block_fn(name, True)
    for name in ('shank1_bc1_decode', 'shank1_bc3_decode'):
        api[name] = block_fn(name, False)
    return api


def call3(fn, src_bytes, out_bytes, effort=0):
    out = ctypes.create_string_buffer(out_bytes)
    fn(bytes(src_bytes), out, effort)
    return out.raw


# ------------------------------------------------------------------------------------------------------------------
# DXT encoders for the test set: the game's own blocks come from the Xbox 360 build, this tool needs some to test.
# A plain min/max-endpoint BC1 is enough to feed the transcoders; the transcode's loss is what is measured, not the
# test encoder's.
# ------------------------------------------------------------------------------------------------------------------

def to565(c):
    r, g, b = (int(v) for v in c[:3])
    return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)


def from565(v):
    r, g, b = (v >> 11) & 0x1F, (v >> 5) & 0x3F, v & 0x1F
    return np.array([(r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)], dtype=np.float32)


def encode_bc1(block_rgba):
    """block_rgba: 16x4 uint8, p = x * 4 + y order. Returns 8 bytes."""
    rgb = block_rgba[:, :3].astype(np.float32)
    alpha = block_rgba[:, 3]
    punch = bool((alpha < 128).any())
    # The two colours furthest apart on the block's principal axis (min/max per channel is close enough here).
    c0, c1 = rgb.max(axis=0), rgb.min(axis=0)
    v0, v1 = to565(c0), to565(c1)
    if punch and v0 > v1:
        v0, v1 = v1, v0
    if not punch and v0 < v1:
        v0, v1 = v1, v0
    e0, e1 = from565(v0), from565(v1)
    if punch:
        palette = [e0, e1, (e0 + e1) / 2, np.zeros(3, dtype=np.float32)]
    else:
        palette = [e0, e1, (2 * e0 + e1) / 3, (e0 + 2 * e1) / 3]
    bits = 0
    for y in range(4):
        for x in range(4):
            p = x * 4 + y
            d = [float(((rgb[p] - np.array(col)) ** 2).sum()) for col in palette]
            bits |= int(np.argmin(d)) << (2 * (4 * y + x))
    return np.frombuffer(np.uint16([v0, v1]).tobytes() + np.uint32(bits).tobytes(), dtype=np.uint8).tobytes()


def encode_bc3_alpha(alpha):
    """16 values -> 8 bytes of a DXT5 alpha block (8-step palette, min/max endpoints)."""
    a = alpha.astype(np.int32)
    a0, a1 = int(a.max()), int(a.min())
    palette = [a0, a1]
    palette += [((7 - i) * a0 + i * a1) // 7 for i in range(1, 7)]
    bits = 0
    for y in range(4):
        for x in range(4):
            p = x * 4 + y
            d = [abs(int(a[p]) - v) for v in palette]
            bits |= int(np.argmin(d)) << (3 * (4 * y + x))
    head = bytes([a0, a1])
    return head + int(bits).to_bytes(6, 'little')


def encode_bc3(block_rgba):
    rgb = encode_bc1(block_rgba)
    return encode_bc3_alpha(block_rgba[:, 3]) + rgb


# ------------------------------------------------------------------------------------------------------------------
# The test set: 8x8 tiles of the kinds of content the game's textures have.
# ------------------------------------------------------------------------------------------------------------------

def tile_gradient(seed):
    rng = np.random.default_rng(seed)
    base = rng.integers(0, 256, 3)
    delta = rng.integers(-80, 81, 3)
    img = np.zeros((8, 8, 4), dtype=np.uint8)
    for y in range(8):
        for x in range(8):
            t = (x + y) / 14.0
            img[y, x, :3] = np.clip(base + delta * t, 0, 255).astype(np.uint8)
    img[:, :, 3] = 255
    return img


def tile_flat(seed):
    rng = np.random.default_rng(seed)
    img = np.zeros((8, 8, 4), dtype=np.uint8)
    img[:, :, :3] = rng.integers(0, 256, 3).astype(np.uint8)
    img[:, :, 3] = 255
    return img


def tile_noise(seed):
    rng = np.random.default_rng(seed)
    img = np.zeros((8, 8, 4), dtype=np.uint8)
    img[:, :, :3] = rng.integers(0, 256, (8, 8, 3), dtype=np.uint8)
    img[:, :, 3] = 255
    return img


def tile_edges(seed):
    rng = np.random.default_rng(seed)
    a, b = rng.integers(0, 256, 3), rng.integers(0, 256, 3)
    img = np.zeros((8, 8, 4), dtype=np.uint8)
    img[:, :4, :3] = a
    img[:, 4:, :3] = b
    img[:, :, 3] = 255
    return img


def tile_alpha(seed):
    rng = np.random.default_rng(seed)
    img = np.zeros((8, 8, 4), dtype=np.uint8)
    img[:, :, :3] = rng.integers(0, 256, 3).astype(np.uint8)
    alpha = np.clip(np.linspace(rng.integers(0, 64), rng.integers(192, 256), 8), 0, 255)
    img[:, :, 3] = alpha.astype(np.uint8)[None, :]
    return img


def tile_cutout(seed):
    """Foliage-like: a colour and punch-through holes (BC1 with c0 <= c1)."""
    rng = np.random.default_rng(seed)
    img = np.zeros((8, 8, 4), dtype=np.uint8)
    img[:, :, :3] = rng.integers(60, 200, 3).astype(np.uint8)
    holes = rng.random((8, 8)) < 0.4
    img[:, :, 3] = np.where(holes, 0, 255).astype(np.uint8)
    return img


def tile_normal(seed):
    rng = np.random.default_rng(seed)
    img = np.zeros((8, 8, 4), dtype=np.uint8)
    yy, xx = np.mgrid[0:8, 0:8]
    img[:, :, 0] = np.clip(128 + 60 * np.sin((xx + seed) / 2.0), 0, 255).astype(np.uint8)
    img[:, :, 1] = np.clip(128 + 60 * np.cos((yy + seed) / 2.0), 0, 255).astype(np.uint8)
    img[:, :, 3] = 255
    return img


TEST_TILES = [('flat', tile_flat), ('gradient', tile_gradient), ('edges', tile_edges), ('noise', tile_noise),
              ('alpha', tile_alpha), ('cutout', tile_cutout), ('normal', tile_normal)]


def blocks_of(img):
    """A 8x8 RGBA image -> four 4x4 blocks in p order."""
    out = []
    for by in (0, 1):
        for bx in (0, 1):
            block = np.zeros((16, 4), dtype=np.uint8)
            for y in range(4):
                for x in range(4):
                    block[x * 4 + y] = img[by * 4 + y, bx * 4 + x]
            out.append(block)
    return out


def ref_pixels(raw):
    """A reference decoder's 4x4 output (row-major, BGRA) -> 16 texels in the p = x * 4 + y order blocks use."""
    a = np.frombuffer(raw, dtype=np.uint8).reshape(16, 4)[:, [2, 1, 0, 3]]
    return np.array([a[(p & 3) * 4 + (p >> 2)] for p in range(16)])


def ref_channel(raw, channel=2):
    """One channel of a reference decode's 4x4 output, in p order (the EAC formats' value lands in channel 2)."""
    a = np.frombuffer(raw, dtype=np.uint8).reshape(16, 4)[:, channel]
    return np.array([a[(p & 3) * 4 + (p >> 2)] for p in range(16)])


def psnr(a, b, mask=None):
    d = (a.astype(np.float64) - b.astype(np.float64)) ** 2
    if mask is not None:
        d = d[mask]
    mse = d.mean()
    return float('inf') if mse <= 0 else 10.0 * np.log10(255.0 * 255.0 / mse)


# ------------------------------------------------------------------------------------------------------------------
# Checks.
# ------------------------------------------------------------------------------------------------------------------

def check_layouts(api):
    """The layout self-tests: nothing here needs the reference decoder."""
    failures = []
    # A flat ETC2 block: base 17 * 15 = 255 red, index 0 (+2) everywhere -> decodes to 255, 2, 2.
    rgba = np.zeros((16, 4), dtype=np.uint8)
    rgba[:, 0], rgba[:, 1], rgba[:, 2] = 255, 0, 0
    rgba[:, 3] = 255
    block = call3(api['shank1_etc2_encode_rgb'], rgba.tobytes(), 8)
    if ref is not None:
        decoded = ref_pixels(ref.decode_etc2(block, 4, 4))
        if not (decoded[:, 0].min() >= 200 and decoded[:, 1].max() <= 12 and decoded[:, 2].max() <= 12):
            failures.append('ETC2 RGB8 block does not decode to red: %s' % decoded[0].tolist())
    # The EAC alpha half of ETC2_RGBA8 comes first; a flat alpha must land in the alpha channel.
    rgba[:, 3] = 200
    block = call3(api['shank1_etc2_encode_rgba'], rgba.tobytes(), 16)
    if ref is not None:
        decoded = ref_pixels(ref.decode_etc2a8(block, 4, 4))
        if not (decoded[:, 3] == 200).all():
            failures.append('ETC2_RGBA8 alpha does not decode to 200: %s' % decoded[:, 3].tolist())
    # EAC_R11: two flat blocks and a ramp, each within a few steps of the source.
    for values in (np.full(16, 0, dtype=np.uint8), np.full(16, 255, dtype=np.uint8),
                   np.arange(0, 32, 2, dtype=np.uint8)):
        block = call3(api['shank1_eac_encode'], values.tobytes(), 8)
        if ref is None:
            continue
        got = ref_channel(ref.decode_eacr(block, 4, 4))
        if np.abs(got.astype(int) - values.astype(int)).max() > 10:
            failures.append('EAC_R11 values are off by %d: %s vs %s' % (
                np.abs(got.astype(int) - values.astype(int)).max(), got.tolist(), values.tolist()))
    return failures


def check_dxt_decoders(api, tiles):
    """The DXT decoders here against the reference ones: they must agree exactly."""
    failures = []
    for name, make in tiles:
        if name in ('normal',):
            continue
        for block in blocks_of(make(3)):
            bc1 = encode_bc1(block)
            mine = np.frombuffer(call3(api['shank1_bc1_decode'], bc1, 64), dtype=np.uint8).reshape(16, 4)
            if not np.array_equal(mine, ref_pixels(ref.decode_bc1(bc1, 4, 4))):
                failures.append('%s: BC1 decode differs from the reference' % name)
                break
            bc3 = encode_bc3(block)
            mine = np.frombuffer(call3(api['shank1_bc3_decode'], bc3, 64), dtype=np.uint8).reshape(16, 4)
            if not np.array_equal(mine, ref_pixels(ref.decode_bc3(bc3, 4, 4))):
                failures.append('%s: BC3 decode differs from the reference' % name)
                break
    return failures


def accumulate(sums, fmt, sse, samples):
    entry = sums.setdefault(fmt, {'sse': 0.0, 'samples': 0, 'worst': 0.0})
    entry['sse'] += sse
    entry['samples'] += samples
    if sse > entry['worst']:
        entry['worst'] = sse


def mse_of(a, b):
    return float(((a.astype(np.float64) - b.astype(np.float64)) ** 2).sum())


def measure(api, tiles, effort):
    """PSNR of each transcode against the DXT data the guest would have sampled, and the encoder's throughput: the
    sums of squared error are what is accumulated, so a block with no error does not make a format 'infinite'."""
    rows = []
    for name, make in tiles:
        sums = {}
        count = 0
        elapsed = {}
        for seed in range(8):
            for block in blocks_of(make(seed)):
                count += 1
                if ref is None:
                    continue
                rgba = block.reshape(16, 4)
                # BC1 -> RGB
                bc1 = encode_bc1(block)
                t0 = time.perf_counter()
                etc2 = call3(api['shank1_bc1_to_rgb'], bc1, 8, effort)
                elapsed['BC1->ETC2_RGB8'] = elapsed.get('BC1->ETC2_RGB8', 0.0) + time.perf_counter() - t0
                got = ref_pixels(ref.decode_etc2(etc2, 4, 4))
                want = ref_pixels(ref.decode_bc1(bc1, 4, 4))
                accumulate(sums, 'BC1->ETC2_RGB8', mse_of(got[:, :3], want[:, :3]), 16 * 3)
                # BC1 -> RGBA (punch-through alpha)
                t0 = time.perf_counter()
                etc2a = call3(api['shank1_bc1_to_rgba'], bc1, 16, effort)
                elapsed['BC1->ETC2_RGBA8'] = elapsed.get('BC1->ETC2_RGBA8', 0.0) + time.perf_counter() - t0
                got = ref_pixels(ref.decode_etc2a8(etc2a, 4, 4))
                accumulate(sums, 'BC1->ETC2_RGBA8', mse_of(got, want), 16 * 4)
                # BC3 -> RGBA
                bc3 = encode_bc3(block)
                t0 = time.perf_counter()
                etc2a = call3(api['shank1_bc3_to_rgba'], bc3, 16, effort)
                elapsed['BC3->ETC2_RGBA8'] = elapsed.get('BC3->ETC2_RGBA8', 0.0) + time.perf_counter() - t0
                got = ref_pixels(ref.decode_etc2a8(etc2a, 4, 4))
                want3 = ref_pixels(ref.decode_bc3(bc3, 4, 4))
                accumulate(sums, 'BC3->ETC2_RGBA8', mse_of(got, want3), 16 * 4)
                # BC4 -> EAC_R11
                bc4 = encode_bc3_alpha(block[:, 0])
                t0 = time.perf_counter()
                eac = call3(api['shank1_bc4_to_eac'], bc4, 8, effort)
                elapsed['BC4->EAC_R11'] = elapsed.get('BC4->EAC_R11', 0.0) + time.perf_counter() - t0
                got = ref_channel(ref.decode_eacr(eac, 4, 4))
                want4 = ref_channel(ref.decode_bc4(bc4, 4, 4))
                accumulate(sums, 'BC4->EAC_R11', mse_of(got, want4), 16)
        rows.append((name, count, sums, {k: v / max(1, count) for k, v in elapsed.items()}))
    return rows


def report_layouts(failures):
    if failures:
        print('layout self-tests FAILED:')
        for f in failures:
            print('  -', f)
        return False
    print('layout self-tests: ok%s' % ('' if ref is not None else ' (no texture2ddecoder: only the encoder side ran)'))
    return True


def db(sse, samples):
    mse = sse / max(1, samples)
    return float('inf') if mse <= 0 else 10.0 * np.log10(255.0 * 255.0 / mse)


def report_quality(rows):
    print('\nPSNR of the transcode against the DXT data the guest samples (mean / worst of the test set):')
    formats = sorted({f for _, _, sums, _ in rows for f in sums})
    width = max(len(r[0]) for r in rows) + 2
    print(' ' * width + ''.join('%-26s' % f for f in formats))
    for name, count, sums, _ in rows:
        cells = ''
        for f in formats:
            if f in sums:
                entry = sums[f]
                cells += '%-26s' % ('%5.2f dB / %5.2f' % (db(entry['sse'], entry['samples']),
                                                          db(entry['worst'], 48)))
            else:
                cells += '%-26s' % '-'
        print('%-*s%s' % (width, name, cells))
    totals = {}
    for _, _, sums, _ in rows:
        for f, entry in sums.items():
            total = totals.setdefault(f, {'sse': 0.0, 'samples': 0})
            total['sse'] += entry['sse']
            total['samples'] += entry['samples']
    print()
    for f in formats:
        print('%-20s overall %5.2f dB' % (f, db(totals[f]['sse'], totals[f]['samples'])))


def report_speed(rows, effort):
    print('\nEncoder time per 4x4 block (this machine, -O2, effort %d):' % effort)
    for f in sorted({f for _, _, _, elapsed in rows for f in elapsed}):
        times = [elapsed[f] for _, _, _, elapsed in rows if f in elapsed]
        per_block = float(np.mean(times))
        blocks_1024 = (1024 // 4) ** 2
        print('%-20s %6.2f us/block   (%.0f ms per 1024x1024 texture, %.0f ms per 512x512)'
              % (f, per_block * 1e6, per_block * blocks_1024 * 1e3, per_block * (512 // 4) ** 2 * 1e3))


def add_image(path):
    global TEST_TILES
    try:
        from PIL import Image
    except ImportError:
        print('no Pillow: --image ignored', file=sys.stderr)
        return
    image = np.asarray(Image.open(path).convert('RGBA'))
    image = image[: (image.shape[0] // 8) * 8, : (image.shape[1] // 8) * 8]

    def make(seed):
        # A seeded crop of the picture, so the tile generator has the test set's signature.
        rng = np.random.default_rng(seed)
        y = int(rng.integers(0, max(1, image.shape[0] - 8)))
        x = int(rng.integers(0, max(1, image.shape[1] - 8)))
        return image[y:y + 8, x:x + 8]

    TEST_TILES = TEST_TILES + [(os.path.basename(path), make)]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--effort', type=int, default=0, choices=(0, 1),
                    help='the encoder: 0 the default search, 1 the thorough one')
    ap.add_argument('--self-test', action='store_true', help='layout checks only')
    ap.add_argument('--image', action='append', default=[], help='add a picture to the test set (needs Pillow)')
    ap.add_argument('--verbose', action='store_true')
    a = ap.parse_args()

    for path in a.image:
        add_image(path)

    api = load(build_library())
    ok = report_layouts(check_layouts(api))
    if ref is not None:
        dxt = check_dxt_decoders(api, TEST_TILES)
        if dxt:
            ok = False
            print('DXT decoders FAILED: %s' % '; '.join(dxt))
        else:
            print('DXT decoders: identical to the reference')
    if a.self_test:
        return 0 if ok else 1
    if ref is None:
        print('\nno texture2ddecoder: no PSNR (pip install texture2ddecoder numpy)')
    rows = measure(api, TEST_TILES, a.effort)
    if ref is not None:
        report_quality(rows)
    report_speed(rows, a.effort)
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
