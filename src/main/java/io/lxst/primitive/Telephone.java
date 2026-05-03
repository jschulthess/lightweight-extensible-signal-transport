package io.lxst.primitive;

import io.lxst.LXST;
import io.lxst.Mixer;
import io.lxst.Pipeline;
import io.lxst.codec.Codec;
import io.lxst.codec.NullCodec;
import io.lxst.codec.RawCodec;
import io.lxst.filter.AGCFilter;
import io.lxst.filter.BandPassFilter;
import io.lxst.filter.Filter;
import io.lxst.generator.ToneSource;
import io.lxst.network.LinkSource;
import io.lxst.network.Packetizer;
import io.lxst.network.SignallingReceiver;
import io.lxst.primitive.telephony.Profiles;
import io.lxst.primitive.telephony.Signalling;
import io.lxst.sink.LineSink;
import io.lxst.source.LineSource;
import io.lxst.source.OpusFileSource;
import io.reticulum.Transport;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.destination.ProofStrategy;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Full VoIP telephony over Reticulum.
 * Mirrors Python LXST Primitives.Telephony.Telephone.
 */
public class Telephone extends SignallingReceiver {

    private static final Logger LOG = Logger.getLogger(Telephone.class.getName());

    public static final int    RING_TIME             = 60;
    public static final int    WAIT_TIME             = 70;
    public static final int    CONNECT_TIME          = 5;
    public static final double DIAL_TONE_FREQUENCY   = 382.0;
    public static final double DIAL_TONE_EASE_MS     = 3.14159;
    public static final int    JOB_INTERVAL          = 5;
    public static final int    ANNOUNCE_INTERVAL_MIN = 60 * 5;
    public static final int    ANNOUNCE_INTERVAL     = 60 * 60 * 3;
    public static final int    ALLOW_ALL             = 0xFF;
    public static final int    ALLOW_NONE            = 0xFE;

    private static final String PRIMITIVE_NAME = "telephony";

    // ── Per-call state ────────────────────────────────────────────────────────

    private static class CallState {
        final Link link;
        boolean isIncoming;
        boolean isOutgoing;
        boolean ringTimeout     = false;
        boolean answered        = false;
        boolean isTerminating   = false;
        boolean pipelinesOpened = false;
        Integer profile         = null;
        Packetizer packetizer   = null;
        LinkSource audioSource  = null;
        List<Filter> filters    = null;

        CallState(Link link, boolean isIncoming) {
            this.link       = link;
            this.isIncoming = isIncoming;
            this.isOutgoing = !isIncoming;
        }

        byte[]     getHash()           { return link.getHash(); }
        Identity   getRemoteIdentity() { return link.getRemoteIdentity(); }
        LinkStatus getStatus()         { return link.getStatus(); }
        void       teardown()          { link.teardown(); }
        void       identify(Identity id) { link.identify(id); }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final Identity identity;
    private volatile Destination destination;

    // allowed can be ALLOW_ALL, ALLOW_NONE (Integer), List<byte[]>, or Predicate<byte[]>
    private Object allowed;
    private List<byte[]> blocked;
    private long lastAnnounce    = 0;
    private int  announceInterval = ANNOUNCE_INTERVAL;

    private final ReentrantLock callHandlerLock       = new ReentrantLock(true);
    private final ReentrantLock pipelineLock           = new ReentrantLock(true);
    private final ReentrantLock callerPipelineOpenLock = new ReentrantLock(true);
    private final ReentrantLock ringerLock             = new ReentrantLock(true);

    private int    establishmentTimeout = CONNECT_TIME;
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private int    ringTime;
    private int    waitTime;
    private Double autoAnswer;
    private float  receiveGain;
    private float  transmitGain;
    private boolean useAgc = true;

    private volatile CallState activeCall   = null;
    private volatile int       callStatus   = Signalling.STATUS_AVAILABLE;
    private volatile boolean   externalBusy = false;

    private Consumer<Identity> ringingCallback    = null;
    private Consumer<Identity> establishedCallback = null;
    private Consumer<Identity> endedCallback       = null;
    private Consumer<Identity> busyCallback        = null;
    private Consumer<Identity> rejectedCallback    = null;

    private Integer targetFrameTimeMs = null;

