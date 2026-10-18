import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Lists every macro slot on the left, the editor for the selected macro on the right. Edits
 * are made on a copy and only reach the mouse when Apply is pressed; Discard goes back to
 * what was last read from (or written to) the mouse.
 */
public class MacrosTab extends Tab {

    private final MouseSession session;
    private final ListView<Integer> macroList = new ListView<>();
    private final TextField nameField = new TextField();
    private final ComboBox<String> triggerBox = new ComboBox<>();
    private final TextField repeatDelayBox = new TextField();
    private final ListView<MacroData.Step> stepList = new ListView<>();
    private final VBox editor = new VBox(8);
    private final Button applyButton = new Button("Apply");
    private final Button discardButton = new Button("Discard");

    private MouseSession.State lastState = null;
    private int selectedIndex = -1;
    private MacroData baseline = null;      // the selected macro as stored on the mouse
    private MacroData current = null;       // the selected macro with the edits
    private boolean loading = false;        // true while the widgets are filled from the data

    public MacrosTab(MouseSession session) {
        super("Macros");
        this.session = session;
        setClosable(false);

        // left: the macro slots
        macroList.setCellFactory(list -> new ListCell<Integer>() {
            @Override
            protected void updateItem(Integer index, boolean empty) {
                super.updateItem(index, empty);
                setText(empty || index == null ? null : describeSlot(index));
            }
        });

        macroList.getSelectionModel().selectedItemProperty().addListener((observable, oldIndex, newIndex) -> {
            if (loading || newIndex == null) {
                return;
            }

            if (!confirmDiscard()) {
                // put the selection back on the macro that is being edited
                Platform.runLater(() -> {
                    loading = true;
                    macroList.getSelectionModel().select(selectedIndex);
                    loading = false;
                });
                return;
            }

            selectedIndex = newIndex;
            loadSelected();
        });

        // right: name and trigger mode on top, then the steps
        nameField.setTextFormatter(new TextFormatter<String>(change -> {
            String text = change.getControlNewText();

            if (text.length() > MacroData.NAME_LENGTH) {
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

        triggerBox.getItems().addAll(MacroData.TRIGGER_NAMES);
        triggerBox.setMaxWidth(Double.MAX_VALUE);

        triggerBox.getSelectionModel().selectedIndexProperty().addListener((observable, oldIndex, newIndex) -> {
            if (loading || current == null || newIndex.intValue() < 0) {
                return;
            }

            current.trigger = newIndex.intValue();
            updateRepeatDelay();
            updateButtons();
        });

        // digits only, and nothing above what the u16 field of the mouse can hold
        repeatDelayBox.setTextFormatter(numberFormatter(MacroData.REPEAT_DELAY_MAX));

        repeatDelayBox.textProperty().addListener((observable, oldText, newText) -> {
            // an empty field keeps the last value until the user leaves it
            if (loading || current == null || newText.isEmpty()) {
                return;
            }

            current.repeatDelayMs = Integer.parseInt(newText);
            updateButtons();
        });

        // leaving the field shows the value that will be written (also fixes an empty field)
        repeatDelayBox.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (!isFocused && current != null) {
                repeatDelayBox.setText(String.valueOf(current.repeatDelayMs));
            }
        });

        stepList.setPlaceholder(new Label("No steps"));

        stepList.setCellFactory(list -> {
            ListCell<MacroData.Step> cell = new ListCell<MacroData.Step>() {
                @Override
                protected void updateItem(MacroData.Step step, boolean empty) {
                    super.updateItem(step, empty);
                    setText(empty || step == null ? null : (getIndex() + 1) + "  " + describeStep(step));
                }
            };

            MenuItem insertBefore = new MenuItem("Insert before");
            MenuItem insertAfter = new MenuItem("Insert after");
            MenuItem remove = new MenuItem("Remove");

            insertBefore.setOnAction(event -> insertStep(cell.getIndex()));
            insertAfter.setOnAction(event -> insertStep(cell.getIndex() + 1));
            remove.setOnAction(event -> removeStep(cell.getIndex()));

            ContextMenu menu = new ContextMenu(insertBefore, insertAfter, remove);

            // no inserting once the macro holds the most steps the mouse allows
            menu.setOnShowing(event -> {
                insertBefore.setDisable(isFull());
                insertAfter.setDisable(isFull());
            });

            // cells are reused, so only a cell that holds a step gets the menu
            cell.emptyProperty().addListener((observable, wasEmpty, isEmpty) -> cell.setContextMenu(isEmpty ? null : menu));

            return cell;
        });

        // right click below the last step (or in an empty list) adds a step at the end
        MenuItem addStep = new MenuItem("Add step");
        addStep.setOnAction(event -> insertStep(current == null ? 0 : current.steps.size()));

        ContextMenu listMenu = new ContextMenu(addStep);
        listMenu.setOnShowing(event -> addStep.setDisable(current == null || isFull()));
        stepList.setContextMenu(listMenu);

        editor.getChildren().addAll(new Label("Name"), nameField, new Label("Trigger"), triggerBox,
            new Label("Repeat delay (ms)"), repeatDelayBox, new Label("Steps"), stepList);
        VBox.setVgrow(stepList, Priority.ALWAYS);
        editor.setDisable(true);

        applyButton.setOnAction(event -> apply());
        discardButton.setOnAction(event -> discard());

        VBox rightBox = new VBox(8, editor, new HBox(6, applyButton, discardButton));
        VBox.setVgrow(editor, Priority.ALWAYS);
        rightBox.setPadding(new Insets(10));

        // the list gets about a quarter of the width
        SplitPane root = new SplitPane(macroList, rightBox);
        root.setDividerPositions(0.25);
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
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "The macro has changes that were not applied.", discard, keep);
        alert.setHeaderText(null);

        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == discard;
    }

    private void refresh() {
        MouseSession.State state = session.getState();

        if (state == null) {
            return;
        }

        if (state != lastState) {
            lastState = state;

            List<Integer> indexes = new ArrayList<>();

            for (int i = 0; i < state.macroNames.length; i++) {
                indexes.add(i);
            }

            // a new connection: nothing is selected, so nothing is being edited
            loading = true;
            macroList.getItems().setAll(indexes);
            selectedIndex = -1;
            baseline = null;
            current = null;
            loadEditor();
            loading = false;
            updateButtons();
        } else {
            macroList.refresh();
        }
    }

    private String describeSlot(int index) {
        MouseSession.State state = session.getState();

        if (state == null || index >= state.macroNames.length) {
            return "";
        }

        String name = state.macroNames[index];
        return (index + 1) + "  " + (name == null ? "(empty)" : (name.isEmpty() ? "(unnamed)" : name));
    }

    private String describeStep(MacroData.Step step) {
        switch (step.type) {
        case MacroData.STEP_KEY_DOWN:
        case MacroData.STEP_KEY_UP:
            return MacroData.STEP_TYPE_NAMES[step.type] + ": " + keyLabel(step.value);
        case MacroData.STEP_MOUSE_DOWN:
        case MacroData.STEP_MOUSE_UP:
            return MacroData.STEP_TYPE_NAMES[step.type] + ": " + mouseButtonLabel(step.value);
        default:
            return "Delay: " + step.value + " ms";
        }
    }

    private String mouseButtonLabel(int button) {
        return button < MacroData.MOUSE_BUTTON_NAMES.length ? MacroData.MOUSE_BUTTON_NAMES[button] : String.valueOf(button);
    }

    private String keyLabel(int code) {
        MouseSession.State state = session.getState();

        if (state != null) {
            for (KeyTable.KeyInfo key : state.keys.getKeys()) {
                if (key.code == code) {
                    return key.label;
                }
            }
        }

        return String.format("0x%02X", code);
    }

    // Reads the selected macro from the mouse and shows it
    private void loadSelected() {
        int index = selectedIndex;

        // the editor stays disabled until the macro has arrived
        baseline = null;
        current = null;
        loadEditor();
        updateButtons();

        // TODO: decide how an empty slot (macroNames[index] == null) is loaded
        session.run("Reading macro...", c -> MacroData.fromBytes(c.readObject(ProtocolClient.OBJECT_MACRO, index)), macro -> {
            // the selection may have moved on while the macro was being read
            if (index != selectedIndex) {
                return;
            }

            baseline = macro;
            current = macro.copy();

            loading = true;
            loadEditor();
            loading = false;
            updateButtons();
        });
    }

    // Fills the widgets from the edit copy (or clears and disables them if nothing is loaded)
    private void loadEditor() {
        editor.setDisable(current == null);

        if (current == null) {
            nameField.clear();
            repeatDelayBox.clear();
            triggerBox.getSelectionModel().clearSelection();
            updateRepeatDelay();
            stepList.getItems().clear();
            return;
        }

        nameField.setText(current.name);
        triggerBox.getSelectionModel().select(current.trigger);
        repeatDelayBox.setText(String.valueOf(current.repeatDelayMs));
        updateRepeatDelay();
        stepList.getItems().setAll(current.steps);
    }

    // The repeat delay only applies to the two modes that repeat
    private void updateRepeatDelay() {
        repeatDelayBox.setDisable(current == null || !MacroData.usesRepeatDelay(current.trigger));
    }

    private boolean isFull() {
        MouseSession.State state = session.getState();
        return current != null && state != null && current.steps.size() >= state.info.macroMaxSteps;
    }

    private void insertStep(int position) {
        if (current == null || isFull()) {
            return;
        }

        Optional<MacroData.Step> step = promptForStep();

        if (step.isPresent()) {
            current.steps.add(position, step.get());
            stepList.getItems().setAll(current.steps);
            updateButtons();
        }
    }

    private void removeStep(int position) {
        if (current == null || position < 0 || position >= current.steps.size()) {
            return;
        }

        current.steps.remove(position);
        stepList.getItems().setAll(current.steps);
        updateButtons();
    }

    /** One entry of the value dropdown: the number that is stored and the text that is shown. */
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

    // Only digits, and nothing above max
    private static TextFormatter<String> numberFormatter(int max) {
        return new TextFormatter<String>(change -> {
            String text = change.getControlNewText();

            if (!text.matches("\\d{0,5}")) {
                return null;
            }

            if (!text.isEmpty() && Integer.parseInt(text) > max) {
                return null;
            }

            return change;
        });
    }

    // Asks for the type of the step first, then for its value; empty if it was cancelled
    private Optional<MacroData.Step> promptForStep() {
        MouseSession.State state = session.getState();

        if (state == null) {
            return Optional.empty();
        }

        List<ValueChoice> keyChoices = new ArrayList<>();

        for (KeyTable.KeyInfo key : state.keys.getKeys()) {
            keyChoices.add(new ValueChoice(key.code, key.label));
        }

        List<ValueChoice> mouseChoices = new ArrayList<>();

        for (int i = 0; i < MacroData.MOUSE_BUTTON_NAMES.length; i++) {
            mouseChoices.add(new ValueChoice(i, MacroData.MOUSE_BUTTON_NAMES[i]));
        }

        ComboBox<String> typeBox = new ComboBox<>();
        ComboBox<ValueChoice> valueBox = new ComboBox<>();
        TextField delayField = new TextField();

        typeBox.getItems().addAll(MacroData.STEP_TYPE_NAMES);
        typeBox.setMaxWidth(Double.MAX_VALUE);
        valueBox.setMaxWidth(Double.MAX_VALUE);
        delayField.setPromptText("Milliseconds");
        delayField.setTextFormatter(numberFormatter(MacroData.STEP_DELAY_MAX));

        delayField.setMaxWidth(Double.MAX_VALUE);

        // nothing to choose until the type is known; the two controls share one slot, so the
        // dialog has its full height from the start and does not grow when one is shown
        valueBox.setVisible(false);
        delayField.setVisible(false);
        StackPane valueSlot = new StackPane(valueBox, delayField);

        Dialog<MacroData.Step> dialog = new Dialog<>();
        dialog.setTitle("Add step");
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        ButtonType add = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(add, ButtonType.CANCEL);

        VBox box = new VBox(8, new Label("Action"), typeBox, valueSlot);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);

        Node addButton = dialog.getDialogPane().lookupButton(add);
        addButton.setDisable(true);

        // Add is only possible once the step has everything it needs
        Runnable updateAddButton = () -> {
            int type = typeBox.getSelectionModel().getSelectedIndex();
            boolean complete;

            if (type < 0) {
                complete = false;
            } else if (type == MacroData.STEP_DELAY) {
                complete = !delayField.getText().isEmpty();
            } else {
                complete = valueBox.getSelectionModel().getSelectedItem() != null;
            }

            addButton.setDisable(!complete);
        };

        typeBox.getSelectionModel().selectedIndexProperty().addListener((observable, oldIndex, newIndex) -> {
            int type = newIndex.intValue();
            boolean isDelay = type == MacroData.STEP_DELAY;
            boolean isKey = type == MacroData.STEP_KEY_DOWN || type == MacroData.STEP_KEY_UP;

            valueBox.getItems().setAll(isKey ? keyChoices : mouseChoices);
            valueBox.getSelectionModel().clearSelection();

            valueBox.setVisible(!isDelay);
            delayField.setVisible(isDelay);

            updateAddButton.run();
        });

        valueBox.getSelectionModel().selectedItemProperty().addListener((observable, oldChoice, newChoice) -> updateAddButton.run());
        delayField.textProperty().addListener((observable, oldText, newText) -> updateAddButton.run());

        dialog.setResultConverter(button -> {
            if (button != add) {
                return null;
            }

            int type = typeBox.getSelectionModel().getSelectedIndex();

            if (type == MacroData.STEP_DELAY) {
                return new MacroData.Step(type, Integer.parseInt(delayField.getText()));
            }

            return new MacroData.Step(type, valueBox.getSelectionModel().getSelectedItem().value);
        });

        return dialog.showAndWait();
    }

    // Writes the edit copy to the mouse and then reads it back, like applyProfile does
    private void apply() {
        int index = selectedIndex;
        boolean empty = current.steps.isEmpty();
        byte[] bytes = empty ? null : current.toBytes();

        session.run("Writing macro...", c -> {
            // a macro without steps is cleared instead of written
            if (empty) {
                c.resetMacro(index);
            } else {
                c.writeObject(ProtocolClient.OBJECT_MACRO, index, bytes);
            }

            return MacroData.fromBytes(c.readObject(ProtocolClient.OBJECT_MACRO, index));
        }, stored -> {
            if (index == selectedIndex) {
                baseline = stored;
                current = stored.copy();

                loading = true;
                loadEditor();
                loading = false;
                updateButtons();
            }

            // the name in the list may have changed
            session.refreshMacros();
        });
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

    private void updateButtons() {
        boolean dirty = isDirty();

        applyButton.setDisable(!dirty || !current.isValid());
        discardButton.setDisable(!dirty);
    }
}