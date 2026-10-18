import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * One profile in the wire layout: name (20 bytes, zero padded), CPI (u16, little endian),
 * then 7 button bindings of one type byte and one value byte each.
 */
public class ProfileData {

    public static final int NAME_LENGTH = 20;
    public static final int BINDING_COUNT = 7;
    public static final int SIZE = NAME_LENGTH + 2 + BINDING_COUNT * 2;

    public static final int TYPE_NONE = 0;
    public static final int TYPE_MOUSE_BUTTON = 1;
    public static final int TYPE_WHEEL_UP = 2;
    public static final int TYPE_WHEEL_DOWN = 3;
    public static final int TYPE_KEY = 4;
    public static final int TYPE_MACRO = 5;

    public static final String[] TYPE_NAMES = { "None", "Mouse Button", "Wheel Up", "Wheel Down", "Key", "Macro" };
    public static final String[] MOUSE_BUTTON_NAMES = { "Left", "Right", "Middle", "Side 1", "Side 2" };
    public static final String[] SLOT_NAMES = { "Left Click", "Right Click", "Middle Click", "Side Button 1",
        "Side Button 2", "Scroll Up", "Scroll Down" };

    // Value of a binding whose type needs a value that has not been chosen yet
    public static final int VALUE_UNSET = -1;

    public String name = "";
    public int cpi = 0;
    public final int[] types = new int[BINDING_COUNT];
    public final int[] values = new int[BINDING_COUNT];

    /** Whether a binding of this type carries a value that the user has to choose. */
    public static boolean needsValue(int type) {
        return type == TYPE_MOUSE_BUTTON || type == TYPE_KEY || type == TYPE_MACRO;
    }

    /** Reads the zero terminated name at the start of a profile or macro object. */
    public static String readName(byte[] object) {
        int length = 0;

        while (length < NAME_LENGTH && length < object.length && object[length] != 0) {
            length++;
        }

        return new String(object, 0, length, StandardCharsets.US_ASCII);
    }

    public static ProfileData fromBytes(byte[] data) throws IOException {
        if (data.length != SIZE) {
            throw new IOException("Unexpected profile size: " + data.length + " bytes (expected " + SIZE + ").");
        }

        ProfileData profile = new ProfileData();
        profile.name = readName(data);
        profile.cpi = ProtocolClient.getU16(data, NAME_LENGTH);

        for (int i = 0; i < BINDING_COUNT; i++) {
            int position = NAME_LENGTH + 2 + i * 2;
            profile.types[i] = data[position] & 0xFF;
            profile.values[i] = data[position + 1] & 0xFF;
        }

        return profile;
    }

    /**
     * @throws IllegalStateException if the profile is not valid (see isValid)
     */
    public byte[] toBytes() {
        if (!isValid()) {
            throw new IllegalStateException("The profile is not complete.");
        }

        byte[] data = new byte[SIZE];
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(nameBytes, 0, data, 0, nameBytes.length);
        ProtocolClient.putU16(data, NAME_LENGTH, cpi);

        for (int i = 0; i < BINDING_COUNT; i++) {
            int position = NAME_LENGTH + 2 + i * 2;
            data[position] = (byte) types[i];
            data[position + 1] = (byte) values[i];
        }

        return data;
    }

    /** The name is 1 to 20 printable ASCII characters. */
    public boolean isNameValid() {
        if (name == null || name.length() < 1 || name.length() > NAME_LENGTH) {
            return false;
        }

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);

            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }

        return true;
    }

    /** Every binding whose type needs a value has one. */
    public boolean isComplete() {
        for (int i = 0; i < BINDING_COUNT; i++) {
            if (needsValue(types[i]) && values[i] < 0) {
                return false;
            }
        }

        return true;
    }

    public boolean isValid() {
        return isNameValid() && isComplete();
    }

    public ProfileData copy() {
        ProfileData copy = new ProfileData();
        copy.name = name;
        copy.cpi = cpi;
        System.arraycopy(types, 0, copy.types, 0, BINDING_COUNT);
        System.arraycopy(values, 0, copy.values, 0, BINDING_COUNT);
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof ProfileData)) {
            return false;
        }

        ProfileData that = (ProfileData) other;
        return Objects.equals(name, that.name) && cpi == that.cpi
            && Arrays.equals(types, that.types) && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, cpi, Arrays.hashCode(types), Arrays.hashCode(values));
    }
}