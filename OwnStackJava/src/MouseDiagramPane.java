import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

/**
 * The center of the Profiles tab. For now only a label; the diagram of the mouse with its
 * buttons will go here.
 */
public class MouseDiagramPane extends StackPane {

    // Slot (index into ProfileData.SLOT_NAMES) of the button being hovered in the sidebar, or -1
    private int highlightedSlot = -1;

    public MouseDiagramPane() {
        getChildren().add(new Label("Mouse diagram"));
    }

    /**
     * Called while the pointer is over a button's editor in the sidebar. There is no diagram
     * yet, so nothing is drawn; the diagram will highlight the button of this slot.
     *
     * @param slot index into ProfileData.SLOT_NAMES, or -1 for no highlight
     */
    public void setHighlightedSlot(int slot) {
        highlightedSlot = slot;
    }
}