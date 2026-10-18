import java.util.Objects;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;

/**
 * The center of the Profiles tab: the outline of the mouse with the editor of every button
 * next to it. Each editor sits in a column beside the mouse and is tied to its button by a
 * leader line. While the pointer is over an editor, the zone of that button is highlighted
 * on the mouse.
 * <p>
 * The zones and the points where the leader lines end are given in pixels of the original
 * image (mouseOutline.png), so they follow the image when it is scaled.
 */
public class MouseDiagramPane extends Pane {

    // The image is loaded from the classpath (src/main/resources)
    private static final String IMAGE_PATH = "/mouseOutline.png";

    private static final double COLUMN_WIDTH = 190;     // width of an editor
    private static final double PADDING = 10;           // space around everything
    private static final double GAP = 10;               // space between a column and the image
    private static final double ROW_GAP = 8;            // space between two editors of a column

    // Highlight zone of every slot (index into ProfileData.SLOT_NAMES) as x, y pairs in image pixels
    private static final double[][] ZONES = {
        { 282, 40, 378, 40, 378, 600, 282, 600 },       // left click
        { 656, 40, 750, 40, 750, 600, 656, 600 },       // right click
        { 485, 195, 560, 195, 560, 455, 485, 455 },     // middle click (the whole wheel)
        { 232, 548, 280, 548, 280, 760, 232, 760 },     // side button 1 (front)
        { 232, 760, 280, 760, 280, 975, 232, 975 },     // side button 2 (rear)
        { 485, 195, 560, 195, 560, 325, 485, 325 },     // scroll up (upper half of the wheel)
        { 485, 325, 560, 325, 560, 455, 485, 455 }      // scroll down (lower half of the wheel)
    };

    // Where the leader line of every slot ends, as x, y in image pixels
    private static final double[][] ANCHORS = {
        { 330, 120 },   // left click
        { 704, 120 },   // right click
        { 540, 290 },   // middle click
        { 256, 650 },   // side button 1
        { 256, 870 },   // side button 2
        { 500, 250 },   // scroll up
        { 540, 400 }    // scroll down
    };

    // Slots of each column, from top to bottom
    private static final int[] LEFT_SLOTS = { 0, 5, 3, 4 };
    private static final int[] RIGHT_SLOTS = { 1, 2, 6 };

    private static final String ZONE_STYLE = "-fx-fill: -fx-accent; -fx-stroke: -fx-accent; -fx-stroke-width: 1;";
    private static final String LEADER_STYLE = "-fx-stroke: -fx-text-base-color; -fx-stroke-width: 1;";
    private static final String LEADER_HIGHLIGHT_STYLE = "-fx-stroke: -fx-accent; -fx-stroke-width: 2;";

    private final Image image;
    private final ImageView imageView;
    private final Polygon[] zones = new Polygon[ProfileData.BINDING_COUNT];
    private final Line[] leaders = new Line[ProfileData.BINDING_COUNT];
    private final Region[] slotNodes = new Region[ProfileData.BINDING_COUNT];

    // Slot (index into ProfileData.SLOT_NAMES) of the button being hovered, or -1
    private int highlightedSlot = -1;

    public MouseDiagramPane() {
        image = new Image(Objects.requireNonNull(MouseDiagramPane.class.getResource(IMAGE_PATH), "Missing resource " + IMAGE_PATH).toExternalForm());
        imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setManaged(false);
        imageView.setMouseTransparent(true);
        getChildren().add(imageView);

        for (int i = 0; i < zones.length; i++) {
            zones[i] = new Polygon();
            zones[i].setStyle(ZONE_STYLE);
            zones[i].setOpacity(0.4);
            zones[i].setManaged(false);
            zones[i].setMouseTransparent(true);
            getChildren().add(zones[i]);
        }

        for (int i = 0; i < leaders.length; i++) {
            leaders[i] = new Line();
            leaders[i].setManaged(false);
            leaders[i].setMouseTransparent(true);
            getChildren().add(leaders[i]);
        }

        setHighlightedSlot(-1);
    }

    /**
     * Puts the editor of a button into the diagram. It is placed beside the mouse and tied to
     * the button with a leader line.
     *
     * @param slot index into ProfileData.SLOT_NAMES
     * @param node the editor of that button
     */
    public void setSlotNode(int slot, Region node) {
        if (slotNodes[slot] != null) {
            getChildren().remove(slotNodes[slot]);
        }

        slotNodes[slot] = node;
        getChildren().add(node);
        requestLayout();
    }

