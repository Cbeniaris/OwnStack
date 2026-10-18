import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Profiles on the left, the mouse diagram in the middle, the editor for the selected profile
 * on the right. Edits are made on a copy and only reach the mouse when Apply is pressed;
 * Discard goes back to what was last read from (or written to) the mouse.
 */
public class ProfilesTab extends Tab {

    // The default profile slot, which can never be deleted
    private static final int DEFAULT_INDEX = 0;

    /** One entry of a value drop-down. */
    private static class ValueChoice {
        final int value;
        final String text;

        ValueChoice(int value, String text) {
            this.value = value;
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final MouseSession session;
    private final MouseDiagramPane diagramPane = new MouseDiagramPane();
    private final ListView<Integer> profileList = new ListView<>();
    private final Button createButton = new Button("Create");
    private final Button deleteButton = new Button("Delete");
    private final Button activateButton = new Button("Activate");
    private final TextField nameField = new TextField();
    private final ComboBox<Integer> cpiBox = new ComboBox<>();
    private final BindingRow[] rows = new BindingRow[ProfileData.BINDING_COUNT];
    private final VBox editor = new VBox(8);
    private final Button applyButton = new Button("Apply");
    private final Button discardButton = new Button("Discard");

    private MouseSession.State lastState = null;
    private int selectedIndex = -1;
    private ProfileData baseline = null;    // the selected profile as stored on the mouse
    private ProfileData current = null;     // the selected profile with the edits
    private boolean loading = false;        // true while the widgets are filled from the data

    public ProfilesTab(MouseSession session) {
        super("Profiles");
        this.session = session;
        setClosable(false);

        // left: profile list and its buttons
        profileList.setCellFactory(list -> new ListCell<Integer>() {
            @Override
            protected void updateItem(Integer index, boolean empty) {
                super.updateItem(index, empty);
                setText(empty || index == null ? null : describeSlot(index));
            }
        });

        profileList.getSelectionModel().selectedItemProperty().addListener((observable, oldIndex, newIndex) -> {
            if (loading || newIndex == null) {
                return;
            }

            if (!confirmDiscard()) {
                Platform.runLater(() -> {
                    loading = true;
                    profileList.getSelectionModel().select(selectedIndex);
                    loading = false;
                });
                return;
            }

            selectedIndex = newIndex;
            refresh();
        });

        createButton.setOnAction(event -> session.createProfile(selectedIndex));
        deleteButton.setOnAction(event -> confirmDelete());
        activateButton.setOnAction(event -> session.activateProfile(selectedIndex));

        VBox leftBox = new VBox(8, profileList, new HBox(6, createButton, deleteButton, activateButton));
        VBox.setVgrow(profileList, Priority.ALWAYS);
        leftBox.setPadding(new Insets(10));
        leftBox.setPrefWidth(260);

        // right: name, CPI and the buttons to program
        nameField.setTextFormatter(new TextFormatter<String>(change -> {
            String text = change.getControlNewText();

            if (text.length() > ProfileData.NAME_LENGTH) {
                return null;
            }

            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);

                if (c < 0x20 || c > 0x7E) {
                    return null;
                }
            }

            return change;
        }));

        nameField.textProperty().addListener((observable, oldText, newText) -> {
            if (loading || current == null) {
                return;
            }

            current.name = newText;
            updateButtons();
        });

        cpiBox.setMaxWidth(Double.MAX_VALUE);

        cpiBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (loading || current == null || newValue == null) {
                return;
            }

