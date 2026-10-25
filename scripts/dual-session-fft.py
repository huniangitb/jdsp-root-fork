#!/usr/bin/env python3
"""Per-second narrowband analysis of the P0.5 recording (48k s16 stereo PCM).
Band energy around 440 Hz (session A) and 880 Hz (session B)."""
import numpy as np, sys

SR = 48000
path = sys.argv[1] if len(sys.argv) > 1 else '/tmp/p0/p05.pcm'
raw = np.fromfile(path, dtype='<i2').astype(np.float64) / 32768.0
n1 = SR * 2  # one second stereo
nsec = len(raw) // n1
print('sec  rms_dbfs  e440_dB   e880_dB   verdict')
for s in range(nsec):
    x = raw[s * n1:(s + 1) * n1].reshape(-1, 2).mean(axis=1)  # mono mixdown
    x = x * np.hanning(len(x))
    rms = np.sqrt(np.mean(raw[s * n1:(s + 1) * n1] ** 2))
    spec = 20 * np.log10(np.abs(np.fft.rfft(x)) / len(x) + 1e-12)
    freqs = np.fft.rfftfreq(len(x), 1 / SR)
    def band(f, w=12.0):
        m = (freqs > f - w) & (freqs < f + w)
        return spec[m].max()
    e440, e880 = band(440), band(880)
    verdict = ''
    if s >= 4:
        v4 = 'A_on ' if e440 > -55 else 'A_off'
        v8 = 'B_on ' if e880 > -55 else 'B_off'
        verdict = v4 + v8
    print(f'{s:3d}  {20*np.log10(rms+1e-12):8.1f}  {e440:8.1f}  {e880:8.1f}  {verdict}')
