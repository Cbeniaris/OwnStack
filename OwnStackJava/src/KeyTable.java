import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The key table object of the mouse: every key that can be used in a binding or a macro, in
 * the order the mouse sends them. Each entry is the HID usage code, then the shell name and
 * the display label, both zero terminated.
 */
public class KeyTable {

    /** One key of the table. */
    public static class KeyInfo {
        public final int code;
        public final String name;
        public final String label;

        public KeyInfo(int code, String name, String label) {
            this.code = code;
            this.name = name;
            this.label = label;
        }
    }

    private final List<KeyInfo> keys;

    private KeyTable(List<KeyInfo> keys) {
        this.keys = Collections.unmodifiableList(keys);
    }

    public static KeyTable parse(byte[] table) throws IOException {
        List<KeyInfo> keys = new ArrayList<>();
        int position = 0;

        while (position < table.length) {
            int code = table[position++] & 0xFF;

            int nameEnd = findTerminator(table, position);
            String name = new String(table, position, nameEnd - position, StandardCharsets.US_ASCII);
            position = nameEnd + 1;

            int labelEnd = findTerminator(table, position);
            String label = new String(table, position, labelEnd - position, StandardCharsets.US_ASCII);
            position = labelEnd + 1;

            keys.add(new KeyInfo(code, name, label));
        }

        return new KeyTable(keys);
    }

    private static int findTerminator(byte[] table, int start) throws IOException {
        for (int i = start; i < table.length; i++) {
            if (table[i] == 0) {
                return i;
            }
        }

        throw new IOException("The key table is malformed.");
    }

    public List<KeyInfo> getKeys() {
        return keys;
    }
}