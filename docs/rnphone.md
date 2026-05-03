# RnPhone — Reticulum Telephone Utility (Java)

`RnPhone` is a command-line voice-over-Reticulum telephone,
equivalent to the Python `rnphone` tool included with LXST.
The source is at `src/main/java/examples/RnPhone.java`.

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Java 11+ | `java -version` |
| Maven 3.6+ | `mvn -version` |
| [libopus](https://opus-codec.org/) | `sudo apt install libopus0` |
| [libcodec2](https://github.com/drowe67/codec2) | `sudo apt install libcodec2-dev` *(optional, for low-bandwidth profiles)* |
| Reticulum configured | `~/.reticulum/config.yml` must exist — run `rnsd` or `rnstatus` once to create it |

---

## Build

```bash
mvn package -DskipTests
```

This produces `target/rnphone.jar` — a self-contained fat JAR with all dependencies.

---

## Run

The RNS Java stack uses Jackson reflection that requires a few JVM module access flags:

```bash
java \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.net=ALL-UNNAMED \
  -jar target/rnphone.jar
```

### Recommended: create a wrapper script

To avoid typing the flags every time, create a small launcher:

```bash
cat > rnphone.sh << 'EOF'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
exec java \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.net=ALL-UNNAMED \
  -jar "$DIR/target/rnphone.jar" "$@"
EOF
chmod +x rnphone.sh
```

Then run:

```bash
./rnphone.sh
```

### Options

| Flag | Default | Description |
|---|---|---|
| `--config <dir>` | `~/.rnphone` | Config/identity directory |
| `--rnsconfig <dir>` | `~/.reticulum` | Reticulum config directory |

---

## First run

On first run the app creates `~/.rnphone/` and generates a fresh identity:

```
No identity file found, creating new...
Created new identity: 3f8a...b2

Reticulum Telephone Utility is ready
  Identity hash: 3f8a...b2

> 
```

The identity is persisted in `~/.rnphone/identity` and reloaded on subsequent runs.

---

## CLI usage

At the `> ` prompt:

| Input | Action |
|---|---|
| `<32 hex chars>` | Dial the identity hash |
| `p` / `phonebook` | Open the phonebook |
| `r` / `redial` | Call the last dialled identity again |
| `i` / `identity` | Print the identity hash of this telephone |
| `d` / `desthash` | Print the destination hash of this telephone |
| `a` / `announce` | Send an announce on the network |
| `h` / `help` / `?` | Show the help menu |
| `q` / `quit` / `exit` | Exit |

**When the phone is ringing (incoming call):**

| Input | Action |
|---|---|
| Enter (empty line) | Answer |
| Anything else | Reject |

**During a call:**

| Input | Action |
|---|---|
| Enter (empty line) | Hang up |

---

## Config file

The config file at `~/.rnphone/config` uses the same INI format as the Python `rnphone`.
A default file is written on first run:

```ini
[telephone]
    # Ringtone played for incoming calls (Opus file in the config directory)
    # ringtone = ringer.opus

    # Preferred audio devices (fuzzy-matched against soundcard device names)
    # speaker    = device name
    # microphone = device name
    # ringer     = device name

    # Who is allowed to call this telephone: all | none | phonebook
    # allowed_callers = all

[phonebook]
    # Name = <identity-hash-hex>
    # Mary = f3e8c3359b39d36f3baff0a616a73d3e
    # Jake = b8d80b1b7a9d3147880b366995422a45
```

Phonebook entries can be dialled by entering the entry number shown in the phonebook menu.

---

## Calling another node

1. The remote node must have an `lxst.telephony` announce reachable on the network.
   Ask them to run `a` (announce) if you cannot reach them.

2. Type their 32-character identity hash at the `> ` prompt and press Enter.
   The app will request a path if one is not already cached, then place the call.

3. The remote phone rings.  
   On their side: Enter to answer, any other key to reject.

4. Once connected, both sides hear each other via the default audio devices.  
   Press Enter (or any input) to hang up.

---

## Audio profiles

The call profile is negotiated automatically. Available profiles (from `Profiles.java`):

| Profile | Codec | Frame | Typical use |
|---|---|---|---|
| Ultra Low BW | Codec2 700C | 400 ms | Satellite / very slow links |
| Very Low BW | Codec2 1600 | 320 ms | Slow packet radio |
| Low BW | Codec2 3200 | 200 ms | Packet radio |
| Medium Quality *(default)* | Opus | 60 ms | Internet / Wi-Fi |
| High Quality | Opus | 60 ms | Good broadband |
| Super High Quality | Opus | 60 ms | High bandwidth |
| Low Latency | Opus | 20 ms | LAN |
| Ultra Low Latency | Opus | 10 ms | LAN, latency-critical |
