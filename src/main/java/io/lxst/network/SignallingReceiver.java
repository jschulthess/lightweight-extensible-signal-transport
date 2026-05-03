package io.lxst.network;

import io.reticulum.link.Link;
import io.reticulum.packet.Packet;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ValueType;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles in-band signalling messages carried over RNS packets.
 *
 * Mirrors Python LXST Network.SignallingReceiver.
 */
public class SignallingReceiver {

    private static final Logger LOG = Logger.getLogger(SignallingReceiver.class.getName());

    // Field keys in the msgpack map
    public static final int FIELD_SIGNALLING = 0x00;
    public static final int FIELD_FRAMES     = 0x01;

    private SignallingReceiver proxy;

    public SignallingReceiver() {}

    public SignallingReceiver(SignallingReceiver proxy) {
        this.proxy = proxy;
    }

    public void handleSignallingFrom(Link link) {
        link.setPacketCallback((data, packet) -> handlePacket(data, packet, null));
    }

    public void signallingReceived(List<Integer> signals, Link source) {
        if (proxy != null) proxy.signallingReceived(signals, source);
    }

    public void signal(int signal, Link destination) {
        signal(signal, destination, true);
    }

    public void signal(int signal, Link destination, boolean immediate) {
        if (!immediate) return; // inband scheduling not implemented

        try {
            org.msgpack.core.MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
            packer.packMapHeader(1);
            packer.packInt(FIELD_SIGNALLING);
            packer.packArrayHeader(1);
            packer.packInt(signal);
            byte[] data = packer.toByteArray();

            Packet pkt = new Packet(destination, data);
            pkt.send();
        } catch (Exception e) {
            LOG.log(Level.WARNING, this + " could not send signalling packet", e);
        }
    }

    protected void handlePacket(byte[] data, Packet packet, Object unpacked) {
        try {
            if (data == null && unpacked == null) return;

            org.msgpack.value.MapValue mapVal = null;

            if (data != null) {
                try (MessageUnpacker u = MessagePack.newDefaultUnpacker(data)) {
                    if (u.hasNext() && u.getNextFormat().getValueType() == ValueType.MAP) {
                        mapVal = (org.msgpack.value.MapValue) u.unpackValue();
                    }
                }
            }

            if (mapVal != null) {
                for (java.util.Map.Entry<org.msgpack.value.Value, org.msgpack.value.Value> entry : mapVal.entrySet()) {
                    int key = entry.getKey().asIntegerValue().asInt();
                    if (key == FIELD_SIGNALLING) {
                        org.msgpack.value.Value val = entry.getValue();
                        java.util.List<Integer> signals = new java.util.ArrayList<>();
                        if (val.isArrayValue()) {
                            for (org.msgpack.value.Value v : val.asArrayValue()) signals.add(v.asIntegerValue().asInt());
                        } else if (val.isIntegerValue()) {
                            signals.add(val.asIntegerValue().asInt());
                        }
                        Link source = null;
                        if (packet != null) {
                            Object dest = packet.getDestination();
                            if (dest instanceof Link) source = (Link) dest;
                        }
                        signallingReceived(signals, source);
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, this + " could not process incoming packet: " + e.getMessage());
        }
    }
}
