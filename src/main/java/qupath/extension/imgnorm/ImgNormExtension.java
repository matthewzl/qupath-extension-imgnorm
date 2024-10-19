package qupath.extension.imgnorm;

import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import java.util.concurrent.CompletableFuture;

public class ImgNormExtension implements QuPathExtension {
    private static final String VERSION = "0.1.0";

    @Override
    public void installExtension(QuPathGUI qupath) {

        var menu = qupath.getMenu("Extensions>ImgNorm", true);
        MenuItem menuItem = new MenuItem("Normalize images to new project");
        ImgNormRunner imgNormRunner = new ImgNormRunner(qupath);

        menuItem.setOnAction(e -> {

            CompletableFuture<Void> runFuture = CompletableFuture.runAsync(() -> {
                Platform.runLater(() -> menuItem.setDisable(true));
            }).thenRunAsync(imgNormRunner).thenRunAsync(() -> {
                Platform.runLater(() -> menuItem.setDisable(false));
            }).exceptionally(ex -> {
                menuItem.setDisable(false);
                throw new RuntimeException(ex);
            });

        });

        menu.getItems().add(menuItem);

    }

    @Override
    public String getName() {
        return "ImgNorm";
    }

    @Override
    public String getDescription() {
        return "Color-normalize H&E images \n\n"
                + "Version " + VERSION;
    }

}