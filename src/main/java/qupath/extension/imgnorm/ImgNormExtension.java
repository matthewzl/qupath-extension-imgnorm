package qupath.extension.imgnorm;

import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import java.util.concurrent.CompletableFuture;

public class ImgNormExtension implements QuPathExtension, GitHubProject {
    private static final String VERSION = "0.1.1";

    @Override
    public void installExtension(QuPathGUI qupath) {

        var menu = qupath.getMenu("Extensions>ImgNorm", true);
        MenuItem menuItem = new MenuItem("Normalize H&E images");
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
        return "ImgNorm extension";
    }

    @Override
    public String getDescription() {
        return "Color-normalize H&E images \n\n"
                + "Version " + VERSION;
    }

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create(getName(), "matthewzl", "qupath-extension-imgnorm");
    }

}