package io.lxst.filter;

/**
 * Automatic Gain Control filter.
 * Mirrors Python LXST Filters.AGC.
 */
public class AGCFilter implements Filter {

    private static final double TRIGGER_LEVEL = 0.003;
    private static final double PEAK_LIMIT    = 0.75;

    private final double targetLevelDb;
    private final double maxGainDb;
    private final double attackTime;
    private final double releaseTime;
    private final double holdTime;

    private double targetLinear;
    private double maxGainLinear;

    private int   _sampleRate  = -1;
    private int   _channels    = -1;
    private float[] currentGainLin;
    private int   holdCounter  = 0;
    private double blockTargetS = 0.01;

    private double attackCoeff;
    private double releaseCoeff;
    private int    holdSamples;

    public AGCFilter() {
        this(-12.0, 12.0, 0.0001, 0.002, 0.001);
    }

    public AGCFilter(double targetLevelDb, double maxGainDb,
                     double attackTime, double releaseTime, double holdTime) {
        this.targetLevelDb = targetLevelDb;
        this.maxGainDb     = maxGainDb;
        this.attackTime    = attackTime;
        this.releaseTime   = releaseTime;
        this.holdTime      = holdTime;
        this.targetLinear  = Math.pow(10, targetLevelDb / 10.0);
        this.maxGainLinear = Math.pow(10, maxGainDb / 10.0);
    }

    @Override
    public float[][] handleFrame(float[][] frame, int sampleRate) {
        if (frame.length == 0) return frame;

        int samples  = frame.length;
        int channels = frame[0].length;

        if (sampleRate != _sampleRate) {
            _sampleRate   = sampleRate;
            attackCoeff   = 1.0 - Math.exp(-1.0 / (attackTime  * sampleRate));
            releaseCoeff  = 1.0 - Math.exp(-1.0 / (releaseTime * sampleRate));
            holdSamples   = (int) (holdTime * sampleRate);
        }

        if (_channels != channels) {
            _channels      = channels;
            currentGainLin = new float[channels];
            for (int c = 0; c < channels; c++) currentGainLin[c] = 1.0f;
            holdCounter    = 0;
        }

        float[][] output = new float[samples][channels];
        int blockSize = Math.max(1, samples / Math.max(1, (int) ((samples / (double) sampleRate) / blockTargetS)));

        for (int i = 0; i < samples; i += blockSize) {
            int blockEnd = Math.min(i + blockSize, samples);
            int blockLen = blockEnd - i;

            // Compute RMS per channel
            float[] rms = new float[channels];
            for (int s = i; s < blockEnd; s++) {
                for (int c = 0; c < channels; c++) rms[c] += frame[s][c] * frame[s][c];
            }
            for (int c = 0; c < channels; c++) rms[c] = (float) Math.sqrt(rms[c] / blockLen);

            for (int c = 0; c < channels; c++) {
                float targetGain = (rms[c] > 1e-9f)
                    ? Math.min((float) (targetLinear / rms[c]), (float) maxGainLinear)
                    : (float) maxGainLinear;

                if (rms[0] < TRIGGER_LEVEL) targetGain = currentGainLin[c];

                if (targetGain < currentGainLin[c]) {
                    currentGainLin[c] = (float) (attackCoeff  * targetGain + (1 - attackCoeff)  * currentGainLin[c]);
                    holdCounter = holdSamples;
                } else {
                    if (holdCounter > 0) holdCounter -= blockLen;
                    else currentGainLin[c] = (float) (releaseCoeff * targetGain + (1 - releaseCoeff) * currentGainLin[c]);
                }
            }

            for (int s = i; s < blockEnd; s++) {
                for (int c = 0; c < channels; c++) output[s][c] = frame[s][c] * currentGainLin[c];
            }
        }

        // Peak limiter
        float[] peaks = new float[channels];
        for (float[] row : output) {
            for (int c = 0; c < channels; c++) {
                float abs = Math.abs(row[c]);
                if (abs > peaks[c]) peaks[c] = abs;
            }
        }
        for (int c = 0; c < channels; c++) {
            if (peaks[c] > PEAK_LIMIT) {
                float limitGain = (float) (PEAK_LIMIT / peaks[c]);
                for (float[] row : output) row[c] *= limitGain;
            }
        }
        return output;
    }
}
