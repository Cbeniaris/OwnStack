import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Map;

/**
 * Command line tool to try every command of the mouse protocol over the mouse's USB config
 * interface. Run without arguments to see the commands.
 */
public class ProtocolTest {

    private static final String[] BINDING_NAMES = { "none", "mouse button", "wheel up", "wheel down", "key", "macro" };
    private static final String[] BINDING_SLOTS = { "left click", "right click", "middle click", "side button 1",
        "side button 2", "scroll up", "scroll down" };
    private static final String[] MOUSE_BUTTON_NAMES = { "left", "right", "middle", "side 1", "side 2" };
    private static final String[] TRIGGER_NAMES = { "once", "while held", "toggle" };

    private static final int STEP_KEY_DOWN = 0;
    private static final int STEP_KEY_UP = 1;
    private static final int STEP_MOUSE_DOWN = 2;
    private static final int STEP_MOUSE_UP = 3;
    private static final int STEP_DELAY = 4;

    private static final int NAME_LENGTH = 20;
    private static final int PROFILE_SIZE = 36;
    private static final int MACRO_HEADER_SIZE = 24;
    private static final int MACRO_STEP_SIZE = 3;

    private final ProtocolClient client;
    private Map<Integer, String> keyNames = null;

    private ProtocolTest(ProtocolClient client) {
        this.client = client;
    }

    public static void main(String[] args) {
    // optional first argument chooses the link: --cable or --dongle (default: cable first, then dongle)
    String linkOption = "";

        if (args.length > 0 && args[0].startsWith("--")) {
            linkOption = args[0];
            args = Arrays.copyOfRange(args, 1, args.length);
        }

        if (args.length == 0) {
            printUsage();
            return;
        }

        int exitCode = 0;

        try (FrameLink link = openLink(linkOption)) {
            new ProtocolTest(new ProtocolClient(link)).run(args);
        } catch (ProtocolClient.ProtocolException e) {
            System.err.println("The mouse rejected the request: " + e.getMessage());
            exitCode = 1;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage();
            exitCode = 2;
        } catch (IOException e) {
            System.err.println(e.getMessage());
            exitCode = 1;
        }

        System.exit(exitCode);
    }

    private static FrameLink openLink(String option) throws IOException {
        switch (option) {
        case "--cable":
            return HidFrameLink.open();
        case "--dongle":
            return HidFrameLink.openDongle();
        case "":
            return HidFrameLink.openPreferringCable();
        default:
            throw new IllegalArgumentException("Unknown option: " + option);
        }
    }

    private static void printUsage() {
        System.out.println("Commands:");
        System.out.println("  ping");
        System.out.println("  info");
        System.out.println("  list                          profile slots, active profile, macros with steps");
        System.out.println("  get profile <index>           (indexes are 0 based, like the protocol)");
        System.out.println("  get macro <index>");
        System.out.println("  get keys");
        System.out.println("  create <index>                create a profile in an unused slot");
        System.out.println("  delete <index>");
        System.out.println("  activate <index>");
        System.out.println("  resetmacro <index>");
        System.out.println("  setprofile <index> <cpi> <name>   pull the profile, change cpi and name, push it back");
        System.out.println("  testmacro <index> <name>          write a test macro: press w, wait 50 ms, release w");
    }

