package io.lxst.filter;

/**
 * Band-pass filter composed of a high-pass and low-pass filter in series.
 * Mirrors Python LXST Filters.BandPass.
 */
public class BandPassFilter implements Filter {

    private final HighPassFilter highPass;
    private final LowPassFilter  lowPass;

    public BandPassFilter(double lowCut, double highCut) {
        if (lowCut >= highCut)
            throw new IllegalArgumentException("Low-cut frequency must be less than high-cut frequency");
        this.highPass = new HighPassFilter(lowCut);
        this.lowPass  = new LowPassFilter(highCut);
    }

    @Override
    public float[][] handleFrame(float[][] frame, int sampleRate) {
        if (frame.length == 0) return frame;
        float[][] highPassed = highPass.handleFrame(frame, sampleRate);
        return lowPass.handleFrame(highPassed, sampleRate);
    }
}
