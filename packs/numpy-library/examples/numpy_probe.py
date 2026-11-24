import numpy as np

print("NumPy version:", np.__version__)
a = np.arange(1, 7).reshape(2, 3)
assert a.sum() == 21
assert np.dot([1, 2, 3], [4, 5, 6]) == 32
print("array/dot: OK")
matrix = np.array([[3.0, 1.0], [1.0, 2.0]])
target = np.array([9.0, 8.0])
answer = np.linalg.solve(matrix, target)
assert np.allclose(answer, [2.0, 3.0])
assert np.allclose(matrix @ answer, target)
print("solve:", answer.tolist())
signal = np.array([0.0, 1.0, 0.0, -1.0])
spectrum = np.fft.fft(signal)
assert np.allclose(np.fft.ifft(spectrum).real, signal)
print("fft: OK")
np.save("numpy_probe.npy", matrix)
assert np.array_equal(np.load("numpy_probe.npy"), matrix)
print("save/load: OK")
print("NUMPY_ALL_OK")
