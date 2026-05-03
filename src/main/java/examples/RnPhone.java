package examples;

import io.lxst.LXST;
import io.lxst.primitive.Telephone;
import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.identity.Identity;
import io.reticulum.identity.IdentityKnownDestination;
import io.reticulum.utils.DestinationUtils;
import io.reticulum.utils.IdentityUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reticulum Telephone Utility — Java port of the Python rnphone example.
 *
 * Usage:
 *   java examples.RnPhone [--config <dir>] [--rnsconfig <dir>]
 *
 * Default config directory: ~/.rnphone/
 * Identity is stored at {@code <configdir>/identity}.
 * Optional INI config file at {@code <configdir>/config} (same format as Python rnphone).
 */
public class RnPhone {

    private static final int STATE_AVAILABLE  = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_RINGING    = 2;
    private static final int STATE_IN_CALL    = 3;

    private static final int PATH_TIMEOUT_SEC = 10;

    // ANSI terminal formatting (mirrors Python Terminal class in rnphone.py)
    private static final String BOLD      = "\033[1m";
    private static final String UNDERLINE = "\033[4m";
    private static final String END       = "\033[0m";

    // ── Instance state ──────────────────────────────────────────────────────

    private final Path configDir;
    private Identity identity;
    private Telephone telephone;

    private volatile int      state          = STATE_AVAILABLE;
    private volatile Identity remoteIdentity = null;
    private volatile boolean  shouldRun      = false;
    private volatile boolean  inPhonebook    = false;

    private String lastDialledHash = null;

    private final Map<String, String> phonebook  = new LinkedHashMap<>(); // name  → identity-hash-hex
    private final Map<String, String> nameByHash = new HashMap<>();       // hash  → name

    // Audio device / ringtone settings (from config file)
    private String speaker    = null;
    private String microphone = null;
    private String ringer     = null;
    private String ringtone   = null;