            current.cpi = newValue;
            updateButtons();
        });

        editor.getChildren().addAll(new Label("Name"), nameField, new Label("CPI"), cpiBox, new Label("Buttons"));

        for (int i = 0; i < rows.length; i++) {
            rows[i] = new BindingRow(i);
            editor.getChildren().add(rows[i]);
        }

        editor.setPadding(new Insets(0, 10, 0, 0));
        editor.setDisable(true);

        ScrollPane scroll = new ScrollPane(editor);
        scroll.setFitToWidth(true);

        applyButton.setOnAction(event -> session.applyProfile(selectedIndex, current));
        discardButton.setOnAction(event -> discard());

        VBox rightBox = new VBox(8, scroll, new HBox(6, applyButton, discardButton));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        rightBox.setPadding(new Insets(10));
        rightBox.setPrefWidth(290);

        BorderPane root = new BorderPane();
        root.setLeft(leftBox);
        root.setCenter(diagramPane);
        root.setRight(rightBox);
        setContent(root);

        session.addListener(this::refresh);
        updateButtons();
    }

    /**
     * Asks whether unapplied changes may be thrown away.
     *
     * @return true if there is nothing to lose or the user chose to discard
     */
    public boolean confirmDiscard() {
        if (!isDirty()) {
            return true;
        }

        ButtonType discard = new ButtonType("Discard changes", ButtonBar.ButtonData.OK_DONE);
        ButtonType keep = new ButtonType("Keep editing", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "The profile has changes that were not applied.", discard, keep);
        alert.setHeaderText(null);

        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == discard;
    }

    private void confirmDelete() {
        ButtonType delete = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Delete profile " + (selectedIndex + 1) + " (" + baseline.name + ")?", delete, cancel);
        alert.setHeaderText(null);

        Optional<ButtonType> answer = alert.showAndWait();

        if (answer.isPresent() && answer.get() == delete) {
            session.deleteProfile(selectedIndex);
        }
    }

    private void discard() {
        if (baseline == null) {
            return;
        }

        loading = true;
        current = baseline.copy();
        loadEditor();
        loading = false;
        updateButtons();
    }

    private boolean isDirty() {
        return current != null && baseline != null && !current.equals(baseline);
    }

    private String describeSlot(int index) {
        MouseSession.State state = session.getState();

        if (state == null || index >= state.profiles.length) {
            return "";
        }

        ProfileData profile = state.profiles[index];
        String text = (index + 1) + "  " + (profile == null ? "(unused)" : profile.name);

        if (index == state.activeIndex) {
            text += "  [active]";
        }

        return text;
    }

    // Brings the whole tab in line with the session state. Unapplied edits survive unless the
    // stored profile itself was replaced (new connection, apply, create, delete).
    private void refresh() {
        MouseSession.State state = session.getState();

        if (state == null) {
            return;
        }

        loading = true;

        if (state != lastState) {
            lastState = state;
            fillCpiBox(state.info);

            List<Integer> indexes = new ArrayList<>();

            for (int i = 0; i < state.profiles.length; i++) {
                indexes.add(i);
            }

            profileList.getItems().setAll(indexes);
            selectedIndex = state.activeIndex;
            baseline = null;
            current = null;
            profileList.getSelectionModel().select(selectedIndex);
        } else {
            profileList.refresh();
        }

        fillChoices(state);

        ProfileData stored = state.profiles[selectedIndex];

        if (stored != baseline) {
            baseline = stored;
            current = stored == null ? null : stored.copy();
        }

        loadEditor();
        loading = false;
        updateButtons();
    }

    // Every CPI the mouse accepts: a multiple of the step within its range
    private void fillCpiBox(ProtocolClient.DeviceInfo info) {
        List<Integer> values = new ArrayList<>();
        int first = ((info.cpiMin + info.cpiStep - 1) / info.cpiStep) * info.cpiStep;

        for (int cpi = first; cpi <= info.cpiMax; cpi += info.cpiStep) {
            values.add(cpi);
        }

        cpiBox.getItems().setAll(values);
    }

    private void fillChoices(MouseSession.State state) {
        List<ValueChoice> keyChoices = new ArrayList<>();

        for (KeyTable.KeyInfo key : state.keys.getKeys()) {
            keyChoices.add(new ValueChoice(key.code, key.label));
        }

        List<ValueChoice> macroChoices = new ArrayList<>();

        for (int i = 0; i < state.macroNames.length; i++) {
            String name = state.macroNames[i];
            String text = name == null ? "(empty)" : (name.isEmpty() ? "(unnamed)" : name);
            macroChoices.add(new ValueChoice(i, (i + 1) + ": " + text));
        }

        for (BindingRow row : rows) {
            row.setChoices(keyChoices, macroChoices);
        }
    }

    // Fills the widgets from the edit copy (or clears and disables them if the slot is unused)
    private void loadEditor() {
        editor.setDisable(current == null);

        if (current == null) {
            nameField.clear();
            cpiBox.setValue(null);

            for (BindingRow row : rows) {
                row.clear();
            }

            return;
        }

        nameField.setText(current.name);
        cpiBox.setValue(current.cpi);

        for (int i = 0; i < rows.length; i++) {
            rows[i].show(current.types[i], current.values[i]);
        }
    }

    private void updateButtons() {
        MouseSession.State state = session.getState();
        boolean exists = state != null && selectedIndex >= 0 && state.profiles[selectedIndex] != null;
        boolean dirty = isDirty();

        createButton.setDisable(state == null || selectedIndex < 0 || exists);
        deleteButton.setDisable(!exists || selectedIndex == DEFAULT_INDEX);
        activateButton.setDisable(!exists || selectedIndex == state.activeIndex);
        applyButton.setDisable(!dirty || !current.isValid());
        discardButton.setDisable(!dirty);
    }

    /** The editor of one button: what it does (type) and what it does it with (value). */
    private class BindingRow extends VBox {

        private final int slot;
        private final ComboBox<String> typeBox = new ComboBox<>();
        private final ComboBox<ValueChoice> valueBox = new ComboBox<>();
        private final List<ValueChoice> mouseChoices = new ArrayList<>();
        private List<ValueChoice> keyChoices = new ArrayList<>();
        private List<ValueChoice> macroChoices = new ArrayList<>();

        BindingRow(int slot) {
            super(4);
            this.slot = slot;

            for (int i = 0; i < ProfileData.MOUSE_BUTTON_NAMES.length; i++) {
                mouseChoices.add(new ValueChoice(i, ProfileData.MOUSE_BUTTON_NAMES[i]));
            }

            typeBox.getItems().addAll(ProfileData.TYPE_NAMES);
            typeBox.setMaxWidth(Double.MAX_VALUE);
            valueBox.setMaxWidth(Double.MAX_VALUE);
            valueBox.setPromptText("Select...");
            valueBox.setVisible(false);
            valueBox.setManaged(false);

            getChildren().addAll(new Label(ProfileData.SLOT_NAMES[slot]), typeBox, valueBox);

            // the matching button on the diagram is highlighted while the pointer is over this
            // editor, and while one of its drop-downs is open
            setOnMouseEntered(event -> diagramPane.setHighlightedSlot(slot));
            setOnMouseExited(event -> {
                if (!typeBox.isShowing() && !valueBox.isShowing()) {
                    diagramPane.setHighlightedSlot(-1);
                }
            });
            typeBox.showingProperty().addListener((observable, wasShowing, isShowing) -> {
                if (!isShowing && !isHover()) {
                    diagramPane.setHighlightedSlot(-1);
                }
            });
            valueBox.showingProperty().addListener((observable, wasShowing, isShowing) -> {
                if (!isShowing && !isHover()) {
                    diagramPane.setHighlightedSlot(-1);
                }
            });

            typeBox.getSelectionModel().selectedIndexProperty().addListener((observable, oldIndex, newIndex) -> {
                if (loading || current == null || newIndex.intValue() < 0) {
                    return;
                }

                int type = newIndex.intValue();
                current.types[slot] = type;
                current.values[slot] = ProfileData.needsValue(type) ? ProfileData.VALUE_UNSET : 0;

                loading = true;
                fillValueBox(type, ProfileData.VALUE_UNSET);
                loading = false;

                updateButtons();
            });

            valueBox.getSelectionModel().selectedItemProperty().addListener((observable, oldChoice, newChoice) -> {
                if (loading || current == null || newChoice == null) {
                    return;
                }

                current.values[slot] = newChoice.value;
                updateButtons();
            });
        }

        void setChoices(List<ValueChoice> keyChoices, List<ValueChoice> macroChoices) {
            this.keyChoices = keyChoices;
            this.macroChoices = macroChoices;
        }

        void show(int type, int value) {
            typeBox.getSelectionModel().select(type);
            fillValueBox(type, value);
        }

        void clear() {
            typeBox.getSelectionModel().clearSelection();
            fillValueBox(-1, ProfileData.VALUE_UNSET);
        }

        // Fills the value drop-down for a type and selects the choice with the given value, if any
        private void fillValueBox(int type, int value) {
            List<ValueChoice> choices;

            switch (type) {
            case ProfileData.TYPE_MOUSE_BUTTON:
                choices = mouseChoices;
                break;
            case ProfileData.TYPE_KEY:
                choices = keyChoices;
                break;
            case ProfileData.TYPE_MACRO:
                choices = macroChoices;
                break;
            default:
                choices = new ArrayList<>();
                break;
            }

            valueBox.getItems().setAll(choices);
            valueBox.getSelectionModel().clearSelection();

            for (ValueChoice choice : choices) {
                if (choice.value == value) {
                    valueBox.getSelectionModel().select(choice);
                    break;
                }
            }

            boolean visible = ProfileData.needsValue(type);
            valueBox.setVisible(visible);
            valueBox.setManaged(visible);
        }
    }
}