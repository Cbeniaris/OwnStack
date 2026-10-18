import java.io.IOException;

/**
 * One link that carries 32-byte protocol frames to and from a device: the mouse's own USB
 * config interface now, the dongle's later. ProtocolClient only talks to this interface.
 */
public interface FrameLink extends AutoCloseable {

    /**
     * Sends one frame.
     *
     * @param frame exactly ProtocolClient.FRAME_SIZE bytes
     * @throws IOException if the frame could not be written
     */
    void send(byte[] frame) throws IOException;

    /**
     * Waits for the next frame from the device.
     *
     * @param timeoutMs how long to wait
     * @return the received frame, or null if nothing arrived in time
     * @throws IOException if reading failed
     */
    byte[] receive(int timeoutMs) throws IOException;

    @Override
    void close();
}
