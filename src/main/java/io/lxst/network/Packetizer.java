package io.lxst.network;

import io.lxst.codec.Codecs;
import io.lxst.sink.RemoteSink;
import io.lxst.source.Source;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import io.reticulum.packet.Packet;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends encoded audio frames over an RNS Link or Destination as msgpack-framed packets.
 *
 * Mirrors Python LXST Network.Packetizer.
 */
public class Packetizer extends RemoteSink {

    private static final Logger LOG = Logger.getLogger(Packetizer.class.getName());

    private final Object destination;   // RNS Link or Destination
    private volatile boolean shouldRun = false;
    private volatile boolean transmitFailure = false;
    private final Runnable failureCallback;

    private Source source;

    public Packetizer(Object destination) {
        this(destination, null);
    }

    public Packetizer(Object destination, Runnable failureCallback) {
        this.destination     = destination;
        this.failureCallback = failureCallback;
    }

    public void setSource(Source source) { this.source = source; }

    @Override
    public void handleFrame(byte[] frame, Source fromSource) {
        if (destination instanceof Link) {
            Link link = (Link) destination;
            if (link.getStatus() != LinkStatus.ACTIVE) return;
        }

        // Prepend codec header byte and wrap in msgpack map {1: bytes}
        byte codecHeader = (source != null && source.getCodec() != null)
                           ? Codecs.codecHeaderByte(source.getCodec().getClass())
                           : (byte) Codecs.RAW;

        byte[] framedData = new byte[1 + frame.length];
        framedData[0] = codecHeader;
        System.arraycopy(frame, 0, framedData, 1, frame.length);

        try {
            MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
            packer.packMapHeader(1);
            packer.packInt(SignallingReceiver.FIELD_FRAMES);
            packer.packBinaryHeader(framedData.length);
            packer.addPayload(framedData);
            byte[] packed = packer.toByteArray();

            Packet pkt;
            if (destination instanceof Link) {
                pkt = new Packet((Link) destination, packed);
            } else if (destination instanceof io.reticulum.destination.Destination) {
                pkt = new Packet((io.reticulum.destination.Destination) destination, packed);
            } else {
                LOG.warning(this + " unknown destination type: " + destination.getClass());
                return;
            }

            io.reticulum.packet.PacketReceipt receipt = pkt.send();
            if (receipt == null) {
                transmitFailure = true;
                if (failureCallback != null) failureCallback.run();
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, this + " error sending frame packet", e);
            transmitFailure = true;
            if (failureCallback != null) failureCallback.run();
        }
    }

    public void start() {
        if (!shouldRun) {
            LOG.fine(this + " starting");
            shouldRun = true;
        }
    }

    public void stop() {
        shouldRun = false;
    }

    public boolean isTransmitFailure() { return transmitFailure; }

    @Override
    public String toString() { return "<lxst.Packetizer>"; }
}