    private LineSink   audioOutput = null;
    private LineSource audioInput  = null;
    private ToneSource dialTone    = null;
    private double dialToneFrequency = DIAL_TONE_FREQUENCY;
    private double dialToneEaseMs    = DIAL_TONE_EASE_MS;
    private double busyToneSeconds   = 4.25;

    private Codec    transmitCodec    = null;
    private Codec    receiveCodec     = null;
    private Mixer    receiveMixer     = null;
    private Mixer    transmitMixer    = null;
    private Pipeline receivePipeline  = null;
    private Pipeline transmitPipeline = null;
    private boolean  receiveMuted     = false;
    private boolean  transmitMuted    = false;

    private LineSink       ringerOutput   = null;
    private Pipeline       ringerPipeline = null;
    private OpusFileSource ringerSource   = null;
    private String  ringtonePath     = null;
    private double  ringtoneGain     = 0.0;
    private String  speakerDevice    = null;
    private String  microphoneDevice = null;
    private String  ringerDevice     = null;
    private boolean lowLatencyOutput = false;

    // ── Constructors ──────────────────────────────────────────────────────────

    public Telephone(Identity identity) {
        this(identity, RING_TIME, WAIT_TIME, null, ALLOW_ALL, 0.0f, 0.0f);
    }

    public Telephone(Identity identity, int ringTime, int waitTime, Double autoAnswer,
                     int allowed, float receiveGain, float transmitGain) {
        super();
        this.identity     = identity;
        this.ringTime     = ringTime;
        this.waitTime     = waitTime;
        this.autoAnswer   = autoAnswer;
        this.allowed      = allowed;
        this.receiveGain  = receiveGain;
        this.transmitGain = transmitGain;

        this.destination = new Destination(identity, Direction.IN, DestinationType.SINGLE,
                                           LXST.APP_NAME, PRIMITIVE_NAME);
        this.destination.setProofStrategy(ProofStrategy.PROVE_NONE);
        this.destination.setLinkEstablishedCallback(this::incomingLinkEstablished);

        Thread jobThread = new Thread(this::jobs, "lxst-telephone-jobs");
        jobThread.setDaemon(true);
        jobThread.start();

        LOG.fine(this + " listening on " + hexRep(destination.getHash()));
    }

    // ── Teardown / announce ───────────────────────────────────────────────────

    public void teardown() {
        hangup();
        // Python calls RNS.Transport.deregister_destination() here; no equivalent API in the Java RNS stack.
        destination = null;
    }

    public void announce() { announce(null); }

    public void announce(ConnectionInterface attachedInterface) {
        if (destination != null) {
            if (attachedInterface != null) destination.announce(false, null, attachedInterface);
            else                           destination.announce();
            lastAnnounce = System.currentTimeMillis() / 1000L;
        }
    }

    // ── Access control ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public void setAllowed(Object allowed) {
        if (allowed instanceof Integer) {
            int v = (Integer) allowed;
            if (v == ALLOW_ALL || v == ALLOW_NONE) { this.allowed = allowed; return; }
        }
        if (allowed instanceof List || allowed instanceof Predicate) { this.allowed = allowed; return; }
        throw new IllegalArgumentException("Invalid type for allowed callers: " + allowed.getClass());
    }

    public void setBlocked(List<byte[]> blocked) { this.blocked = blocked; }

    @SuppressWarnings("unchecked")
    private boolean isAllowed(Identity remoteIdentity) {
        byte[] hash = remoteIdentity.getHash();
        if (blocked != null) {
            for (byte[] b : blocked) if (Arrays.equals(b, hash)) return false;
        }
        if (allowed instanceof Integer) {
            int v = (Integer) allowed;
            if (v == ALLOW_ALL)  return true;
            if (v == ALLOW_NONE) return false;
        }
        if (allowed instanceof List) {
            for (byte[] b : (List<byte[]>) allowed) if (Arrays.equals(b, hash)) return true;
            return false;
        }
        if (allowed instanceof Predicate) return ((Predicate<byte[]>) allowed).test(hash);
        return false;
    }

    // ── Configuration setters ─────────────────────────────────────────────────

    public void setConnectTimeout(int timeout) { this.establishmentTimeout = timeout; }

    public void setAnnounceInterval(int interval) {
        if (interval < ANNOUNCE_INTERVAL_MIN) interval = ANNOUNCE_INTERVAL_MIN;
        this.announceInterval = interval;
    }

