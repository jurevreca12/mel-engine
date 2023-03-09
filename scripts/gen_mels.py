""" Generates hex file with librosa filters. """
import librosa
from fixedpoint import FixedPoint

N_FFT = 512
SR = 16000
N_MELS = 20


filter_banks = librosa.filters.mel(n_fft=N_FFT,
                                   sr=SR,
                                   n_mels=N_MELS,
                                   fmin=0,
                                   fmax=((SR/2)+1),
                                   norm=None)


hex_vals0 = []
hex_vals1 = []
for i in range(len(filter_banks[0])):
    vals = []
    for x in filter_banks[:, i]:
        if x > 0.0001:
            vals.append(x)
    assert len(vals) <= 2
    if len(vals) == 0:
        vals.append(0.0)
        vals.append(0.0)
    elif len(vals) == 1:
        vals.append(0.0)

    hex_vals0.append(FixedPoint(vals[0], signed=False, m=0, n=16))
    hex_vals1.append(FixedPoint(vals[1], signed=False, m=0, n=16))

hexstr = ""
for i in range(0, len(filter_banks[0])):
    hv0 = hex_vals0[i]
    hv1 = hex_vals1[i]
    hexstr += f"{str(hv1)} {str(hv0)} // {float(hv1):.4f}, {float(hv0):.4f}\n"

print(hexstr[:-1])  # we use slicing to remove the last \n