    private void run(String[] args) throws IOException {
        switch (args[0]) {
        case "ping":
            client.ping();
            System.out.println("pong");
            break;
        case "info":
            printInfo();
            break;
        case "list":
            printList();
            break;
        case "get":
            get(args);
            break;
        case "create":
            requireArgs(args, 2);
            client.createProfile(parseIndex(args[1]));
            System.out.println("created");
            break;
        case "delete":
            requireArgs(args, 2);
            System.out.println("deleted, active profile is now " + client.deleteProfile(parseIndex(args[1])));
            break;
        case "activate":
            requireArgs(args, 2);
            client.activateProfile(parseIndex(args[1]));
            System.out.println("activated");
            break;
        case "resetmacro":
            requireArgs(args, 2);
            client.resetMacro(parseIndex(args[1]));
            System.out.println("macro reset");
            break;
        case "setprofile":
            requireArgs(args, 4);
            setProfile(parseIndex(args[1]), parseIndex(args[2]), args[3]);
            break;
        case "testmacro":
            requireArgs(args, 3);
            testMacro(parseIndex(args[1]), args[2]);
            break;
        default:
            throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void requireArgs(String[] args, int count) {
        if (args.length != count) {
            throw new IllegalArgumentException("Wrong number of arguments for " + args[0] + ".");
        }
    }

    private static int parseIndex(String text) {
        try {
            int value = Integer.parseInt(text);

            if (value < 0 || value > 65535) {
                throw new IllegalArgumentException("Number out of range: " + text);
            }

            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number: " + text);
        }
    }

    private void get(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("get needs profile, macro or keys.");
        }

        switch (args[1]) {
        case "profile":
            requireArgs(args, 3);
            printProfile(client.readObject(ProtocolClient.OBJECT_PROFILE, parseIndex(args[2])));
            break;
        case "macro":
            requireArgs(args, 3);
            printMacro(client.readObject(ProtocolClient.OBJECT_MACRO, parseIndex(args[2])));
            break;
        case "keys":
            requireArgs(args, 2);
            printKeys();
            break;
        default:
            throw new IllegalArgumentException("get needs profile, macro or keys.");
        }
    }

    private void printInfo() throws IOException {
        ProtocolClient.DeviceInfo info = client.info();
        StringBuilder id = new StringBuilder();

        for (byte b : info.deviceId) {
            id.append(String.format("%02X", b));
        }

        System.out.println("protocol version: " + info.protocolVersion);
        System.out.println("role: " + (info.role == 1 ? "mouse" : info.role == 2 ? "dongle" : "unknown " + info.role));
        System.out.println("device id: " + id);
        System.out.println("profile slots: " + info.profileCount + ", name up to " + info.profileNameMax + " characters");
        System.out.println("macro slots: " + info.macroCount + ", up to " + info.macroMaxSteps + " steps, name up to "
            + info.macroNameMax + " characters");
        System.out.println("key table crc: " + String.format("0x%04X", info.keyTableCrc));
        System.out.println("cpi: " + info.cpiMin + " to " + info.cpiMax + " in steps of " + info.cpiStep);
    }

    private void printList() throws IOException {
        ProtocolClient.ProfileList list = client.profileList();

        System.out.println("active profile: " + list.activeIndex);

        for (int i = 0; i < list.exists.length; i++) {
            if (list.exists[i]) {
                byte[] profile = client.readObject(ProtocolClient.OBJECT_PROFILE, i);
                System.out.println("  profile " + i + ": " + readName(profile) + ", " + ProtocolClient.getU16(profile, NAME_LENGTH)
                    + " CPI" + (i == list.activeIndex ? " (active)" : ""));
            } else {
                System.out.println("  profile " + i + ": unused");
            }
        }

        boolean[] macros = client.macroList();
        StringBuilder used = new StringBuilder();

        for (int i = 0; i < macros.length; i++) {
            if (macros[i]) {
                used.append(used.length() == 0 ? "" : ", ").append(i);
            }
        }

        System.out.println("macros with steps: " + (used.length() == 0 ? "none" : used));
    }

    private static String readName(byte[] object) {
        int length = 0;

        while (length < NAME_LENGTH && object[length] != 0) {
            length++;
        }

        return new String(object, 0, length, StandardCharsets.US_ASCII);
    }

    // Loads the key table once so keys can be shown by name
    private Map<Integer, String> loadKeyNames() throws IOException {
        if (keyNames == null) {
            keyNames = new LinkedHashMap<>();
            byte[] table = client.readObject(ProtocolClient.OBJECT_KEY_TABLE, 0);
            int position = 0;

            while (position < table.length) {
                int code = table[position++] & 0xFF;
                int nameStart = position;

                while (table[position] != 0) {
                    position++;
                }

                String name = new String(table, nameStart, position - nameStart, StandardCharsets.US_ASCII);
                position++;

                while (table[position] != 0) {
                    position++;
                }

                position++;
                keyNames.put(code, name);
            }
        }

        return keyNames;
    }

    private void printKeys() throws IOException {
        byte[] table = client.readObject(ProtocolClient.OBJECT_KEY_TABLE, 0);
        int crc = ProtocolClient.crc16(table, table.length);

        System.out.println(table.length + " bytes, crc " + String.format("0x%04X", crc) + ", INFO says "
            + String.format("0x%04X", client.info().keyTableCrc));

        int position = 0;
        int count = 0;

        while (position < table.length) {
            int code = table[position++] & 0xFF;
            int nameStart = position;

            while (table[position] != 0) {
                position++;
            }

            String name = new String(table, nameStart, position - nameStart, StandardCharsets.US_ASCII);
            position++;
            int labelStart = position;

            while (table[position] != 0) {
                position++;
            }

            String label = new String(table, labelStart, position - labelStart, StandardCharsets.US_ASCII);
            position++;
            count++;

            System.out.println(String.format("  0x%02X %-12s %s", code, name, label));
        }

        System.out.println(count + " keys");
    }

    private String describeBinding(int type, int value) throws IOException {
        String typeName = type < BINDING_NAMES.length ? BINDING_NAMES[type] : "unknown type " + type;

        switch (type) {
        case 1:
            return typeName + ": " + (value < MOUSE_BUTTON_NAMES.length ? MOUSE_BUTTON_NAMES[value] : "unknown " + value);
        case 4:
            return typeName + ": " + keyName(value);
        case 5:
            return typeName + ": macro " + value;
        default:
            return typeName;
        }
    }

    private String keyName(int code) throws IOException {
        String name = loadKeyNames().get(code);
        return name != null ? name : String.format("0x%02X", code);
    }

    private void printProfile(byte[] profile) throws IOException {
        System.out.println("name: " + readName(profile));
        System.out.println("cpi: " + ProtocolClient.getU16(profile, NAME_LENGTH));

        for (int i = 0; i < BINDING_SLOTS.length; i++) {
            int type = profile[NAME_LENGTH + 2 + i * 2] & 0xFF;
            int value = profile[NAME_LENGTH + 3 + i * 2] & 0xFF;
            System.out.println(BINDING_SLOTS[i] + ": " + describeBinding(type, value));
        }
    }

    private void printMacro(byte[] macro) throws IOException {
        int trigger = macro[NAME_LENGTH] & 0xFF;
        int stepCount = macro[NAME_LENGTH + 1] & 0xFF;

        System.out.println("name: " + readName(macro));
        System.out.println("trigger: " + (trigger < TRIGGER_NAMES.length ? TRIGGER_NAMES[trigger] : "unknown " + trigger));
        System.out.println("repeat delay: " + ProtocolClient.getU16(macro, NAME_LENGTH + 2) + " ms");
        System.out.println("steps: " + stepCount);

        for (int i = 0; i < stepCount; i++) {
            int position = MACRO_HEADER_SIZE + i * MACRO_STEP_SIZE;
            int type = macro[position] & 0xFF;
            int value = ProtocolClient.getU16(macro, position + 1);
            String text;

            switch (type) {
            case STEP_KEY_DOWN:
                text = "press " + keyName(value);
                break;
            case STEP_KEY_UP:
                text = "release " + keyName(value);
                break;
            case STEP_MOUSE_DOWN:
                text = "mouse down " + (value < MOUSE_BUTTON_NAMES.length ? MOUSE_BUTTON_NAMES[value] : "unknown " + value);
                break;
            case STEP_MOUSE_UP:
                text = "mouse up " + (value < MOUSE_BUTTON_NAMES.length ? MOUSE_BUTTON_NAMES[value] : "unknown " + value);
                break;
            case STEP_DELAY:
                text = "wait " + value + " ms";
                break;
            default:
                text = "unknown step type " + type;
                break;
            }

            System.out.println("  " + (i + 1) + ". " + text);
        }
    }

    private static void putName(byte[] object, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);

        if (bytes.length == 0 || bytes.length > NAME_LENGTH) {
            throw new IllegalArgumentException("The name must be 1 to " + NAME_LENGTH + " characters.");
        }

        for (int i = 0; i < NAME_LENGTH; i++) {
            object[i] = i < bytes.length ? bytes[i] : 0;
        }
    }