    public void setRingingCallback(Consumer<Identity> cb)    { ringingCallback    = cb; }
    public void setEstablishedCallback(Consumer<Identity> cb) { establishedCallback = cb; }
    public void setEndedCallback(Consumer<Identity> cb)      { endedCallback      = cb; }
    public void setBusyCallback(Consumer<Identity> cb)       { busyCallback       = cb; }
    public void setRejectedCallback(Consumer<Identity> cb)   { rejectedCallback   = cb; }

    public void setSpeaker(String device)    { speakerDevice    = device; LOG.fine(this + " speaker set to " + device); }
    public void setMicrophone(String device) { microphoneDevice = device; LOG.fine(this + " microphone set to " + device); }
    public void setRinger(String device)     { ringerDevice     = device; LOG.fine(this + " ringer set to " + device); }

    public void setRingtone(String path)              { setRingtone(path, 0.0); }
    public void setRingtone(String path, double gain) { ringtonePath = path; ringtoneGain = gain; LOG.fine(this + " ringtone set to " + path); }

    public void setBusyToneTime(double seconds) { busyToneSeconds = seconds; }
    public void enableAgc(boolean enable)       { useAgc = enable; }
    public void disableAgc(boolean disable)     { useAgc = !disable; }

    public void setLowLatencyOutput(boolean enabled) {
        lowLatencyOutput = enabled;
        LOG.fine(this + " low-latency output " + (enabled ? "enabled" : "disabled"));
    }

    public void setBusy(boolean busy) { externalBusy = busy; }

    // ── Properties ────────────────────────────────────────────────────────────

    public boolean isBusy()           { return callStatus != Signalling.STATUS_AVAILABLE || externalBusy; }
    public Integer getActiveProfile() { return activeCall != null ? activeCall.profile : null; }
    public int     getCallStatus()    { return callStatus; }

    public boolean isReceiveMuted()  { return activeCall != null && receiveMixer  != null && receiveMixer.isMuted(); }
    public boolean isTransmitMuted() { return activeCall != null && transmitMixer != null && transmitMixer.isMuted(); }

    // ── Background jobs ───────────────────────────────────────────────────────

    private void jobs() {
        while (destination != null) {
            try { Thread.sleep(JOB_INTERVAL * 1000L); } catch (InterruptedException e) { break; }
            long now = System.currentTimeMillis() / 1000L;
            if (now > lastAnnounce + announceInterval && destination != null) announce();
        }
    }

    // ── Timeout threads ───────────────────────────────────────────────────────

    private void timeoutIncomingCallAt(CallState call, long timeoutSec) {
        Thread t = new Thread(() -> {
            while (System.currentTimeMillis() / 1000L < timeoutSec && activeCall == call) {
                try { Thread.sleep(250); } catch (InterruptedException e) { return; }
            }
            if (activeCall == call && callStatus < Signalling.STATUS_ESTABLISHED) {
                LOG.fine("Ring timeout on call from " + hexRep(activeCall.getHash()) + ", hanging up");
                activeCall.ringTimeout = true;
                hangup();
            }
        }, "lxst-ring-timeout");
        t.setDaemon(true);
        t.start();
    }

    private void timeoutOutgoingCallAt(CallState call, long timeoutSec) {
        Thread t = new Thread(() -> {
            while (System.currentTimeMillis() / 1000L < timeoutSec && activeCall == call) {
                try { Thread.sleep(250); } catch (InterruptedException e) { return; }
            }
            if (activeCall == call && callStatus < Signalling.STATUS_ESTABLISHED) {
                LOG.fine("Timeout on outgoing call to " + hexRep(activeCall.getHash()) + ", hanging up");
                hangup();
            }
        }, "lxst-outgoing-timeout");
        t.setDaemon(true);
        t.start();
    }

    private void timeoutOutgoingEstablishmentAt(CallState call, long timeoutSec) {
        Thread t = new Thread(() -> {
            while (System.currentTimeMillis() / 1000L < timeoutSec && activeCall == call) {
                try { Thread.sleep(250); } catch (InterruptedException e) { return; }
            }
            if (activeCall == call && callStatus < Signalling.STATUS_RINGING) {
                LOG.fine("Establishment timeout to " + hexRep(activeCall.getHash()) + ", hanging up");
                hangup();
            }
        }, "lxst-establishment-timeout");
        t.setDaemon(true);
        t.start();
    }

    // ── Link callbacks ────────────────────────────────────────────────────────

