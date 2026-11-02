# SPDX-License-Identifier: GPL-3.0-or-later
"""Minimal QR code encoder (byte mode, error correction level M, versions 1-10).

Blender ships without a QR library, so this is a small self-contained implementation
following the ISO/IEC 18004 algorithm (structure modelled on Project Nayuki's reference).
Enough for the ~60 byte connection payload the receiver shows.
"""

# Error correction level M, indexed by version (index 0 unused).
_ECC_PER_BLOCK = (None, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
_NUM_BLOCKS = (None, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
_FORMAT_BITS_M = 0  # ECC level M encodes as 00

_MASKS = (
    lambda x, y: (x + y) % 2 == 0,
    lambda x, y: y % 2 == 0,
    lambda x, y: x % 3 == 0,
    lambda x, y: (x + y) % 3 == 0,
    lambda x, y: (x // 3 + y // 2) % 2 == 0,
    lambda x, y: x * y % 2 + x * y % 3 == 0,
    lambda x, y: (x * y % 2 + x * y % 3) % 2 == 0,
    lambda x, y: ((x + y) % 2 + x * y % 3) % 2 == 0,
)


def encode(text):
    """Returns the QR code for ``text`` as a list of rows of booleans (True = dark)."""
    data = text.encode("utf-8")
    for version in range(1, 11):
        cc_bits = 8 if version < 10 else 16
        if 4 + cc_bits + 8 * len(data) <= _num_data_codewords(version) * 8:
            break
    else:
        raise ValueError("Text too long for QR versions 1-10 (%d bytes)" % len(data))
    return _QrCode(version, _make_codewords(version, data, cc_bits)).modules


def _num_raw_data_modules(ver):
    result = (16 * ver + 128) * ver + 64
    if ver >= 2:
        numalign = ver // 7 + 2
        result -= (25 * numalign - 10) * numalign - 55
        if ver >= 7:
            result -= 36
    return result


def _num_data_codewords(ver):
    return _num_raw_data_modules(ver) // 8 - _ECC_PER_BLOCK[ver] * _NUM_BLOCKS[ver]


def _make_codewords(version, data, cc_bits):
    bits = []

    def append(value, length):
        for i in reversed(range(length)):
            bits.append((value >> i) & 1)

    append(0b0100, 4)  # byte mode
    append(len(data), cc_bits)
    for b in data:
        append(b, 8)
    capacity = _num_data_codewords(version) * 8
    append(0, min(4, capacity - len(bits)))  # terminator
    append(0, (-len(bits)) % 8)  # pad to a byte
    pad = 0xEC
    while len(bits) < capacity:
        append(pad, 8)
        pad ^= 0xEC ^ 0x11
    codewords = [0] * (len(bits) // 8)
    for i, bit in enumerate(bits):
        codewords[i >> 3] |= bit << (7 - (i & 7))
    return codewords


# ---------------------------------------------------------------- Reed-Solomon (GF(2^8), poly 0x11D)

def _rs_multiply(x, y):
    z = 0
    for i in reversed(range(8)):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def _rs_divisor(degree):
    result = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(degree):
            result[j] = _rs_multiply(result[j], root)
            if j + 1 < degree:
                result[j] ^= result[j + 1]
        root = _rs_multiply(root, 0x02)
    return result


def _rs_remainder(data, divisor):
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result.pop(0)
        result.append(0)
        for i, coef in enumerate(divisor):
            result[i] ^= _rs_multiply(coef, factor)
    return result


# ---------------------------------------------------------------- symbol construction

class _QrCode:
    def __init__(self, version, codewords):
        self.version = version
        self.size = version * 4 + 17
        self.modules = [[False] * self.size for _ in range(self.size)]
        self.is_function = [[False] * self.size for _ in range(self.size)]

        self._draw_function_patterns()
        self._draw_codewords(self._add_ecc_and_interleave(codewords))

        best_mask, best_penalty = 0, None
        for mask in range(8):
            self._apply_mask(mask)
            self._draw_format_bits(mask)
            penalty = self._penalty()
            if best_penalty is None or penalty < best_penalty:
                best_mask, best_penalty = mask, penalty
            self._apply_mask(mask)  # XOR again to undo
        self._apply_mask(best_mask)
        self._draw_format_bits(best_mask)

    def _set_function(self, x, y, dark):
        self.modules[y][x] = dark
        self.is_function[y][x] = True

    def _alignment_positions(self):
        ver = self.version
        if ver == 1:
            return []
        numalign = ver // 7 + 2
        step = (ver * 8 + numalign * 3 + 5) // (numalign * 4 - 4) * 2
        result = [self.size - 7 - i * step for i in range(numalign - 1)] + [6]
        return list(reversed(result))

    def _draw_function_patterns(self):
        size = self.size
        for i in range(size):  # timing patterns
            self._set_function(6, i, i % 2 == 0)
            self._set_function(i, 6, i % 2 == 0)
        self._draw_finder(3, 3)
        self._draw_finder(size - 4, 3)
        self._draw_finder(3, size - 4)
        pos = self._alignment_positions()
        n = len(pos)
        skips = {(0, 0), (0, n - 1), (n - 1, 0)}
        for i in range(n):
            for j in range(n):
                if (i, j) not in skips:
                    self._draw_alignment(pos[i], pos[j])
        self._draw_format_bits(0)  # reserve the area; real bits are drawn after masking
        self._draw_version()

    def _draw_finder(self, x, y):
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                xx, yy = x + dx, y + dy
                if 0 <= xx < self.size and 0 <= yy < self.size:
                    self._set_function(xx, yy, max(abs(dx), abs(dy)) not in (2, 4))

    def _draw_alignment(self, x, y):
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                self._set_function(x + dx, y + dy, max(abs(dx), abs(dy)) != 1)

    def _draw_format_bits(self, mask):
        data = _FORMAT_BITS_M << 3 | mask
        rem = data
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        bits = (data << 10 | rem) ^ 0x5412

        def bit(i):
            return (bits >> i) & 1 != 0

        for i in range(0, 6):
            self._set_function(8, i, bit(i))
        self._set_function(8, 7, bit(6))
        self._set_function(8, 8, bit(7))
        self._set_function(7, 8, bit(8))
        for i in range(9, 15):
            self._set_function(14 - i, 8, bit(i))
        size = self.size
        for i in range(0, 8):
            self._set_function(size - 1 - i, 8, bit(i))
        for i in range(8, 15):
            self._set_function(8, size - 15 + i, bit(i))
        self._set_function(8, size - 8, True)  # always-dark module

    def _draw_version(self):
        if self.version < 7:
            return
        rem = self.version
        for _ in range(12):
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        bits = self.version << 12 | rem
        for i in range(18):
            dark = (bits >> i) & 1 != 0
            a = self.size - 11 + i % 3
            b = i // 3
            self._set_function(a, b, dark)
            self._set_function(b, a, dark)

    def _add_ecc_and_interleave(self, data):
        ver = self.version
        numblocks = _NUM_BLOCKS[ver]
        blockecclen = _ECC_PER_BLOCK[ver]
        rawcodewords = _num_raw_data_modules(ver) // 8
        numshortblocks = numblocks - rawcodewords % numblocks
        shortblocklen = rawcodewords // numblocks
        divisor = _rs_divisor(blockecclen)
        blocks = []
        k = 0
        for i in range(numblocks):
            datlen = shortblocklen - blockecclen + (0 if i < numshortblocks else 1)
            dat = data[k:k + datlen]
            k += datlen
            ecc = _rs_remainder(dat, divisor)
            if i < numshortblocks:
                dat = dat + [0]
            blocks.append(dat + ecc)
        result = []
        for i in range(len(blocks[0])):
            for j, blk in enumerate(blocks):
                if i != shortblocklen - blockecclen or j >= numshortblocks:
                    result.append(blk[i])
        return result

    def _draw_codewords(self, data):
        size = self.size
        i = 0
        total_bits = len(data) * 8
        right = size - 1
        while right >= 1:
            if right == 6:
                right = 5
            for vert in range(size):
                for j in range(2):
                    x = right - j
                    upward = ((right + 1) & 2) == 0
                    y = size - 1 - vert if upward else vert
                    if not self.is_function[y][x] and i < total_bits:
                        self.modules[y][x] = (data[i >> 3] >> (7 - (i & 7))) & 1 != 0
                        i += 1
            right -= 2

    def _apply_mask(self, mask):
        fn = _MASKS[mask]
        for y in range(self.size):
            row, func = self.modules[y], self.is_function[y]
            for x in range(self.size):
                if not func[x] and fn(x, y):
                    row[x] = not row[x]

    def _penalty(self):
        """Standard penalty rules N1-N4 (straightforward, unoptimised version)."""
        size, m = self.size, self.modules
        score = 0
        lines = [m[y] for y in range(size)] + [[m[y][x] for y in range(size)] for x in range(size)]
        finder_a = [True, False, True, True, True, False, True, False, False, False, False]
        finder_b = finder_a[::-1]
        for line in lines:
            run_color, run_len = None, 0
            for v in line:
                if v == run_color:
                    run_len += 1
                else:
                    if run_len >= 5:
                        score += run_len - 2
                    run_color, run_len = v, 1
            if run_len >= 5:
                score += run_len - 2
            padded = [False] * 4 + list(line) + [False] * 4
            for i in range(len(padded) - 10):
                window = padded[i:i + 11]
                if window == finder_a or window == finder_b:
                    score += 40
        for y in range(size - 1):
            for x in range(size - 1):
                c = m[y][x]
                if c == m[y][x + 1] == m[y + 1][x] == m[y + 1][x + 1]:
                    score += 3
        dark = sum(sum(1 for v in row if v) for row in m)
        total = size * size
        k = (abs(dark * 20 - total * 10) + total - 1) // total - 1
        score += max(k, 0) * 10
        return score
