""" Generates hex file with librosa filters. """
import librosa
from fixedpoint import FixedPoint
import numpy as np


N_FFT = 512
SR = 16000
N_MELS = 20


filter_banks = librosa.filters.mel(n_fft=N_FFT,
                                   sr=SR,
                                   n_mels=N_MELS,
                                   fmin=0,
                                   fmax=((SR/2)+1),
                                   norm=None)

np.savetxt('mel_filters.csv', filter_banks, delimiter=',', fmt='%.4f')

val0 = True
hex_vals0 = []
hex_vals1 = []
for fbank in filter_banks:
    pre_zeros_done = False
    for ind, val in enumerate(fbank): 
        if val > 0.0001:
            pre_zeros_done = True
            if val0:
                hex_vals0.append(FixedPoint(val, signed=False, m=0, n=16))
            else:
                hex_vals1.append(FixedPoint(val, signed=False, m=0, n=16))
        elif not pre_zeros_done:
            if val0 and ind >= len(hex_vals0):
                hex_vals0.append(FixedPoint(val, signed=False, m=0, n=16))
            elif not val0 and ind >= len(hex_vals1):
                hex_vals1.append(FixedPoint(val, signed=False, m=0, n=16))
    val0 = not val0

fp_zero = FixedPoint(0.0, signed=False, m=0, n=16)
hex_vals0 = (hex_vals0 + ([fp_zero]*(257-len(hex_vals0)))) if len(hex_vals0) < 257 else hex_vals0
hex_vals1 = (hex_vals1 + ([fp_zero]*(257-len(hex_vals1)))) if len(hex_vals1) < 257 else hex_vals1

hexstr = ""
for i in range(0, len(filter_banks[0])):
    hv0 = hex_vals0[i]
    hv1 = hex_vals1[i]
    hexstr += f"{str(hv1)} {str(hv0)} // {float(hv1):.4f}, {float(hv0):.4f}\n"

print(hexstr[:-1])  # we use slicing to remove the last \n
