import java.io.IOException;
import java.util.Arrays;

/**
 * Host side of the mouse command protocol. Sends one request at a time over a FrameLink,
 * waits for the matching response and retransmits after a timeout. Objects (profiles,
 * macros, key table) are moved as raw bytes in the wire layout; the CRC is checked here.
 */
public class ProtocolClient {

    public static final int FRAME_SIZE = 32;
    public static final int HEADER_SIZE = 4;
    public static final int MAX_PAYLOAD = FRAME_SIZE - HEADER_SIZE;

    // Bytes of object data in one OBJ_READ response and in one OBJ_WRITE_DATA request
    public static final int READ_CHUNK_SIZE = MAX_PAYLOAD - 4;
    public static final int WRITE_CHUNK_SIZE = MAX_PAYLOAD - 2;

    public static final int OPCODE_PING = 0x01;
    public static final int OPCODE_INFO = 0x02;
    public static final int OPCODE_PROFILE_LIST = 0x10;
    public static final int OPCODE_PROFILE_CREATE = 0x11;
    public static final int OPCODE_PROFILE_DELETE = 0x12;
    public static final int OPCODE_PROFILE_ACTIVATE = 0x13;
    public static final int OPCODE_MACRO_LIST = 0x20;
    public static final int OPCODE_MACRO_RESET = 0x21;
    public static final int OPCODE_OBJ_READ = 0x30;
    public static final int OPCODE_OBJ_WRITE_BEGIN = 0x31;
    public static final int OPCODE_OBJ_WRITE_DATA = 0x32;
    public static final int OPCODE_OBJ_WRITE_COMMIT = 0x33;
    public static final int OPCODE_OBJ_WRITE_ABORT = 0x34;

    public static final int STATUS_OK = 0;

    public static final int OBJECT_PROFILE = 1;
    public static final int OBJECT_MACRO = 2;
    public static final int OBJECT_KEY_TABLE = 3;

    private static final int RESPONSE_FLAG = 0x80;
    private static final int REQUEST_FLAG_RETRY = 0x80;

    private static final String[] STATUS_NAMES = {
        "OK", "BAD_REQUEST", "NOT_FOUND", "EXISTS", "FORBIDDEN", "STORAGE_ERROR",
        "BUSY", "BAD_SEQUENCE", "BAD_CRC", "UNSUPPORTED", "NO_LINK"
    };

    // How long to wait for a response, how often a request is sent, and the read poll step
    private final int responseTimeoutMs;
    private final int attempts;
    private static final int POLL_STEP_MS = 50;

    private final FrameLink link;
    private int nextSeq = 0;
    private DeviceInfo cachedInfo = null;

    /** The mouse answered with a status other than OK. */
    public static class ProtocolException extends IOException {
        private final int status;

        public ProtocolException(int opcode, int status) {
            super(String.format("opcode 0x%02X failed with status %s", opcode, statusName(status)));
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /** Contents of the INFO response. */
    public static class DeviceInfo {
        public int protocolVersion;
        public int role;
        public byte[] deviceId;
        public int profileCount;
        public int macroCount;
        public int macroMaxSteps;
        public int profileNameMax;
        public int macroNameMax;
        public int keyTableCrc;
        public int cpiMin;
        public int cpiMax;
        public int cpiStep;
    }

    /** Contents of the PROFILE_LIST response. */
    public static class ProfileList {
        public int activeIndex;
        public boolean[] exists;
    }

    public ProtocolClient(FrameLink link) {
        this(link, 500, 3);
    }

    public ProtocolClient(FrameLink link, int responseTimeoutMs, int attempts) {
        this.link = link;
        this.responseTimeoutMs = responseTimeoutMs;
        this.attempts = attempts;
    }

    public static String statusName(int status) {
        if (status >= 0 && status < STATUS_NAMES.length) {
            return STATUS_NAMES[status];
        }

        return "UNKNOWN(" + status + ")";
    }

    /**
     * CRC-16/CCITT-FALSE (polynomial 0x1021, initial value 0xFFFF, no reflection, no final xor).
     */
    public static int crc16(byte[] data, int length) {
        int crc = 0xFFFF;

        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF) << 8;

            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }

