import numpy as np
import matplotlib.pyplot as plt
import librosa

sr = 16000
frame_length = 512
frame_step = frame_length
num_frames = 32
act_len = num_frames * frame_length # 16384


def to_12_bit_unsigned(signal):
    signal = (signal.astype(np.float32)*(2**16)) + 2**15
    signal = signal / 2**4
    signal = np.clip(signal, 0, 4095)
    return signal

def frame_signal(signal):
    signal = np.pad(signal, (0, act_len-len(signal))) # pad after
    
    signal_length = len(signal)
    assert signal_length == act_len
    
    frames = signal.reshape([num_frames, frame_length])
    frames *= np.hamming(frame_length)
    return frames


def preprocess_frames(frames, n_mels=20):
    fft_res = np.fft.rfft(frames)
    mag_frames = (fft_res.real / 2**9)**2  # No imaginary component!
    filter_banks = librosa.filters.mel(n_fft=frame_length, sr=sr, n_mels=n_mels, fmin=0, fmax=((sr/2)+1), norm=None)
    if n_mels > 0:
        mels = np.dot(filter_banks, mag_frames.T)
        mels = np.where(mels == 0, np.finfo(float).eps, mels)  # Numerical Stability
    else:
        mels = mag_frames
    log_mels = np.log2(mels, dtype=np.float32) * 8
    return np.floor(log_mels)


def preprocess(signal, n_mels=20):
    frames = frame_signal(to_12_bit_unsigned(signal))
    return preprocess_frames(frames, n_mels=n_mels)


length_sec = 1  # seconds
fs = 16000
tone_freq = 200
time_axis = np.linspace(0, length_sec, length_sec * fs)
sine_wave = np.sin(2*np.pi*tone_freq*time_axis)
stft = preprocess_frames(sine_wave, n_mels=0)
tone = (sine_wave[0:512] + 1) * 2047 * 0.8
win_tone = tone * np.hamming(512)
fft_res = np.fft.rfft(win_tone, norm='forward')

print('First 10 elements of fft: ' + ''.join([f'{fft_res[i].real:.3f}, ' for i in range(10)]))

fig, axis = plt.subplots(3, 1)
axis[0].plot(tone)
axis[0].set_title('pure tone')
axis[1].plot(win_tone)
axis[1].set_title('windowed tone')
axis[2].plot(fft_res)
axis[2].set_title('fft of tone')
plt.show()