    // Pulls what is on the mouse, edits it, pushes it back and reads it again to compare
    private void setProfile(int index, int cpi, String name) throws IOException {
        byte[] profile = client.readObject(ProtocolClient.OBJECT_PROFILE, index);
        System.out.println("before: " + readName(profile) + ", " + ProtocolClient.getU16(profile, NAME_LENGTH) + " CPI");

        putName(profile, name);
        ProtocolClient.putU16(profile, NAME_LENGTH, cpi);

        client.writeObject(ProtocolClient.OBJECT_PROFILE, index, profile);

        byte[] check = client.readObject(ProtocolClient.OBJECT_PROFILE, index);
        System.out.println("after:  " + readName(check) + ", " + ProtocolClient.getU16(check, NAME_LENGTH) + " CPI");
        System.out.println(java.util.Arrays.equals(profile, check) ? "read back matches" : "READ BACK DIFFERS");
    }

    private void testMacro(int index, String name) throws IOException {
        byte[] macro = new byte[MACRO_HEADER_SIZE + 3 * MACRO_STEP_SIZE];
        putName(macro, name);
        macro[NAME_LENGTH] = 0;
        macro[NAME_LENGTH + 1] = 3;
        ProtocolClient.putU16(macro, NAME_LENGTH + 2, 0);

        int[][] steps = { { STEP_KEY_DOWN, 0x1A }, { STEP_DELAY, 50 }, { STEP_KEY_UP, 0x1A } };

        for (int i = 0; i < steps.length; i++) {
            int position = MACRO_HEADER_SIZE + i * MACRO_STEP_SIZE;
            macro[position] = (byte) steps[i][0];
            ProtocolClient.putU16(macro, position + 1, steps[i][1]);
        }

        client.writeObject(ProtocolClient.OBJECT_MACRO, index, macro);

        byte[] check = client.readObject(ProtocolClient.OBJECT_MACRO, index);
        printMacro(check);
        System.out.println(java.util.Arrays.equals(macro, check) ? "read back matches" : "READ BACK DIFFERS");
    }
}