        return crc;
    }

    public static int getU16(byte[] data, int position) {
        return (data[position] & 0xFF) | ((data[position + 1] & 0xFF) << 8);
    }

    public static void putU16(byte[] data, int position, int value) {
        data[position] = (byte) (value & 0xFF);
        data[position + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /**
     * Sends one request and returns the payload of its response. A request is sent again
     * (marked as a retransmission, so the mouse never executes it twice) if no response
     * arrives in time.
     *
     * @throws ProtocolException if the mouse answers with a status other than OK
     * @throws IOException if no response arrives after all attempts
     */
    private byte[] request(int opcode, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too long");
        }

        int seq = nextSeq;
        nextSeq = (nextSeq + 1) & 0xFF;

        byte[] frame = new byte[FRAME_SIZE];
        frame[0] = (byte) opcode;
        frame[1] = (byte) seq;
        frame[3] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, HEADER_SIZE, payload.length);

        // throw away anything left over from an earlier request
        while (link.receive(1) != null) {
            // nothing to do
        }

        for (int attempt = 0; attempt < attempts; attempt++) {
            frame[2] = (byte) (attempt == 0 ? 0 : REQUEST_FLAG_RETRY);
            link.send(frame);

            long deadline = System.currentTimeMillis() + responseTimeoutMs;

            while (System.currentTimeMillis() < deadline) {
                byte[] response = link.receive(POLL_STEP_MS);

                if (response == null || response.length != FRAME_SIZE) {
                    continue;
                }

                if ((response[0] & 0xFF) != (opcode | RESPONSE_FLAG) || (response[1] & 0xFF) != seq) {
                    continue;
                }

                int status = response[2] & 0xFF;
                int length = response[3] & 0xFF;
                byte[] responsePayload = Arrays.copyOfRange(response, HEADER_SIZE, HEADER_SIZE + Math.min(length, MAX_PAYLOAD));

                if (status != STATUS_OK) {
                    throw new ProtocolException(opcode, status);
                }

                return responsePayload;
            }
        }

        throw new IOException(String.format( "No response from the mouse to opcode 0x%02X (seq=%d, attempts=%d, timeout=%d ms each).", opcode, seq, attempts, responseTimeoutMs
));
    }

    public void ping() throws IOException {
        request(OPCODE_PING, new byte[0]);
    }

    /** Reads the device info. The result is kept, because the limits do not change. */
    public DeviceInfo info() throws IOException {
        if (cachedInfo != null) {
            return cachedInfo;
        }

        byte[] data = request(OPCODE_INFO, new byte[0]);

        if (data.length < 23) {
            throw new IOException("INFO response too short.");
        }

        DeviceInfo info = new DeviceInfo();
        info.protocolVersion = data[0] & 0xFF;
        info.role = data[1] & 0xFF;
        info.deviceId = Arrays.copyOfRange(data, 2, 10);
        info.profileCount = data[10] & 0xFF;
        info.macroCount = data[11] & 0xFF;
        info.macroMaxSteps = data[12] & 0xFF;
        info.profileNameMax = data[13] & 0xFF;
        info.macroNameMax = data[14] & 0xFF;
        info.keyTableCrc = getU16(data, 15);
        info.cpiMin = getU16(data, 17);
        info.cpiMax = getU16(data, 19);
        info.cpiStep = getU16(data, 21);

        cachedInfo = info;
        return info;
    }

    public ProfileList profileList() throws IOException {
        int count = info().profileCount;
        byte[] data = request(OPCODE_PROFILE_LIST, new byte[0]);

        if (data.length < 1 + (count + 7) / 8) {
            throw new IOException("PROFILE_LIST response too short.");
        }

        ProfileList list = new ProfileList();
        list.activeIndex = data[0] & 0xFF;
        list.exists = bitmapToArray(data, 1, count);

        return list;
    }

    /** Returns for every macro slot whether the macro has at least one step. */
    public boolean[] macroList() throws IOException {
        int count = info().macroCount;
        byte[] data = request(OPCODE_MACRO_LIST, new byte[0]);

        if (data.length < (count + 7) / 8) {
            throw new IOException("MACRO_LIST response too short.");
        }

        return bitmapToArray(data, 0, count);
    }

