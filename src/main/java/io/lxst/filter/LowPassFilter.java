package io.lxst.filter;

/**
 * First-order IIR low-pass filter.
 * Mirrors Python LXST Filters.LowPass.
 */
public class LowPassFilter implements Filter {

    private final double cutFrequency;
    private int   _sampleRate = -1;
    private int   _channels   = -1;
    private float[] filterStates;
    private double alpha;

    public LowPassFilter(double cutFrequency) {
        this.cutFrequency = cutFrequency;
    }

    @Override
    public float[][] handleFrame(float[][] frame, int sampleRate) {
        if (frame.length == 0) return frame;

        if (sampleRate != _sampleRate) {
            _sampleRate = sampleRate;
            double dt = 1.0 / sampleRate;
            double rc = 1.0 / (2.0 * Math.PI * cutFrequency);
            alpha     = dt / (rc + dt);
        }

        int samples  = frame.length;
        int channels = frame[0].length;

        if (filterStates == null || _channels != channels) {
            _channels    = channels;
            filterStates = new float[channels];
        }

        float[][] output = new float[samples][channels];
        float a = (float) alpha;

        for (int c = 0; c < channels; c++) {
            output[0][c] = a * frame[0][c] + (1.0f - a) * filterStates[c];
            for (int s = 1; s < samples; s++) {
                output[s][c] = a * frame[s][c] + (1.0f - a) * output[s - 1][c];
            }
            filterStates[c] = output[samples - 1][c];
        }
        return output;
    }
}
