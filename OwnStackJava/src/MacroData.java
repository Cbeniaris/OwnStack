import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One macro: name, trigger mode, repeat delay and its steps. Mirrors struct mouseMacro of the
 * firmware. Works the way ProfileData does: fromBytes / toBytes convert to and from the wire
 * layout, copy gives the edit copy, equals tells whether the edit copy was changed.
 */
public class MacroData {

    // Step types, in the order of enum macroStepType
    public static final int STEP_KEY_DOWN = 0;
    public static final int STEP_KEY_UP = 1;
    public static final int STEP_MOUSE_DOWN = 2;
    public static final int STEP_MOUSE_UP = 3;
    public static final int STEP_DELAY = 4;

    public static final String[] STEP_TYPE_NAMES = { "Key Down", "Key Up", "Mouse Button Down", "Mouse Button Up", "Delay" };

    // Trigger modes, in the order of enum macroTrigger
    public static final int TRIGGER_ONCE = 0;
    public static final int TRIGGER_WHILE_HELD = 1;
    public static final int TRIGGER_TOGGLE = 2;

    public static final String[] TRIGGER_NAMES = { "Once", "While Held", "Toggle" };

    public static final int NAME_LENGTH = 20;   // bytes, zero padded

    // repeatDelayMs is a u16 on the wire, so this is the largest value that can be stored
    public static final int REPEAT_DELAY_MAX = 0xFFFF;

    // a step value is a u16 too, which also limits a delay step
    public static final int STEP_DELAY_MAX = 0xFFFF;

    // Left, Right, Middle: the first three values of ProfileData.MOUSE_BUTTON_NAMES
    public static final String[] MOUSE_BUTTON_NAMES = { "Left", "Right", "Middle" };

    // Wire layout: name (20 bytes, zero padded), trigger, stepCount, repeatDelayMs (u16, little
    // endian), then only the steps that are used. The 24 bytes before the steps are what the
    // mouse sent for a macro without steps. The order of trigger, stepCount and repeatDelayMs
    // is the order of struct mouseMacro, which still has to be confirmed with a macro that has
    // steps: if it is wrong, fromBytes fails with a size mismatch.
    // MAX_STEPS must match MACRO_MAX_STEPS of the firmware.
    // STEP_SIZE is 3 if the step is packed and 4 if it is padded; if it is wrong, fromBytes
    // fails and its message shows the size the mouse really sent.
    public static final int MAX_STEPS = 50;
    public static final int STEP_SIZE = 3;

    private static final int OFFSET_NAME = 0;
    private static final int OFFSET_TRIGGER = OFFSET_NAME + NAME_LENGTH;
    private static final int OFFSET_STEP_COUNT = OFFSET_TRIGGER + 1;
    private static final int OFFSET_REPEAT_DELAY = OFFSET_STEP_COUNT + 1;
    private static final int OFFSET_STEPS = OFFSET_REPEAT_DELAY + 2;

    /** Whether a macro with this trigger repeats, and so uses its repeat delay. */
    public static boolean usesRepeatDelay(int trigger) {
        return trigger == TRIGGER_WHILE_HELD || trigger == TRIGGER_TOGGLE;
    }

    /** One step. Immutable: to change a step, replace it in the list. */
    public static class Step {
        public final int type;
        public final int value;

        public Step(int type, int value) {
            this.type = type;
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof Step)) {
                return false;
            }

            Step that = (Step) other;
            return type == that.type && value == that.value;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, value);
        }
    }

    public String name = "";
    public int trigger = 0;
    public int repeatDelayMs = 0;
    public final List<Step> steps = new ArrayList<>();

    public static MacroData fromBytes(byte[] data) throws IOException {
        if (data.length < OFFSET_STEPS) {
            throw new IOException("Unexpected macro size: " + data.length + " bytes (at least " + OFFSET_STEPS + " expected).");
        }

        MacroData macro = new MacroData();

        // the name ends at the first zero, or after NAME_LENGTH bytes
        int nameLength = 0;

        while (nameLength < NAME_LENGTH && data[OFFSET_NAME + nameLength] != 0) {
            nameLength++;
        }

        macro.name = new String(data, OFFSET_NAME, nameLength, StandardCharsets.US_ASCII);

        macro.trigger = data[OFFSET_TRIGGER] & 0xFF;

        if (macro.trigger >= TRIGGER_NAMES.length) {
            throw new IOException("Unknown macro trigger: " + macro.trigger + ".");
        }

        int stepCount = data[OFFSET_STEP_COUNT] & 0xFF;

        if (stepCount > MAX_STEPS) {
            throw new IOException("The macro reports " + stepCount + " steps (at most " + MAX_STEPS + ").");
        }

        macro.repeatDelayMs = ProtocolClient.getU16(data, OFFSET_REPEAT_DELAY);

        int expectedSize = OFFSET_STEPS + stepCount * STEP_SIZE;

        if (data.length != expectedSize) {
            throw new IOException("Unexpected macro size: " + data.length + " bytes for " + stepCount
                + " steps (expected " + expectedSize + ").");
        }

        for (int i = 0; i < stepCount; i++) {
            int position = OFFSET_STEPS + i * STEP_SIZE;
            int type = data[position] & 0xFF;

            if (type >= STEP_TYPE_NAMES.length) {
                throw new IOException("Unknown step type " + type + " in step " + (i + 1) + ".");
            }

            // the value is the last two bytes of the step (after the padding byte, if there is one)
            macro.steps.add(new Step(type, ProtocolClient.getU16(data, position + STEP_SIZE - 2)));
        }

        return macro;
    }

    /**
     * @throws IllegalStateException if the macro cannot be written (see isValid) or is too long
     */
    public byte[] toBytes() {
        if (!isValid()) {
            throw new IllegalStateException("The macro is not complete.");
        }

        if (steps.size() > MAX_STEPS || name.length() > NAME_LENGTH) {
            throw new IllegalStateException("The macro is too long.");
        }

        byte[] data = new byte[OFFSET_STEPS + steps.size() * STEP_SIZE];

        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(nameBytes, 0, data, OFFSET_NAME, nameBytes.length);

        data[OFFSET_TRIGGER] = (byte) trigger;
        data[OFFSET_STEP_COUNT] = (byte) steps.size();
        ProtocolClient.putU16(data, OFFSET_REPEAT_DELAY, repeatDelayMs);

        for (int i = 0; i < steps.size(); i++) {
            int position = OFFSET_STEPS + i * STEP_SIZE;
            Step step = steps.get(i);

            data[position] = (byte) step.type;
            ProtocolClient.putU16(data, position + STEP_SIZE - 2, step.value);
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

    /** A macro with steps needs a name. A macro without steps is cleared, so its name does not matter. */
    public boolean isValid() {
        return steps.isEmpty() || isNameValid();
    }

    public MacroData copy() {
        MacroData copy = new MacroData();
        copy.name = name;
        copy.trigger = trigger;
        copy.repeatDelayMs = repeatDelayMs;
        copy.steps.addAll(steps);
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof MacroData)) {
            return false;
        }

        MacroData that = (MacroData) other;
        return Objects.equals(name, that.name) && trigger == that.trigger
            && repeatDelayMs == that.repeatDelayMs && steps.equals(that.steps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, trigger, repeatDelayMs, steps);
    }
}