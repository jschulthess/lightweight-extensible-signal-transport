package io.lxst.primitive.telephony;

import io.lxst.codec.*;

/**
 * Telephony quality/bandwidth profiles.
 * Mirrors Python LXST Primitives.Telephony.Profiles.
 */
public final class Profiles {

    public static final int BANDWIDTH_ULTRA_LOW = 0x10;
    public static final int BANDWIDTH_VERY_LOW  = 0x20;
    public static final int BANDWIDTH_LOW       = 0x30;
    public static final int QUALITY_MEDIUM      = 0x40;
    public static final int QUALITY_HIGH        = 0x50;
    public static final int QUALITY_MAX         = 0x60;
    public static final int LATENCY_ULTRA_LOW   = 0x70;
    public static final int LATENCY_LOW         = 0x80;

    public static final int DEFAULT_PROFILE = QUALITY_MEDIUM;

    private static final int[] AVAILABLE = {
        BANDWIDTH_ULTRA_LOW, BANDWIDTH_VERY_LOW, BANDWIDTH_LOW,
        QUALITY_MEDIUM, QUALITY_HIGH, QUALITY_MAX,
        LATENCY_ULTRA_LOW, LATENCY_LOW
    };

    private Profiles() {}

    public static int[] availableProfiles() { return AVAILABLE.clone(); }

    public static int profileIndex(int profile) {
        for (int i = 0; i < AVAILABLE.length; i++) if (AVAILABLE[i] == profile) return i;
        return -1;
    }

    public static String profileName(int profile) {
        switch (profile) {
            case BANDWIDTH_ULTRA_LOW: return "Ultra Low Bandwidth";
            case BANDWIDTH_VERY_LOW:  return "Very Low Bandwidth";
            case BANDWIDTH_LOW:       return "Low Bandwidth";
            case QUALITY_MEDIUM:      return "Medium Quality";
            case QUALITY_HIGH:        return "High Quality";
            case QUALITY_MAX:         return "Super High Quality";
            case LATENCY_LOW:         return "Low Latency";
            case LATENCY_ULTRA_LOW:   return "Ultra Low Latency";
            default:                  return "Default";
        }
    }

    public static String profileAbbreviation(int profile) {
        switch (profile) {
            case BANDWIDTH_ULTRA_LOW: return "ULBW";
            case BANDWIDTH_VERY_LOW:  return "VLBW";
            case BANDWIDTH_LOW:       return "LBW";
            case QUALITY_MEDIUM:      return "MQ";
            case QUALITY_HIGH:        return "HQ";
            case QUALITY_MAX:         return "SHQ";
            case LATENCY_LOW:         return "LL";
            case LATENCY_ULTRA_LOW:   return "ULL";
            default:                  return "DFLT";
        }
    }

    public static Codec getCodec(int profile) {
        switch (profile) {
            case BANDWIDTH_ULTRA_LOW: return new Codec2Codec(Codec2Codec.CODEC2_700C);
            case BANDWIDTH_VERY_LOW:  return new Codec2Codec(Codec2Codec.CODEC2_1600);
            case BANDWIDTH_LOW:       return new Codec2Codec(Codec2Codec.CODEC2_3200);
            case QUALITY_MEDIUM:      return new OpusCodec(OpusCodec.PROFILE_VOICE_MEDIUM);
            case QUALITY_HIGH:        return new OpusCodec(OpusCodec.PROFILE_VOICE_HIGH);
            case QUALITY_MAX:         return new OpusCodec(OpusCodec.PROFILE_VOICE_MAX);
            case LATENCY_LOW:         return new OpusCodec(OpusCodec.PROFILE_VOICE_MEDIUM);
            case LATENCY_ULTRA_LOW:   return new OpusCodec(OpusCodec.PROFILE_VOICE_MEDIUM);
            default:                  return new OpusCodec(OpusCodec.PROFILE_VOICE_MEDIUM);
        }
    }

    public static int getFrameTime(int profile) {
        switch (profile) {
            case BANDWIDTH_ULTRA_LOW: return 400;
            case BANDWIDTH_VERY_LOW:  return 320;
            case BANDWIDTH_LOW:       return 200;
            case QUALITY_MEDIUM:      return 60;
            case QUALITY_HIGH:        return 60;
            case QUALITY_MAX:         return 60;
            case LATENCY_LOW:         return 20;
            case LATENCY_ULTRA_LOW:   return 10;
            default:                  return 60;
        }
    }

    public static int nextProfile(int profile) {
        int idx = profileIndex(profile);
        if (idx < 0) return -1;
        return AVAILABLE[(idx + 1) % AVAILABLE.length];
    }
}