    private void incomingLinkEstablished(Link link) {
        callHandlerLock.lock();
        try {
            if (activeCall != null || isBusy()) {
                LOG.fine("Incoming call, line already active, signalling busy");
                signal(Signalling.STATUS_BUSY, link);
                link.teardown();
            } else {
                link.setRemoteIdentifiedCallback((l, id) -> callerIdentified(l, id));
                link.setLinkClosedCallback(this::linkClosed);
                links.put(hexRep(link.getHash()), link);
                signal(Signalling.STATUS_AVAILABLE, link);
            }
        } finally {
            callHandlerLock.unlock();
        }
    }

    private void callerIdentified(Link link, Identity remoteId) {
        callHandlerLock.lock();
        try {
            if (activeCall != null || isBusy()) {
                LOG.fine("Caller identified, but line already active, signalling busy");
                signal(Signalling.STATUS_BUSY, link);
                link.teardown();
            } else if (!isAllowed(remoteId)) {
                LOG.fine("Identified caller " + hexRep(remoteId.getHash()) + " not allowed, signalling busy");
                signal(Signalling.STATUS_BUSY, link);
                link.teardown();
            } else {
                LOG.fine("Caller identified as " + hexRep(remoteId.getHash()) + ", ringing");
                activeCall = new CallState(link, true);
                handleSignallingFrom(link);
                resetDiallingPipelines();
                signal(Signalling.STATUS_RINGING, link);
                activateRingTone();
                if (ringingCallback != null) ringingCallback.accept(remoteId);
                if (autoAnswer != null) {
                    final Identity callerIdentity = remoteId;
                    Thread t = new Thread(() -> {
                        LOG.fine("Auto-answering call from " + hexRep(callerIdentity.getHash()) + " in " + autoAnswer + "s");
                        try { Thread.sleep((long)(autoAnswer * 1000)); } catch (InterruptedException e) { return; }
                        answer(callerIdentity);
                    }, "lxst-auto-answer");
                    t.setDaemon(true);
                    t.start();
                } else {
                    timeoutIncomingCallAt(activeCall, System.currentTimeMillis() / 1000L + ringTime);
                }
            }
        } finally {
            callHandlerLock.unlock();
        }
    }

    private void linkClosed(Link link) {
        if (activeCall != null && link == activeCall.link) {
            Identity remote = link.getRemoteIdentity();
            LOG.fine("Remote " + (remote != null ? hexRep(remote.getHash()) : "?") + " hung up");
            if (!activeCall.isTerminating) hangup();
        }
    }

    // ── Signalling ────────────────────────────────────────────────────────────

    @Override
    public void signal(int signal, Link destination) {
        if (Signalling.AUTO_STATUS_CODES.contains(signal)) callStatus = signal;
        super.signal(signal, destination);
    }

    @Override
    public void signallingReceived(List<Integer> signals, Link source) {
        for (int signal : signals) {
            if (activeCall == null || source != activeCall.link) {
                LOG.fine("Received signalling on non-active call, ignoring");
                continue;
            }
            if (activeCall.isIncoming && !activeCall.answered && signal < Signalling.PREFERRED_PROFILE) return;

            if (signal == Signalling.STATUS_BUSY) {
                LOG.fine("Remote is busy, terminating");
                activeCall.isTerminating = true;
                playBusyTone();
                disableDialTone();
                hangup(Signalling.STATUS_BUSY);

            } else if (signal == Signalling.STATUS_REJECTED) {
                LOG.fine("Remote rejected call, terminating");
                playBusyTone();
                disableDialTone();
                hangup(Signalling.STATUS_REJECTED);

            } else if (signal == Signalling.STATUS_AVAILABLE) {
                LOG.fine("Line available, sending identification");
                callStatus = signal;
                source.identify(this.identity);

            } else if (signal == Signalling.STATUS_RINGING) {
                LOG.fine("Identification accepted, remote is now ringing");
                callStatus = signal;
                prepareDiallingPipelines();
                if (activeCall != null && activeCall.profile != null) {
                    super.signal(Signalling.PREFERRED_PROFILE + activeCall.profile, activeCall.link);
                }
                if (activeCall != null && activeCall.isOutgoing) activateDialTone();

            } else if (signal == Signalling.STATUS_CONNECTING) {
                LOG.fine("Call answered, remote is performing call setup, opening audio pipelines");
                callStatus = signal;
                callerPipelineOpenLock.lock();
                try {
                    resetDiallingPipelines();
                    Identity remoteId = activeCall != null ? activeCall.getRemoteIdentity() : null;
                    if (remoteId != null) openPipelines(remoteId);
                } finally {
                    callerPipelineOpenLock.unlock();
                }

            } else if (signal == Signalling.STATUS_ESTABLISHED) {
                if (activeCall != null && activeCall.isOutgoing) {
                    LOG.fine("Remote call setup completed, starting audio pipelines");
                    callerPipelineOpenLock.lock();
                    try {
                        startPipelines();
                        disableDialTone();
                    } finally {
                        callerPipelineOpenLock.unlock();
                    }
                    LOG.fine("Call setup complete for " + hexRep(activeCall.getRemoteIdentity().getHash()));
                    callStatus = signal;
                    if (establishedCallback != null) establishedCallback.accept(activeCall.getRemoteIdentity());
                    if (lowLatencyOutput && audioOutput != null) audioOutput.enableLowLatency();
                }

            } else if (signal >= Signalling.PREFERRED_PROFILE) {
                int profile = signal - Signalling.PREFERRED_PROFILE;
                if (activeCall != null && callStatus == Signalling.STATUS_ESTABLISHED) {
                    switchProfile(profile, true);
                } else {
                    selectCallProfile(profile);
                }
            }
        }
    }

