import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Alert;

/**
 * Owns the link to the mouse and everything pulled from it. All traffic runs one request at
 * a time on a single background thread, so the GUI never blocks. The state and the listeners
 * are only touched on the JavaFX thread.
 */
public class MouseSession implements AutoCloseable {

    /** Everything the GUI shows, as last read from the mouse. A new object per connection. */
    public static class State {
        public ProtocolClient.DeviceInfo info;
        public KeyTable keys;
        public int activeIndex;
        public ProfileData[] profiles;      // null where the slot is unused
        public String[] macroNames;         // null where the macro has no steps
    }

    /** One piece of work on the link thread. */
    public interface IoTask<T> {
        T run(ProtocolClient client) throws IOException;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "mouse-link"));

    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanProperty connected = new SimpleBooleanProperty(false);
    private final StringProperty status = new SimpleStringProperty("Not connected.");
    private final List<Runnable> listeners = new ArrayList<>();

    // only used on the link thread
    private FrameLink link = null;
    private ProtocolClient client = null;

    // only used on the JavaFX thread
    private State state = null;
    private int pending = 0;

    public BooleanProperty busyProperty() {
        return busy;
    }

    public BooleanProperty connectedProperty() {
        return connected;
    }

    public StringProperty statusProperty() {
        return status;
    }

    /** The data last read from the mouse, or null before the first successful connection. */
    public State getState() {
        return state;
    }

    /** Registers something to run whenever the state changed. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }

    /** Opens the link (cable first, then dongle) and reads everything from the mouse. */
    public void connect() {
        submit("Connecting...", ignored -> {
            closeLink();
            link = HidFrameLink.openPreferringCable();
            client = new ProtocolClient(link);
            return readState(client);
        }, newState -> {
            state = newState;
            connected.set(true);
            status.set("Connected.");
            notifyListeners();
        }, failure -> {
            connected.set(false);
            status.set("Not connected: " + oneLine(failure.getMessage()));
        });
    }

    /**
     * Runs a task on the link thread and hands its result to onSuccess on the JavaFX thread.
     * A failure is shown to the user. Meant for the macro editor and anything else that needs
     * its own protocol calls.
     */
    public <T> void run(String description, IoTask<T> task, Consumer<T> onSuccess) {
        if (!connected.get()) {
            fail(new IOException("Not connected to the mouse."));
            return;
        }

        submit(description, task, onSuccess, this::fail);
    }

    /** Creates a profile in an unused slot (from the mouse's defaults) and reads it back. */
    public void createProfile(int index) {
        run("Creating profile...", c -> {
            c.createProfile(index);
            return ProfileData.fromBytes(c.readObject(ProtocolClient.OBJECT_PROFILE, index));
        }, created -> {
            state.profiles[index] = created;
            notifyListeners();
        });
    }

    public void deleteProfile(int index) {
        run("Deleting profile...", c -> c.deleteProfile(index), newActiveIndex -> {
            state.profiles[index] = null;
            state.activeIndex = newActiveIndex;
            notifyListeners();
        });
    }

    public void activateProfile(int index) {
        run("Activating profile...", c -> {
            c.activateProfile(index);
            return index;
        }, activated -> {
            state.activeIndex = activated;
            notifyListeners();
        });
    }

    /** Writes the profile to the mouse, then reads it back so the state shows what is stored. */
    public void applyProfile(int index, ProfileData profile) {
        byte[] bytes = profile.toBytes();

        run("Writing profile...", c -> {
            c.writeObject(ProtocolClient.OBJECT_PROFILE, index, bytes);
            return ProfileData.fromBytes(c.readObject(ProtocolClient.OBJECT_PROFILE, index));
        }, stored -> {
            state.profiles[index] = stored;
            notifyListeners();
        });
    }

    /** Reads the macro list again (names of the macros that have steps). */
    public void refreshMacros() {
        run("Reading macros...", c -> readMacroNames(c), names -> {
            state.macroNames = names;
            notifyListeners();
        });
    }

    private static State readState(ProtocolClient c) throws IOException {
        State newState = new State();
        newState.info = c.info();

        if (newState.info.cpiStep <= 0) {
            throw new IOException("The mouse reported an invalid CPI step.");
        }

        newState.keys = KeyTable.parse(c.readObject(ProtocolClient.OBJECT_KEY_TABLE, 0));

        ProtocolClient.ProfileList list = c.profileList();
        newState.activeIndex = list.activeIndex;
        newState.profiles = new ProfileData[newState.info.profileCount];

        for (int i = 0; i < newState.profiles.length; i++) {
            if (list.exists[i]) {
                newState.profiles[i] = ProfileData.fromBytes(c.readObject(ProtocolClient.OBJECT_PROFILE, i));
            }
        }

        newState.macroNames = readMacroNames(c);
        return newState;
    }

    private static String[] readMacroNames(ProtocolClient c) throws IOException {
        boolean[] used = c.macroList();
        String[] names = new String[used.length];

        for (int i = 0; i < used.length; i++) {
            if (used[i]) {
                names[i] = ProfileData.readName(c.readObject(ProtocolClient.OBJECT_MACRO, i));
            }
        }

        return names;
    }

    private <T> void submit(String description, IoTask<T> task, Consumer<T> onSuccess, Consumer<IOException> onFailure) {
        pending++;
        busy.set(true);
        status.set(description);

        executor.execute(() -> {
            T result = null;
            IOException failure = null;

            try {
                result = task.run(client);
            } catch (IOException e) {
                failure = e;
            } catch (RuntimeException e) {
                failure = new IOException("Unexpected error: " + e, e);
            }

            T finalResult = result;
            IOException finalFailure = failure;

            Platform.runLater(() -> {
                pending--;

                if (pending == 0) {
                    busy.set(false);
                }

                if (finalFailure != null) {
                    onFailure.accept(finalFailure);
                } else {
                    status.set("Ready.");
                    onSuccess.accept(finalResult);
                }
            });
        });
    }

    private void fail(IOException failure) {
        status.set("Failed: " + oneLine(failure.getMessage()));

        Alert alert = new Alert(Alert.AlertType.ERROR, String.valueOf(failure.getMessage()));
        alert.setHeaderText("The request failed");
        alert.showAndWait();
    }

    private static String oneLine(String message) {
        return message == null ? "unknown error" : message.replaceAll("\\s*\\R\\s*", " | ");
    }

    private void closeLink() {
        if (link != null) {
            link.close();
            link = null;
            client = null;
        }
    }

    /** Closes the link after the queued work is done. */
    @Override
    public void close() {
        executor.execute(this::closeLink);
        executor.shutdown();
    }
}