    /**
     * Called while the pointer is over a button's editor. Highlights the zone of that button
     * and its leader line.
     *
     * @param slot index into ProfileData.SLOT_NAMES, or -1 for no highlight
     */
    public void setHighlightedSlot(int slot) {
        highlightedSlot = slot;

        for (int i = 0; i < zones.length; i++) {
            boolean highlighted = i == highlightedSlot;
            zones[i].setVisible(highlighted);
            leaders[i].setStyle(highlighted ? LEADER_HIGHLIGHT_STYLE : LEADER_STYLE);
        }
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();

        // the image gets the space between the two columns
        double areaX = PADDING + COLUMN_WIDTH + GAP;
        double areaWidth = width - 2 * areaX;
        double areaHeight = height - 2 * PADDING;
        double scale = Math.max(Math.min(areaWidth / image.getWidth(), areaHeight / image.getHeight()), 0.01);

        double imageWidth = image.getWidth() * scale;
        double imageHeight = image.getHeight() * scale;
        double offsetX = areaX + (areaWidth - imageWidth) / 2;
        double offsetY = PADDING + (areaHeight - imageHeight) / 2;

        imageView.setFitWidth(imageWidth);
        imageView.setFitHeight(imageHeight);
        imageView.relocate(offsetX, offsetY);

        for (int i = 0; i < zones.length; i++) {
            Double[] points = new Double[ZONES[i].length];

            for (int j = 0; j < points.length; j += 2) {
                points[j] = offsetX + ZONES[i][j] * scale;
                points[j + 1] = offsetY + ZONES[i][j + 1] * scale;
            }

            zones[i].getPoints().setAll(points);
        }

        layoutColumn(LEFT_SLOTS, PADDING, true, scale, offsetX, offsetY, height);
        layoutColumn(RIGHT_SLOTS, width - PADDING - COLUMN_WIDTH, false, scale, offsetX, offsetY, height);
    }

    // Places the editors of one column next to the height of their buttons, pushing them apart
    // where they would overlap, and draws the leader lines
    private void layoutColumn(int[] slots, double x, boolean isLeft, double scale, double offsetX, double offsetY, double height) {
        double[] tops = new double[slots.length];
        double[] heights = new double[slots.length];
        double previousBottom = PADDING - ROW_GAP;

        // top to bottom: as close to the button as possible without overlapping the editor above
        for (int i = 0; i < slots.length; i++) {
            Region node = slotNodes[slots[i]];
            heights[i] = node == null ? 0 : node.prefHeight(COLUMN_WIDTH);

            double wanted = offsetY + ANCHORS[slots[i]][1] * scale - heights[i] / 2;
            tops[i] = Math.max(wanted, previousBottom + ROW_GAP);
            previousBottom = tops[i] + heights[i];
        }

        // bottom to top: nothing may run below the pane, so the lower editors move up
        for (int i = slots.length - 1; i >= 0; i--) {
            double lowest = (i == slots.length - 1 ? height - PADDING : tops[i + 1] - ROW_GAP) - heights[i];

            if (tops[i] > lowest) {
                tops[i] = lowest;
            }
        }

        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            Region node = slotNodes[slot];

            if (node != null) {
                node.resizeRelocate(x, tops[i], COLUMN_WIDTH, heights[i]);
            }

            // the line runs from the side of the editor that faces the mouse to the button
            Line leader = leaders[slot];
            leader.setStartX(isLeft ? x + COLUMN_WIDTH : x);
            leader.setStartY(tops[i] + heights[i] / 2);
            leader.setEndX(offsetX + ANCHORS[slot][0] * scale);
            leader.setEndY(offsetY + ANCHORS[slot][1] * scale);
        }
    }

    // The columns and the image scale to whatever space the pane gets
    @Override
    protected double computeMinWidth(double height) {
        return 0;
    }

    @Override
    protected double computeMinHeight(double width) {
        return 0;
    }

    @Override
    protected double computePrefWidth(double height) {
        return 2 * (PADDING + COLUMN_WIDTH + GAP) + 400;
    }

    @Override
    protected double computePrefHeight(double width) {
        return 600;
    }
}