    // ── Call control ──────────────────────────────────────────────────────────

    public boolean answer(Identity identity) {
        callHandlerLock.lock();
        try {
            if (activeCall == null) {
                LOG.warning("Answering call failed, no active incoming call");
                return false;
            }
            Identity remoteId = activeCall.getRemoteIdentity();
            if (remoteId == null || !Arrays.equals(remoteId.getHash(), identity.getHash())) {
                LOG.warning("Answering call failed, active incoming call is not from " + hexRep(identity.getHash()));
                return false;
            }
            if (callStatus > Signalling.STATUS_RINGING) {
                LOG.info("Incoming call from " + hexRep(identity.getHash()) + " already answered and active");
                return false;
            }
            LOG.fine("Answering call from " + hexRep(identity.getHash()));
            activeCall.answered = true;
            openPipelines(identity);
            startPipelines();
            LOG.fine("Call setup complete for " + hexRep(identity.getHash()));
            if (establishedCallback != null) establishedCallback.accept(activeCall.getRemoteIdentity());
            if (lowLatencyOutput && audioOutput != null) audioOutput.enableLowLatency();
            return true;
        } finally {
            callHandlerLock.unlock();
        }
    }

    public void hangup() { hangup(null); }

    public void hangup(Integer reason) {
        callHandlerLock.lock();
        Identity remoteIdentity = null;
        try {
            if (activeCall == null) return;
            CallState terminating = activeCall;
            activeCall = null;
            remoteIdentity = terminating.getRemoteIdentity();

            if (terminating.isIncoming && callStatus == Signalling.STATUS_RINGING) {
                if (!terminating.ringTimeout && terminating.getStatus() == LinkStatus.ACTIVE) {
                    super.signal(Signalling.STATUS_REJECTED, terminating.link);
                }
            }

            if (terminating.getStatus() == LinkStatus.ACTIVE) terminating.teardown();
            stopPipelines();

            audioInput       = null;
            receiveMixer     = null;
            transmitMixer    = null;
            receivePipeline  = null;
            transmitPipeline = null;
            audioOutput      = null;
            dialTone         = null;
            callStatus       = Signalling.STATUS_AVAILABLE;
            receiveMuted     = false;
            transmitMuted    = false;

            if (remoteIdentity != null) LOG.fine("Call with " + hexRep(remoteIdentity.getHash()) + " terminated");
            else                        LOG.fine("Outgoing call could not be connected, link establishment failed");
        } finally {
            callHandlerLock.unlock();
        }

        if (reason == null) {
            if (endedCallback    != null) endedCallback.accept(remoteIdentity);
        } else if (reason == Signalling.STATUS_BUSY) {
            if      (busyCallback  != null) busyCallback.accept(remoteIdentity);
            else if (endedCallback != null) endedCallback.accept(remoteIdentity);
        } else if (reason == Signalling.STATUS_REJECTED) {
            if      (rejectedCallback != null) rejectedCallback.accept(remoteIdentity);
            else if (endedCallback    != null) endedCallback.accept(remoteIdentity);
        }
    }

