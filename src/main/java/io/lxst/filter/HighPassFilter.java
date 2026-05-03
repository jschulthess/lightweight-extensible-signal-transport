package io.lxst.filter;

/**
 * First-order IIR high-pass filter.
 * Mirrors Python LXST Filters.HighPass.
 */
public class HighPassFilter implements Filter {

    private final double cutFrequency;
    private int   _sampleRate = -1;
    private int   _channels   = -1;
    private float[] filterStates;
    private float[] lastInputs;
    private double  alpha;

    public HighPassFilter(double cutFrequency) {
        this.cutFrequency = cutFrequency;
    }

    @Override
    public float[][] handleFrame(float[][] frame, int sampleRate) {
        if (frame.length == 0) return frame;

        if (sampleRate != _sampleRate) {
            _sampleRate = sampleRate;
            double dt = 1.0 / sampleRate;
            double rc = 1.0 / (2.0 * Math.PI * cutFrequency);
            alpha     = rc / (rc + dt);
        }

        int samples  = frame.length;
        int channels = frame[0].length;

        if (filterStates == null || _channels != channels) {
            _channels    = channels;
            filterStates = new float[channels];
            lastInputs   = new float[channels];
        }

        float[][] output = new float[samples][channels];
        for (int c = 0; c < channels; c++) {
            float prevOut   = filterStates[c];
            float prevIn    = lastInputs[c];
            float a         = (float) alpha;

            output[0][c] = a * (prevOut + frame[0][c] - prevIn);
            for (int s = 1; s < samples; s++) {
                output[s][c] = a * (output[s - 1][c] + frame[s][c] - frame[s - 1][c]);
            }
            filterStates[c] = output[samples - 1][c];
            lastInputs[c]   = frame[samples - 1][c];
        }
        return output;
    }
}
