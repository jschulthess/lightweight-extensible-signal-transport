package io.lxst.primitive.telephony;

import java.util.Arrays;
import java.util.List;

/**
 * In-band signalling status codes for telephony.
 * Mirrors Python LXST Primitives.Telephony.Signalling.
 */
public final class Signalling {

    public static final int STATUS_BUSY        = 0x00;
    public static final int STATUS_REJECTED    = 0x01;
    public static final int STATUS_CALLING     = 0x02;
    public static final int STATUS_AVAILABLE   = 0x03;
    public static final int STATUS_RINGING     = 0x04;
    public static final int STATUS_CONNECTING  = 0x05;
    public static final int STATUS_ESTABLISHED = 0x06;
    public static final int PREFERRED_PROFILE  = 0xFF;

    public static final List<Integer> AUTO_STATUS_CODES = Arrays.asList(
        STATUS_CALLING, STATUS_AVAILABLE, STATUS_RINGING, STATUS_CONNECTING, STATUS_ESTABLISHED
    );

    private Signalling() {}
}
