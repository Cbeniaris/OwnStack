import java.io.IOException;
import java.util.Arrays;

import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;
import org.hid4java.HidServicesSpecification;

/**
 * FrameLink over a vendor-defined HID config interface (hid4java): the mouse's own interface
 * when it is connected by cable, or the wireless dongle's interface, which forwards the frames
 * to the mouse over the radio.
 */
public class HidFrameLink implements FrameLink {

    // Zephyr's test vendor ID and the mouse's product ID (development values, see main.c)
    public static final int VENDOR_ID = 0x2FE3;
    public static final int PRODUCT_ID = 0x0100;

    // The wireless dongle's product ID (development value, see the dongle's main.c)
    public static final int DONGLE_PRODUCT_ID = 0x0101;

    // Usage page of the config interface; the mouse interface uses the generic desktop page
    public static final int CONFIG_USAGE_PAGE = 0xFF00;

    private final HidServices hidServices;
    private final HidDevice device;

    private HidFrameLink(HidServices hidServices, HidDevice device) {
        this.hidServices = hidServices;
        this.device = device;
    }

    /**
     * Finds the mouse's config interface (cable) and opens it.
     *
     * @return the open link
     * @throws IOException if the interface is not found or cannot be opened
     */
    public static HidFrameLink open() throws IOException {
        return open(PRODUCT_ID);
    }

    /**
     * Finds the wireless dongle's config interface and opens it. The dongle only forwards
     * the frames, so the mouse must be switched on, and it has to be moved to deliver the
     * first request of a session (a request the mouse does not pick up within about 2 seconds
     * is dropped by the dongle).
     *
     * @return the open link
     * @throws IOException if the interface is not found or cannot be opened
     */
    public static HidFrameLink openDongle() throws IOException {
        return open(DONGLE_PRODUCT_ID);
    }

    /**
     * Opens the mouse's config interface if the mouse is connected by cable, otherwise the
     * wireless dongle's. If the mouse is visible but cannot be opened (for example because of
     * a missing udev rule), the dongle is tried as well.
     *
     * @return the open link
     * @throws IOException if neither interface can be opened (the message holds both reasons)
     */
    public static HidFrameLink openPreferringCable() throws IOException {
        try {
            return open();
        } catch (IOException cableError) {
            try {
                return openDongle();
            } catch (IOException dongleError) {
                throw new IOException("No config interface could be opened."
                    + String.format("%nCable: %s%nDongle: %s", cableError.getMessage(), dongleError.getMessage()),
                    dongleError);
            }
        }
    }

    private static HidFrameLink open(int productId) throws IOException {
        HidServicesSpecification specification = new HidServicesSpecification();
        specification.setAutoStart(false);

        HidServices hidServices = HidManager.getHidServices(specification);
        hidServices.start();

        HidDevice found = null;
        StringBuilder seen = new StringBuilder();

        for (HidDevice candidate : hidServices.getAttachedHidDevices()) {
            if (candidate.getVendorId() != VENDOR_ID || candidate.getProductId() != productId) {
                continue;
            }

            seen.append(String.format("%n  interface %d, usage page 0x%04X",
                candidate.getInterfaceNumber(), candidate.getUsagePage()));

            if ((candidate.getUsagePage() & 0xFFFF) == CONFIG_USAGE_PAGE) {
                found = candidate;
                break;
            }
        }

        if (found == null) {
            hidServices.shutdown();
            throw new IOException(String.format("Config interface not found (vendor 0x%04X, product 0x%04X, usage page 0xFF00).",
                VENDOR_ID, productId)
                + (seen.length() == 0 ? " No device with that vendor/product ID is visible." : " HID interfaces seen:" + seen));
        }

        if (!found.open()) {
            hidServices.shutdown();
            throw new IOException("Could not open the config interface. Check the udev rule.");
        }

        return new HidFrameLink(hidServices, found);
    }

    @Override
    public void send(byte[] frame) throws IOException {
        int written = device.write(frame, frame.length, (byte) 0);

        if (written < 0) {
            throw new IOException("Writing to the config interface failed.");
        }
    }

    @Override
    public byte[] receive(int timeoutMs) throws IOException {
        byte[] buffer = new byte[ProtocolClient.FRAME_SIZE];
        int read = device.read(buffer, timeoutMs);

        if (read < 0) {
            throw new IOException("Reading from the config interface failed.");
        }

        if (read == 0) {
            return null;
        }

        return Arrays.copyOf(buffer, read);
    }

    @Override
    public void close() {
        device.close();
        hidServices.shutdown();
    }
}