import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/**
 * Configuration window of the mouse: a Profiles tab and a Macros tab. Connects when it starts;
 * the Connect button connects again.
 */
public class OwnStackApp extends Application {

    private MouseSession session;

    @Override
    public void start(Stage stage) {
        session = new MouseSession();

        ProfilesTab profilesTab = new ProfilesTab(session);
        MacrosTab macrosTab = new MacrosTab(session);

        TabPane tabs = new TabPane(profilesTab, macrosTab);
        tabs.disableProperty().bind(session.busyProperty().or(session.connectedProperty().not()));

        Button connectButton = new Button("Connect");
        connectButton.disableProperty().bind(session.busyProperty());
        connectButton.setOnAction(event -> {
            if (profilesTab.confirmDiscard()) {
                session.connect();
            }
        });

        Label statusLabel = new Label();
        statusLabel.textProperty().bind(session.statusProperty());

        HBox statusBar = new HBox(10, connectButton, statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(8, 10, 8, 10));

        BorderPane root = new BorderPane();
        root.setTop(statusBar);
        root.setCenter(tabs);

        stage.setTitle("Mouse Configuration");
        stage.setScene(new Scene(root, 1000, 650));
        stage.show();

        session.connect();
    }

    @Override
    public void stop() {
        session.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}