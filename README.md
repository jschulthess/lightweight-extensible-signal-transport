# LXST — Lightweight Extensible Signal Transport for Reticulum (Java)

Java implementation of [LXST](https://github.com/markqvist/LXST), wire-format-compatible with the Python reference implementation. Built on top of the Java [reticulum-network-stack](https://github.com/jschulthess/reticulum-network-stack).

---

## Requirements

- Java 11+
- `libopus` installed on the system (for Opus codec)
- `libcodec2` installed on the system (for Codec2 codec)
- Maven (to build)

### Maven dependency

Add via JitPack:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.jschulthess</groupId>
  <artifactId>lightweight-extensible-signal-transport</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

---

## Architecture

Audio flows through a three-stage pipeline:

```
Source  ──►  Codec  ──►  Sink
```

- **Sources** produce decoded `float[samples][channels]` frames (values in `[-1.0, 1.0]`).
- **Codecs** encode frames to `byte[]` for transport or storage, and decode back.
- **Sinks** consume frames — either playing them, writing them to a file, or transmitting them over a Reticulum link.

A `Mixer` combines frames from multiple sources before forwarding to a single sink.

`Pipeline` wires a source, codec, and sink together and manages start/stop lifecycle.

---

## Core components

| Class | Package | Role |
|---|---|---|
| `LineSource` | `io.lxst.source` | Reads from microphone |
| `OpusFileSource` | `io.lxst.source` | Plays back an OGG Opus file |
| `Loopback` | `io.lxst.source` | Passes frames between pipeline stages |
| `LineSink` | `io.lxst.sink` | Plays audio to speaker |
| `OpusFileSink` | `io.lxst.sink` | Records audio to an OGG Opus file |
| `Mixer` | `io.lxst` | Mixes frames from multiple sources |
| `Pipeline` | `io.lxst` | Connects source → codec → sink |
| `OpusCodec` | `io.lxst.codec` | Opus encode/decode via libopus |
| `Codec2Codec` | `io.lxst.codec` | Codec2 encode/decode via libcodec2 |
| `RawCodec` | `io.lxst.codec` | Uncompressed PCM |
| `ToneSource` | `io.lxst.generator` | Sine-wave tone generator |
| `Telephone` | `io.lxst.primitive` | Full VoIP over Reticulum |
| `FilePlayer` | `io.lxst.primitive` | Convenience wrapper for file playback |
| `FileRecorder` | `io.lxst.primitive` | Convenience wrapper for file recording |
| `Packetizer` | `io.lxst.network` | Sends encoded frames over a Reticulum link |
| `LinkSource` | `io.lxst.network` | Receives encoded frames from a Reticulum link |

---

## Usage

### Play an Opus file

```java
FilePlayer player = new FilePlayer("/path/to/audio.opus");
player.setFinishedCallback(fp -> System.out.println("Done"));
player.play();
```

### Record to an Opus file

```java
FileRecorder recorder = new FileRecorder("/path/to/output.opus");
recorder.start();
// ... later:
recorder.stop();
```

### Manual pipeline

```java
LineSource  mic      = new LineSource();
OpusCodec   codec    = new OpusCodec(OpusCodec.PROFILE_VOICE_HIGH);
Packetizer  pkt      = new Packetizer(reticulumLink, null);
Pipeline    pipeline = new Pipeline(mic, codec, pkt);
pipeline.start();
```

### Telephone

```java
// Setup
Telephone phone = new Telephone(identity);
phone.setRingingCallback(id -> System.out.println("Incoming call from " + id));
phone.setEstablishedCallback(id -> System.out.println("Call established with " + id));
phone.setEndedCallback(id -> System.out.println("Call ended"));
phone.setSpeaker(null);       // null = system default
phone.setMicrophone(null);
phone.setRingtone("/path/to/ringtone.opus");
phone.announce();

// Outgoing call
phone.call(remoteIdentity, Profiles.QUALITY_HIGH);

// Answer an incoming call (from inside the ringing callback)
phone.answer(callerIdentity);

// Hang up
phone.hangup();

// Mute/gain
phone.muteTransmit(true);
phone.setReceiveGain(3.0f);   // dB

// Clean up
phone.teardown();
```

### Mixer

```java
Mixer    mixer    = new Mixer(40);          // 40 ms target frame time
LineSink speaker  = new LineSink();
Pipeline pipeline = new Pipeline(mixer, new NullCodec(), speaker);

mixer.start();
pipeline.start();

// Add sources by piping them into the mixer:
LineSource mic = new LineSource(null, 40, new RawCodec(), mixer, null, 0.0f, 0.0, 0.0);
mic.start();
```

---

## Telephony profiles

| Constant | Name | Codec | Frame |
|---|---|---|---|
| `BANDWIDTH_ULTRA_LOW` | Ultra Low Bandwidth | Codec2 700C | 400 ms |
| `BANDWIDTH_VERY_LOW` | Very Low Bandwidth | Codec2 1600 | 320 ms |
| `BANDWIDTH_LOW` | Low Bandwidth | Codec2 3200 | 200 ms |
| `QUALITY_MEDIUM` *(default)* | Medium Quality | Opus Voice Medium | 60 ms |
| `QUALITY_HIGH` | High Quality | Opus Voice High | 60 ms |
| `QUALITY_MAX` | Super High Quality | Opus Voice Max | 60 ms |
| `LATENCY_LOW` | Low Latency | Opus Voice Medium | 20 ms |
| `LATENCY_ULTRA_LOW` | Ultra Low Latency | Opus Voice Medium | 10 ms |

The active profile can be switched mid-call:

```java
phone.switchProfile(Profiles.LATENCY_LOW);
```

The remote party is notified via an in-band signalling message and switches its transmit codec accordingly.

---

## Filters

Filters can be attached to any `LineSource` to process audio before it is encoded:

```java
List<Filter> filters = Arrays.asList(
    new BandPassFilter(250, 8500),
    new AGCFilter(-15.0, 12.0, 0.0001, 0.002, 0.001)
);
LineSource mic = new LineSource(null, 20, codec, sink, filters, 0.0f, 0.1, 0.075);
```

| Filter | Package | Parameters |
|---|---|---|
| `HighPassFilter` | `io.lxst.filter` | cut frequency (Hz) |
| `LowPassFilter` | `io.lxst.filter` | cut frequency (Hz) |
| `BandPassFilter` | `io.lxst.filter` | low cut, high cut (Hz) |
| `AGCFilter` | `io.lxst.filter` | target dBFS, max gain dB, attack, release, hold times |

---

## Wire format

Network packets are msgpack maps with two optional fields:

| Key | Value | Description |
|---|---|---|
| `0x00` | `[int, ...]` | Signalling — array of status codes |
| `0x01` | `bytes` | Audio frame — codec header byte followed by encoded payload |

The codec header byte identifies the codec: `0x00` = Raw, `0x01` = Opus, `0x02` = Codec2. This matches the Python reference implementation, enabling interoperability between Java and Python LXST nodes on the same Reticulum network.

## Related projects

- [Reticulum](https://reticulum.network) — the underlying network stack, [Reticulum Manual](https://reticulum.network/manual/index.html)
- [reticulum-network-stack](https://github.com/jschulthess/reticulum-network-stack) — native Java Reticulum implementation
- [LXST (Python reference)](https://github.com/markqvist/LXST) — the reference implementation this library is translated from
- [LXST Phone](https://github.com/kc1awv/lxst_phone) — Python LXST client application, [Reticulum Manual Reference](https://reticulum.network/manual/software.html#lxst-phone)