    private static boolean[] bitmapToArray(byte[] data, int start, int count) {
        boolean[] bits = new boolean[count];

        for (int i = 0; i < count; i++) {
            bits[i] = (data[start + i / 8] & (1 << (i % 8))) != 0;
        }

        return bits;
    }

    public void createProfile(int index) throws IOException {
        request(OPCODE_PROFILE_CREATE, new byte[] { (byte) index });
    }

    /** Deletes a profile and returns the index of the profile that is active afterwards. */
    public int deleteProfile(int index) throws IOException {
        byte[] data = request(OPCODE_PROFILE_DELETE, new byte[] { (byte) index });
        return data[0] & 0xFF;
    }

    public void activateProfile(int index) throws IOException {
        request(OPCODE_PROFILE_ACTIVATE, new byte[] { (byte) index });
    }

    public void resetMacro(int index) throws IOException {
        request(OPCODE_MACRO_RESET, new byte[] { (byte) index });
    }

    /**
     * Reads a whole object (see the spec for the layouts) and checks its CRC.
     *
     * @param type  OBJECT_PROFILE, OBJECT_MACRO or OBJECT_KEY_TABLE
     * @param index slot number (ignored for the key table)
     * @return the object bytes
     */
    public byte[] readObject(int type, int index) throws IOException {
        byte[] object = null;
        int total = -1;
        int crc = 0;
        int offset = 0;

        while (true) {
            byte[] payload = new byte[4];
            payload[0] = (byte) type;
            payload[1] = (byte) index;
            putU16(payload, 2, offset);

            byte[] data = request(OPCODE_OBJ_READ, payload);

            if (data.length < 4) {
                throw new IOException("OBJ_READ response too short.");
            }

            int responseTotal = getU16(data, 0);
            int responseCrc = getU16(data, 2);

            if (object == null) {
                total = responseTotal;
                crc = responseCrc;
                object = new byte[total];
            } else if (responseTotal != total || responseCrc != crc) {
                throw new IOException("The object changed while it was being read.");
            }

            int chunk = data.length - 4;

            if (offset + chunk > total) {
                throw new IOException("OBJ_READ returned more data than the object holds.");
            }

            System.arraycopy(data, 4, object, offset, chunk);
            offset += chunk;

            if (offset >= total) {
                break;
            }

            if (chunk == 0) {
                throw new IOException("OBJ_READ made no progress.");
            }
        }

        if (crc16(object, object.length) != crc) {
            throw new IOException("CRC mismatch on the received object.");
        }

        return object;
    }

    /**
     * Writes a whole object. The mouse validates it and saves it to flash on commit; on any
     * failure nothing is changed on the mouse.
     *
     * @param type  OBJECT_PROFILE or OBJECT_MACRO
     * @param index slot number
     * @param data  the object bytes in the wire layout
     */
    public void writeObject(int type, int index, byte[] data) throws IOException {
        byte[] begin = new byte[6];
        begin[0] = (byte) type;
        begin[1] = (byte) index;
        putU16(begin, 2, data.length);
        putU16(begin, 4, crc16(data, data.length));

        request(OPCODE_OBJ_WRITE_BEGIN, begin);

        try {
            int offset = 0;

            while (offset < data.length) {
                int chunk = Math.min(WRITE_CHUNK_SIZE, data.length - offset);
                byte[] payload = new byte[2 + chunk];
                putU16(payload, 0, offset);
                System.arraycopy(data, offset, payload, 2, chunk);

                request(OPCODE_OBJ_WRITE_DATA, payload);
                offset += chunk;
            }

            request(OPCODE_OBJ_WRITE_COMMIT, new byte[0]);
        } catch (IOException e) {
            abortWrite();
            throw e;
        }
    }

    // Best effort: tells the mouse to drop an open write. Errors are ignored on purpose.
    private void abortWrite() {
        try {
            request(OPCODE_OBJ_WRITE_ABORT, new byte[0]);
        } catch (IOException ignored) {
            // the mouse drops an unfinished write by itself after 10 seconds
        }
    }
}
