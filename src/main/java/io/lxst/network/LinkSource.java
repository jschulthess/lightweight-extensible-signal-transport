package io.lxst.network;

import io.lxst.Mixer;
import io.lxst.codec.Codec;
import io.lxst.codec.Codecs;
import io.lxst.codec.NullCodec;
import io.lxst.sink.LineSink;
import io.lxst.sink.OpusFileSink;
import io.lxst.sink.Sink;
import io.lxst.source.RemoteSource;
import io.reticulum.identity.Identity;
import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receives encoded audio frames from a Reticulum Link, decodes them, and forwards
 * them to a Sink.
 *
 * Mirrors Python LXST Network.LinkSource.
 */
public class LinkSource extends RemoteSource {

    private static final Logger LOG = Logger.getLogger(LinkSource.class.getName());

    private final Link link;
    private SignallingReceiver signallingReceiver;
    private Sink sink;
    private Codec codec;

    private volatile boolean shouldRun = false;
    private final Object receiveLock = new Object();

    private io.lxst.Pipeline pipeline;

    private int sampleRate = 48000;
    private int channels   = 1;
    private int bitDepth   = 16;

    public LinkSource(Link link, SignallingReceiver sigReceiver, Sink sink) {
        this.link               = link;
        this.signallingReceiver = sigReceiver;
        this.sink               = sink;
        this.codec              = new NullCodec();

        link.setPacketCallback(this::handleLinkPacket);
    }

    private void handleLinkPacket(byte[] data, Packet packet) {
        synchronized (receiveLock) {
            try {
                try (MessageUnpacker u = MessagePack.newDefaultUnpacker(data)) {
                    if (!u.hasNext() || u.getNextFormat().getValueType() != ValueType.MAP) return;
                    org.msgpack.value.MapValue map = (org.msgpack.value.MapValue) u.unpackValue();

                    for (java.util.Map.Entry<org.msgpack.value.Value, org.msgpack.value.Value> e : map.entrySet()) {
                        int key = e.getKey().asIntegerValue().asInt();

                        if (key == SignallingReceiver.FIELD_FRAMES) {
                            org.msgpack.value.Value val = e.getValue();
                            byte[][] frames;
                            if (val.isBinaryValue()) {
                                frames = new byte[][]{val.asBinaryValue().asByteArray()};
                            } else if (val.isArrayValue()) {
                                org.msgpack.value.ArrayValue arr = val.asArrayValue();
                                frames = new byte[arr.size()][];
                                for (int i = 0; i < arr.size(); i++) frames[i] = arr.get(i).asBinaryValue().asByteArray();
                            } else continue;

                            for (byte[] frame : frames) {
                                if (frame.length == 0) continue;
                                byte headerByte = frame[0];
                                byte[] payload  = new byte[frame.length - 1];
                                System.arraycopy(frame, 1, payload, 0, payload.length);

                                Class<? extends Codec> frameCodecClass = Codecs.codecType(headerByte);
                                if (codec != null && sink != null) {
                                    // Switch codec if needed
                                    if (!frameCodecClass.isInstance(codec)) {
                                        LOG.fine("Remote switched codec to " + frameCodecClass.getSimpleName());
                                        Codec newCodec = Codecs.newCodecFromHeader(headerByte);
                                        newCodec.sink = sink;
                                        newCodec.source = this;
                                        if (pipeline != null) {
                                            pipeline.setCodec(newCodec);
                                        } else {
                                            codec = newCodec;
                                            codec.sink = sink;
                                        }
                                    }

                                    float[][] decoded = codec.decode(payload);
                                    if (codec.channels > 0) channels = codec.channels;
                                    forwardDecoded(decoded);
                                }
                            }
                        }

                        if (key == SignallingReceiver.FIELD_SIGNALLING) {
                            if (signallingReceiver != null) {
                                org.msgpack.value.Value val = e.getValue();
                                List<Integer> signals = new ArrayList<>();
                                if (val.isArrayValue()) {
                                    for (org.msgpack.value.Value v : val.asArrayValue())
                                        signals.add(v.asIntegerValue().asInt());
                                } else if (val.isIntegerValue()) {
                                    signals.add(val.asIntegerValue().asInt());
                                }
                                if (!signals.isEmpty()) signallingReceiver.signallingReceived(signals, link);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                LOG.log(Level.WARNING, this + " could not process incoming packet: " + ex.getMessage());
            }
        }
    }

    private void forwardDecoded(float[][] frame) {
        if (sink instanceof Mixer)          ((Mixer) sink).handleDecodedFrame(frame, this);
        else if (sink instanceof LineSink)  ((LineSink) sink).handleDecodedFrame(frame, this);
        else if (sink instanceof OpusFileSink) ((OpusFileSink) sink).handleDecodedFrame(frame, this);
        else if (sink != null)              sink.handleFrame(new byte[0], this); // fallback
    }

    public void setPipeline(io.lxst.Pipeline pipeline) { this.pipeline = pipeline; }

    @Override public void start()   { LOG.fine(this + " starting"); shouldRun = true; }
    @Override public void stop()    { shouldRun = false; }
    @Override public boolean isShouldRun() { return shouldRun; }
    @Override public int getSampleRate()   { return sampleRate; }
    @Override public int getChannels()     { return channels; }
    @Override public int getBitDepth()     { return bitDepth; }
    @Override public Codec getCodec()      { return codec; }
    @Override public void setCodec(Codec c) { codec = c; if (c != null) { c.source = this; c.sink = sink; } }
    @Override public Sink getSink()        { return sink; }
    @Override public void setSink(Sink s)  { sink = s; if (codec != null) codec.sink = s; }

    @Override
    public String toString() { return "<lxst.LinkSource>"; }
}