    // ── Mute / gain ───────────────────────────────────────────────────────────

    public void muteReceive(boolean mute)    { receiveMuted = mute;   if (receiveMixer  != null) receiveMixer.mute(mute); }
    public void unmuteReceive(boolean unmute){ receiveMuted = !unmute; if (receiveMixer  != null) receiveMixer.unmute(unmute); }
    public void muteTransmit(boolean mute)   { transmitMuted = mute;  if (transmitMixer != null) transmitMixer.mute(mute); }
    public void unmuteTransmit(boolean unmute){ transmitMuted = !unmute; if (transmitMixer != null) transmitMixer.unmute(unmute); }

    public void setReceiveGain(float gain)  { receiveGain  = gain; if (receiveMixer  != null) receiveMixer.setGain(receiveGain); }
    public void setTransmitGain(float gain) { transmitGain = gain; if (transmitMixer != null) transmitMixer.setGain(transmitGain); }

    // ── Profile switching ─────────────────────────────────────────────────────

    public void switchProfile(Integer profile) { switchProfile(profile, false); }

    public void switchProfile(Integer profile, boolean fromSignalling) {
        if (activeCall == null) return;
        if (activeCall.profile != null && activeCall.profile.equals(profile)) return;
        if (callStatus == Signalling.STATUS_ESTABLISHED) {
            activeCall.profile = profile;
            transmitCodec      = Profiles.getCodec(profile);
            targetFrameTimeMs  = Profiles.getFrameTime(profile);
            if (!fromSignalling) super.signal(Signalling.PREFERRED_PROFILE + profile, activeCall.link);
            reconfigureTransmitPipeline();
        }
    }

    private void selectCallProfile(Integer profile) {
        if (profile == null) profile = Profiles.DEFAULT_PROFILE;
        activeCall.profile = profile;
        selectCallCodecs(profile);
        selectCallFrameTime(profile);
        LOG.fine("Selected call profile 0x" + Integer.toHexString(profile));
    }

    private void selectCallCodecs(int profile) {
        receiveCodec  = new NullCodec();
        transmitCodec = Profiles.getCodec(profile);
    }

    private void selectCallFrameTime(int profile) {
        targetFrameTimeMs = Profiles.getFrameTime(profile);
    }

    // ── Pipeline management ───────────────────────────────────────────────────

    private void resetDiallingPipelines() {
        pipelineLock.lock();
        try {
            if (audioOutput    != null) audioOutput.stop();
            if (dialTone       != null) dialTone.stop();
            if (receivePipeline != null) receivePipeline.stop();
            if (receiveMixer   != null) receiveMixer.stop();
            audioOutput    = null;
            dialTone       = null;
            receivePipeline = null;
            receiveMixer   = null;
            prepareDiallingPipelines();
        } finally {
            pipelineLock.unlock();
        }
    }

    private void prepareDiallingPipelines() {
        if (activeCall != null) selectCallProfile(activeCall.profile);
        int frameMs = targetFrameTimeMs != null ? targetFrameTimeMs : 60;
        if (audioOutput    == null) audioOutput    = new LineSink(speakerDevice);
        if (receiveMixer   == null) receiveMixer   = new Mixer(frameMs, null, null, null, receiveGain);
        if (dialTone       == null) dialTone       = new ToneSource(dialToneFrequency, 0.0f,
                                                                     dialToneEaseMs, frameMs,
                                                                     new NullCodec(), receiveMixer);
        if (receivePipeline == null) receivePipeline = new Pipeline(receiveMixer, new NullCodec(), audioOutput);
    }

    private void activateRingTone() {
        if (ringtonePath == null || !new File(ringtonePath).isFile()) return;
        if (ringerPipeline == null) {
            if (ringerOutput == null) ringerOutput = new LineSink(ringerDevice);
            try {
                ringerSource   = new OpusFileSource(ringtonePath, true);
                ringerPipeline = new Pipeline(ringerSource, new NullCodec(), ringerOutput);
            } catch (Exception e) {
                LOG.warning(this + " could not load ringtone: " + e.getMessage());
                return;
            }
        }
        final Pipeline rp  = ringerPipeline;
        final OpusFileSource rs = ringerSource;
        Thread t = new Thread(() -> {
            ringerLock.lock();
            try {
                while (activeCall != null && activeCall.isIncoming && callStatus == Signalling.STATUS_RINGING) {
                    if (!rp.isRunning()) rp.start();
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }
                rs.stop();
            } finally {
                ringerLock.unlock();
            }
        }, "lxst-ringer");
        t.setDaemon(true);
        t.start();
    }