    // ── Entry point ─────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String configArg    = null;
        String rnsConfigArg = null;

        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i])    && i + 1 < args.length) configArg    = args[++i];
            if ("--rnsconfig".equals(args[i]) && i + 1 < args.length) rnsConfigArg = args[++i];
        }

        Path home      = Paths.get(System.getProperty("user.home"));
        Path cfgDir    = configArg    != null ? Paths.get(configArg)    : home.resolve(".rnphone");
        Path rnsCfgDir = rnsConfigArg != null ? Paths.get(rnsConfigArg) : null;

        new RnPhone(cfgDir, rnsCfgDir).start();
    }

    // ── Setup ───────────────────────────────────────────────────────────────

    public RnPhone(Path configDir, Path rnsConfigDir) throws Exception {
        this.configDir = configDir;
        Files.createDirectories(configDir);

        loadOrCreateIdentity();
        loadConfig();

        new Reticulum(rnsConfigDir != null ? rnsConfigDir.toString() : null);

        telephone = new Telephone(identity, Telephone.RING_TIME, Telephone.WAIT_TIME,
                                  null, Telephone.ALLOW_ALL, 0.0f, 0.0f);
        if (ringtone   != null) telephone.setRingtone(ringtone);
        if (speaker    != null) telephone.setSpeaker(speaker);
        if (microphone != null) telephone.setMicrophone(microphone);
        if (ringer     != null) telephone.setRinger(ringer);

        telephone.setRingingCallback(this::onRinging);
        telephone.setEstablishedCallback(this::onEstablished);
        telephone.setEndedCallback(remote -> onCallTerminated(remote, "ended"));
        telephone.setBusyCallback(remote -> {
            System.out.println("\nRemote is busy\n");
            onCallTerminated(remote, "busy");
        });
        telephone.setRejectedCallback(remote -> {
            System.out.println("\nCall was rejected\n");
            onCallTerminated(remote, "rejected");
        });
    }

    private void loadOrCreateIdentity() throws Exception {
        Path identityPath = configDir.resolve("identity");
        if (Files.exists(identityPath)) {
            identity = Identity.fromFile(identityPath);
            if (identity == null) {
                System.err.println("Could not load identity from " + identityPath);
                System.exit(1);
            }
        } else {
            System.out.println("No identity file found, creating new...");
            identity = new Identity();
            identity.toFile(identityPath);
            System.out.println("Created new identity: " + identity.getHexHash());
        }
    }

    private void loadConfig() {
        Path configPath = configDir.resolve("config");
        if (!Files.exists(configPath)) {
            writeDefaultConfig(configPath);
            return;
        }
        try {
            Map<String, Map<String, String>> ini = parseIni(configPath);

            Map<String, String> tel = ini.getOrDefault("telephone", Map.of());
            if (tel.containsKey("ringtone")) ringtone = configDir.resolve(tel.get("ringtone")).toString();
            speaker    = tel.get("speaker");
            microphone = tel.get("microphone");
            ringer     = tel.get("ringer");

            for (Map.Entry<String, String> e : ini.getOrDefault("phonebook", Map.of()).entrySet()) {
                String name = e.getKey();
                // value may be "hash" or "hash, numeric-alias"
                String hash = e.getValue().contains(",") ? e.getValue().split(",")[0].trim() : e.getValue().trim();
                if (hash.length() == 32) {
                    phonebook.put(name, hash);
                    nameByHash.put(hash, name);
                }
            }
        } catch (IOException ex) {
            System.err.println("Could not read config file: " + ex.getMessage());
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
        telephone.announce();
        shouldRun = true;
        run();
    }

    private void cleanup() {
        shouldRun = false;
    }

    // ── Call control ─────────────────────────────────────────────────────────

    private void dial(String identityHashHex) {
        if (identityHashHex.length() != 32) {
            System.out.println("Invalid identity hash (must be 32 hex characters)");
            becomeAvailable();
            return;
        }
        byte[] identityHashBytes;
        try {
            identityHashBytes = hexDecode(identityHashHex);
        } catch (IllegalArgumentException ex) {
            System.out.println("Invalid identity hash: " + ex.getMessage());
            becomeAvailable();
            return;
        }

        lastDialledHash = identityHashHex;

        byte[] destHash = telephonyDestHash(identityHashBytes);

        // Request path if not already known (Telephone.call() also does this, but we need
        // the recalled Identity before calling it, and path responses carry the identity).
        telephone.setBusy(true);
        if (!Boolean.TRUE.equals(Transport.getInstance().hasPath(destHash))) {
            System.out.println("Finding path...");
            Transport.getInstance().requestPath(destHash);
            long deadline = System.currentTimeMillis() + PATH_TIMEOUT_SEC * 1000L;
            while (!Boolean.TRUE.equals(Transport.getInstance().hasPath(destHash))
                   && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(200); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    telephone.setBusy(false);
                    return;
                }
            }
        }
        telephone.setBusy(false);

        if (!Boolean.TRUE.equals(Transport.getInstance().hasPath(destHash))) {
            System.out.println("Path request timed out");
            becomeAvailable();
            return;
        }

        Identity recalled = IdentityKnownDestination.recall(destHash);
        if (recalled == null) {
            System.out.println("Identity not known for " + identityHashHex
                    + " — wait for their announce or ask them to send one");
            becomeAvailable();
            return;
        }

        int    hops     = Transport.getInstance().hopsTo(destHash);
        String hopLabel = hops == 1 ? "hop" : "hops";
        System.out.println("Connecting call over " + hops + " " + hopLabel + "...");
        state          = STATE_CONNECTING;
        remoteIdentity = recalled;
        telephone.call(recalled);
    }

    // ── Telephone callbacks ──────────────────────────────────────────────────

    private void onRinging(Identity remote) {
        state          = STATE_RINGING;
        remoteIdentity = remote;
        System.out.println("\n\nIncoming call from " + remote.getHexHash());
        System.out.println("Hit enter to answer, " + BOLD + "r" + END + " to reject");
    }

    private void onEstablished(Identity remote) {
        state = STATE_IN_CALL;
        System.out.println("Call established with " + remote.getHexHash());
        startCallStatusDisplay();
    }

    private void onCallTerminated(Identity remote, String reason) {
        String remoteHash = remote != null ? remote.getHexHash() : "?";
        if ("ended".equals(reason)) {
            if      (state == STATE_IN_CALL)    System.out.println("\nCall with " + remoteHash + " ended\n");
            else if (state == STATE_RINGING)    System.out.println("\nCall from " + remoteHash + " was not answered\n");
            else if (state == STATE_CONNECTING) System.out.println("\nCall to " + remoteHash + " could not be connected\n");
        }
        // busy/rejected: message already printed in the lambda callbacks
        state          = STATE_AVAILABLE;
        remoteIdentity = null;
        becomeAvailable();
    }

    // ── Main input loop ──────────────────────────────────────────────────────

    private void run() {
        System.out.println("\n" + BOLD + "Reticulum Telephone Utility is ready" + END);
        System.out.println("  Identity hash: " + identity.getHexHash() + "\n");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        becomeAvailable();

        while (shouldRun) {
            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                break;
            }
            if (line == null) break;
            String input = line.trim();

            if (state == STATE_AVAILABLE) {
                if (inPhonebook) {
                    inPhonebook = false;
                    handlePhonebookInput(input);
                } else if (input.length() == 32 && input.matches("[0-9a-fA-F]+")) {
                    dial(input);
                } else {
                    handleCommand(input);
                }
            } else if (state == STATE_RINGING) {
                if (input.isEmpty()) {
                    Identity caller = remoteIdentity;
                    System.out.println("Answering call from " + (caller != null ? caller.getHexHash() : "?"));
                    if (caller == null || !telephone.answer(caller)) System.out.println("Could not answer call");
                } else {
                    Identity caller = remoteIdentity;
                    System.out.println("Rejecting call from " + (caller != null ? caller.getHexHash() : "?"));
                    telephone.hangup();
                }
            } else if (state == STATE_IN_CALL || state == STATE_CONNECTING) {
                Identity remote = remoteIdentity;
                System.out.println("\nHanging up with " + (remote != null ? remote.getHexHash() : "?"));
                telephone.hangup();
            }
        }
    }

    private void handleCommand(String input) {
        switch (input) {
            case "p":
            case "phonebook":
                printPhonebook();
                break;
            case "r":
            case "redial":
                if (lastDialledHash != null) {
                    dial(lastDialledHash);
                } else {
                    System.out.println("No previous call\n");
                    becomeAvailable();
                }
                break;
            case "i":
            case "identity":
                System.out.println("Identity hash: " + identity.getHexHash() + "\n");
                becomeAvailable();
                break;
            case "d":
            case "desthash":
                byte[] destHash = DestinationUtils.hash(identity, LXST.APP_NAME, "telephony");
                System.out.println("Destination hash: " + hexEncode(destHash) + "\n");
                becomeAvailable();
                break;
            case "a":
            case "announce":
                telephone.announce();
                System.out.println("Announce sent\n");
                becomeAvailable();
                break;
            case "h":
            case "help":
            case "?":
                printHelp();
                becomeAvailable();
                break;
            case "q":
            case "quit":
            case "exit":
                cleanup();
                System.exit(0);
                break;
            default:
                if (!input.isEmpty()) System.out.println("Unknown command. Type " + BOLD + "h" + END + " for help.\n");
                becomeAvailable();
                break;
        }
    }

    private void handlePhonebookInput(String input) {
        try {
            int n = Integer.parseInt(input);
            String[] entries = phonebook.keySet().toArray(new String[0]);
            if (n >= 1 && n <= entries.length) {
                dial(phonebook.get(entries[n - 1]));
                return;
            }
        } catch (NumberFormatException ignored) {}
        becomeAvailable();
    }

    private void printHelp() {
        System.out.println();
        System.out.println(UNDERLINE + "Available commands" + END);
        System.out.println("  " + BOLD + "p" + END + "honebook : Open the phonebook");
        System.out.println("  " + BOLD + "r" + END + "edial    : Call the last called identity again");
        System.out.println("  " + BOLD + "i" + END + "dentity  : Display the identity hash of this telephone");
        System.out.println("  " + BOLD + "d" + END + "esthash  : Display the destination hash of this telephone");
        System.out.println("  " + BOLD + "a" + END + "nnounce  : Send an announce from this telephone");
        System.out.println("  " + BOLD + "q" + END + "uit      : Exit the program");
        System.out.println("  " + BOLD + "h" + END + "elp      : This help menu");
        System.out.println();
    }

    private void printPhonebook() {
        if (phonebook.isEmpty()) {
            System.out.println("\nNo entries in phonebook\n");
            becomeAvailable();
            return;
        }
        System.out.println();
        System.out.println(UNDERLINE + "Phonebook" + END);
        int n = 0;
        int maxLen = phonebook.keySet().stream().mapToInt(String::length).max().orElse(0);
        for (Map.Entry<String, String> e : phonebook.entrySet()) {
            n++;
            String pad = " ".repeat(maxLen - e.getKey().length());
            System.out.println("  " + BOLD + n + END + " " + e.getKey() + pad + "  <" + e.getValue() + ">");
        }
        System.out.println("\nEnter number to dial, or anything else to go back");
        inPhonebook = true;
        becomeAvailable();
    }

    private void becomeAvailable() {
        System.out.print("\n> ");
        System.out.flush();
    }

    private void startCallStatusDisplay() {
        Thread t = new Thread(() -> {
            long started = System.currentTimeMillis();
            String erase = "";
            while (state == STATE_IN_CALL) {
                long   elapsed = (System.currentTimeMillis() - started) / 1000;
                String msg     = "In call for " + formatDuration(elapsed) + ", hit enter to hang up ";
                System.out.print("\r" + erase + "\r" + msg);
                System.out.flush();
                erase = " ".repeat(msg.length());
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            }
            System.out.print("\r" + erase + "\r");
            System.out.flush();
        }, "rnphone-call-status");
        t.setDaemon(true);
        t.start();
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    /**
     * Computes the RNS destination hash for "lxst.telephony" + identityHash.
     * Mirrors Python: RNS.Destination.hash_from_name_and_identity("lxst.telephony", identityHash)
     *
     * destHash = SHA256(SHA256("lxst.telephony")[0:10] + identityHash)[0:16]
     * where 10 = NAME_HASH_LENGTH/8 and 16 = TRUNCATED_HASHLENGTH/8
     */
    private static byte[] telephonyDestHash(byte[] identityHash) {
        byte[] nameHash = Arrays.copyOf(
                IdentityUtils.fullHash("lxst.telephony".getBytes(StandardCharsets.UTF_8)),
                10   // NAME_HASH_LENGTH / 8
        );
        byte[] combined = IdentityUtils.concatArrays(nameHash, identityHash);
        return Arrays.copyOf(IdentityUtils.fullHash(combined), 16); // TRUNCATED_HASHLENGTH / 8
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexDecode(String hex) {
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("Odd-length hex string");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            int hi = Character.digit(hex.charAt(i),     16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Non-hex character at index " + i);
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60)   return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    /** Minimal INI parser — returns sections[sectionName][key] = value */
    private static Map<String, Map<String, String>> parseIni(Path path) throws IOException {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        String current = null;
        for (String raw : Files.readAllLines(path)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).trim();
                sections.putIfAbsent(current, new LinkedHashMap<>());
            } else if (current != null && line.contains("=")) {
                int    eq  = line.indexOf('=');
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                int    cmt = val.indexOf('#');
                if (cmt >= 0) val = val.substring(0, cmt).trim();
                sections.get(current).put(key, val);
            }
        }
        return sections;
    }

    private void writeDefaultConfig(Path path) {
        String config =
            "# Reticulum Telephone configuration\n\n" +
            "[telephone]\n" +
            "    # ringtone = ringer.opus\n" +
            "    # speaker = device name\n" +
            "    # microphone = device name\n" +
            "    # ringer = device name\n" +
            "    # allowed_callers = all\n\n" +
            "[phonebook]\n" +
            "    # Mary = f3e8c3359b39d36f3baff0a616a73d3e\n" +
            "    # Jake = b8d80b1b7a9d3147880b366995422a45\n";
        try {
            Files.writeString(path, config);
        } catch (IOException ex) {
            System.err.println("Could not write default config: " + ex.getMessage());
        }
    }
}
