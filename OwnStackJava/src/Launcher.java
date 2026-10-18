public class Launcher {

    public static void main(String[] args) {
        // Separate entry point so the JavaFX runtime check is bypassed in the fat jar
        OwnStackApp.main(args);
    }
}