    private void playBusyTone() {
        if (busyToneSeconds <= 0) return;
        if (audioOutput == null || receiveMixer == null || dialTone == null) resetDiallingPipelines();
        pipelineLock.lock();
        try {
            double window = 0.5;
            long started  = System.currentTimeMillis();
            long durationMs = (long)(busyToneSeconds * 1000);
            while (System.currentTimeMillis() - started < durationMs) {
                double elapsed = ((System.currentTimeMillis() - started) % (long)(window * 1000)) / 1000.0;
                if (elapsed > 0.25) enableDialTone(); else muteDialTone();
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        } finally {
            pipelineLock.unlock();
        }
    }

    private void activateDialTone() {
        Thread t = new Thread(() -> {
            double window = 7.0;
            long started  = System.currentTimeMillis();
            while (activeCall != null && activeCall.isOutgoing && callStatus == Signalling.STATUS_RINGING) {
                double elapsed = ((System.currentTimeMillis() - started) % (long)(window * 1000)) / 1000.0;
                if (elapsed > 0.05 && elapsed < 2.05) enableDialTone(); else muteDialTone();
                try { Thread.sleep(200); } catch (InterruptedException e) { break; }
            }
        }, "lxst-dial-tone");
        t.setDaemon(true);
        t.start();
    }

    private void enableDialTone() {
        if (receiveMixer != null && !receiveMixer.isShouldRun()) receiveMixer.start();
        if (dialTone != null) {
            dialTone.gain = 0.04f;
            if (!dialTone.isShouldRun()) dialTone.start();
        }
    }

    private void muteDialTone() {
        if (receiveMixer != null && !receiveMixer.isShouldRun()) receiveMixer.start();
        if (dialTone != null) {
            if (dialTone.isShouldRun() && dialTone.gain != 0) dialTone.gain = 0.0f;
            if (!dialTone.isShouldRun()) dialTone.start();
        }
    }

    private void disableDialTone() {
        if (dialTone != null && dialTone.isShouldRun()) dialTone.stop();
    }

    private void reconfigureTransmitPipeline() {
        if (transmitPipeline == null || callStatus != Signalling.STATUS_ESTABLISHED || activeCall == null) return;
        if (audioInput    != null) audioInput.stop();
        if (transmitMixer != null) transmitMixer.stop();
        transmitPipeline.stop();

        int frameMs = targetFrameTimeMs != null ? targetFrameTimeMs : 60;
        transmitMixer    = new Mixer(frameMs, null, null, null, transmitGain);
        audioInput       = new LineSource(microphoneDevice, frameMs, new RawCodec(),
                                          transmitMixer, activeCall.filters, 0.0f, 0.0, 0.075);
        transmitPipeline = new Pipeline(transmitMixer, transmitCodec, activeCall.packetizer);

        transmitMixer.mute(transmitMuted);
        transmitMixer.start();
        audioInput.start();
        transmitPipeline.start();
    }

    private void openPipelines(Identity identity) {
        pipelineLock.lock();
        try {
            if (activeCall == null) return;
            Identity remoteId = activeCall.getRemoteIdentity();
            if (remoteId == null || !Arrays.equals(remoteId.getHash(), identity.getHash())) {
                LOG.severe("Identity mismatch while opening call pipelines, tearing down call");
                hangup();
                return;
            }
            if (activeCall.pipelinesOpened) {
                LOG.severe("Pipelines already opened for call with " + hexRep(identity.getHash()));
                return;
            }
            activeCall.pipelinesOpened = true;
            LOG.fine("Opening audio pipelines for call with " + hexRep(identity.getHash()));
            if (activeCall.isIncoming) super.signal(Signalling.STATUS_CONNECTING, activeCall.link);

            if (useAgc) activeCall.filters = Arrays.asList(new BandPassFilter(250, 8500), new AGCFilter(-15.0, 12.0, 0.0001, 0.002, 0.001));
            else        activeCall.filters = Arrays.asList(new BandPassFilter(250, 8500));

            prepareDiallingPipelines();
            activeCall.packetizer = new Packetizer(activeCall.link, this::packetizerFailure);

            int frameMs = targetFrameTimeMs != null ? targetFrameTimeMs : 60;
            transmitMixer = new Mixer(frameMs, null, null, null, transmitGain);
            audioInput    = new LineSource(microphoneDevice, frameMs, new RawCodec(),
                                           transmitMixer, activeCall.filters, 0.0f, 0.225, 0.075);
            transmitPipeline = new Pipeline(transmitMixer, transmitCodec, activeCall.packetizer);

            activeCall.audioSource = new LinkSource(activeCall.link, this, receiveMixer);
            receiveMixer.setSourceMaxFrames(activeCall.audioSource, 2);

            super.signal(Signalling.STATUS_ESTABLISHED, activeCall.link);
        } finally {
            pipelineLock.unlock();
        }
    }

    private void packetizerFailure() {
        LOG.severe("Frame packetization failed, terminating call");
        hangup();
    }

    private void startPipelines() {
        pipelineLock.lock();
        try {
            if (receiveMixer     != null) receiveMixer.start();
            if (transmitMixer    != null) transmitMixer.start();
            if (audioInput       != null) audioInput.start();
            if (transmitPipeline != null) transmitPipeline.start();
            if (audioInput == null) LOG.warning("No audio input was ready at call establishment");
            LOG.fine("Audio pipelines started");
        } finally {
            pipelineLock.unlock();
        }
    }

    private void stopPipelines() {
        pipelineLock.lock();
        try {
            if (receiveMixer     != null) receiveMixer.stop();
            if (transmitMixer    != null) transmitMixer.stop();
            if (audioInput       != null) audioInput.stop();
            if (receivePipeline  != null) receivePipeline.stop();
            if (transmitPipeline != null) transmitPipeline.stop();
            LOG.fine("Audio pipelines stopped");
        } finally {
            pipelineLock.unlock();
        }
    }

    // ── Outgoing call ─────────────────────────────────────────────────────────

    public void call(Identity remoteIdentity) { call(remoteIdentity, null); }

    public void call(Identity remoteIdentity, Integer profile) {
        callHandlerLock.lock();
        try {
            if (activeCall != null) return;
            callStatus = Signalling.STATUS_CALLING;
            long callTimeoutSec   = System.currentTimeMillis() / 1000L + waitTime;
            long establishTimeoutSec = System.currentTimeMillis() / 1000L + establishmentTimeout;

            Destination callDest = new Destination(remoteIdentity, Direction.OUT, DestinationType.SINGLE,
                                                    LXST.APP_NAME, PRIMITIVE_NAME);

            if (!Boolean.TRUE.equals(Transport.getInstance().hasPath(callDest.getHash()))) {
                LOG.fine("No path known for call to " + hexRep(callDest.getHash()) + ", requesting path...");
                Transport.getInstance().requestPath(callDest.getHash());
                while (!Boolean.TRUE.equals(Transport.getInstance().hasPath(callDest.getHash()))
                        && System.currentTimeMillis() / 1000L < callTimeoutSec) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                }
            }

            if (!Boolean.TRUE.equals(Transport.getInstance().hasPath(callDest.getHash()))
                    && System.currentTimeMillis() / 1000L >= callTimeoutSec) {
                hangup();
            } else {
                LOG.fine("Establishing link with " + hexRep(callDest.getHash()) + "...");
                Link link = new Link(callDest);
                link.setLinkEstablishedCallback(this::outgoingLinkEstablished);
                link.setLinkClosedCallback(this::outgoingLinkClosed);
                CallState cs = new CallState(link, false);
                cs.profile = profile;
                activeCall = cs;
                timeoutOutgoingCallAt(cs, callTimeoutSec);
                timeoutOutgoingEstablishmentAt(cs, establishTimeoutSec);
            }
        } finally {
            callHandlerLock.unlock();
        }
    }

    private void outgoingLinkEstablished(Link link) {
        LOG.fine("Link established for call with " + link.getRemoteIdentity());
        link.setLinkClosedCallback(this::linkClosed);
        handleSignallingFrom(link);
    }

    @SuppressWarnings("unused")
    private void outgoingLinkClosed(Link link) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String hexRep(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Override
    public String toString() { return "<lxst.telephony/" + hexRep(identity.getHash()) + ">"; }
}
