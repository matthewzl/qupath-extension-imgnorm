package qupath.extension.imgnorm;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.*;
import org.controlsfx.dialog.ProgressDialog;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.color.StainVector;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImgNormRunner implements Runnable {

    private final QuPathGUI qupath;
    private static final int TILE_SIZE_PIXELS = 5000;  // NOTE: LARGER VALUES WILL USE MORE MEMORY!
    private static final String FINAL_IMAGE_SUFFIX = "_norm.ome";
    private ProgressDialog progressDialog;
    private static final ColorDeconvolutionStains FINAL_STAINS = new ColorDeconvolutionStains("Normalized",
            StainVector.createStainVector("Hematoxylin", 0.651, 0.701, 0.29),
            StainVector.createStainVector("Eosin", 0.216, 0.801, 0.558),
            255, 255, 255);
    private boolean hasErrors = false;
    final Logger logger = LoggerFactory.getLogger(ImgNormRunner.class);


    public ImgNormRunner(QuPathGUI qupath){
        this.qupath = qupath;
    }

    @Override
    public void run() {

        Project<BufferedImage> project = qupath.getProject();
        if (project == null){
            Dialogs.showErrorMessage("Error", "Please make sure a project is loaded!");
            return;
        } else if (project.getImageList().isEmpty()){
            Dialogs.showErrorMessage("Error", "This project has no images!");
            return;
        }

        if(!Dialogs.showYesNoDialog("Begin ImgNorm", "Normalize H&E images for this project?" +
                " The normalized images will be added to a new project.")) return;

        var viewers = qupath.getAllViewers();
        List<ImageData<BufferedImage>> unsavedImageDataList = viewers.stream()
                .map(QuPathViewer::getImageData)
                .filter(Objects::nonNull)
                .filter(ImageData::isChanged)
                .toList();

        if(!unsavedImageDataList.isEmpty() && Dialogs.showYesNoDialog("Save changes",
                "There are unsaved changes in the current " +
                ((viewers.size() > 1) ? "viewers" : "viewer") + "! Would you like to save before proceeding?")) {
            for (ImageData<BufferedImage> unsavedImageData : unsavedImageDataList) {
                try {
                    project.getEntry(unsavedImageData).saveImageData(unsavedImageData);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        ImgNormTask imageProcessingTask = new ImgNormTask();
        ExecutorService pool = Executors.newSingleThreadExecutor();

        // Progress bar window configuration
        Platform.runLater(() -> {
            progressDialog = new ProgressDialog(imageProcessingTask);
            progressDialog.initOwner(qupath.getStage());
            progressDialog.setTitle("ImgNorm");
            progressDialog.setHeaderText(" "); // defer the text to the Task class below
            progressDialog.getDialogPane().setGraphic(null);
            progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
            progressDialog.setResizable(true);

            // Resize the progressDialog if the message changes in line number. (Currently unused)
            imageProcessingTask.messageProperty().addListener((v, o, n) -> {
                int newlineCount = n.split("\n").length;
                int oldLineCount = o.split("\n").length;
                int lineHeight = 17;
                int newHeight = newlineCount * lineHeight + 222; // padding adjustment

                if (newlineCount - oldLineCount == 0) return;

                if (newlineCount - oldLineCount > 0) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        for (int i = (int)progressDialog.getHeight(); i < newHeight; i++) {
                            progressDialog.setHeight(i);
                            try {
                                Thread.sleep(100/(newHeight - i)); // animate the resizing
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                } else {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        for (int i = (int)progressDialog.getHeight(); i > newHeight; i--) {
                            progressDialog.setHeight(i);
                            try {
                                Thread.sleep(100/(i - newHeight)); // animate the resizing
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                }

            });

            progressDialog.getDialogPane().lookupButton(ButtonType.CANCEL).addEventFilter(ActionEvent.ACTION, e -> {
                if (Dialogs.showYesNoDialog("Cancel run", "Are you sure you want to stop the run?")) {
                    imageProcessingTask.quietCancel();
                    imageProcessingTask.updateTaskProgress(-Double.MAX_VALUE, 100); // Make the progress bar indeterminate
                    progressDialog.setHeaderText("Cancelling...");
                    progressDialog.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);
                }
                e.consume();
            });

            progressDialog.show();
        });

        // Start the task
        try {
            CompletableFuture.runAsync(imageProcessingTask, pool).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
            hasErrors = false;
        }

    }

    class ImgNormTask extends Task<Void> {

        private boolean quietCancel = false;
        private boolean functionallyDone = false;
        public void quietCancel() {
            this.quietCancel = true;
        }
        public boolean isQuietlyCancelled() {
            return quietCancel;
        }
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();


        @Override
        protected Void call() {

            try {
                long startTime = System.currentTimeMillis();
                // Record time elapsed
                scheduler.scheduleAtFixedRate(() -> {
                    if (!isCancelled() && !isQuietlyCancelled() && !functionallyDone) {
                        long elapsedTime = (System.currentTimeMillis() - startTime) / 1000; // seconds
                        long hours = elapsedTime / 3600;
                        long minutes = (elapsedTime % 3600) / 60;
                        long seconds = elapsedTime % 60;

                        if (hours == 0 && minutes == 0 && seconds == 0)
                            Platform.runLater(() -> progressDialog.setHeaderText("Running Imgnorm..."));
                        else if (hours == 0 && minutes == 0)
                            Platform.runLater(() -> progressDialog.setHeaderText(String.format("Running Imgnorm: (%ds)", seconds)));
                        else if (hours == 0)
                            Platform.runLater(() -> progressDialog.setHeaderText(String.format("Running Imgnorm: (%dm %ds)", minutes, seconds)));
                        else
                            Platform.runLater(() -> progressDialog.setHeaderText(String.format("Running Imgnorm: (%dh %dm %ds)", hours, minutes, seconds)));
                    }
                }, 0, 1, TimeUnit.SECONDS);

                logger.info("Starting ImgNorm...");
                updateTaskProgress(0, 100);
                Project<BufferedImage> origProj = qupath.getProject();
                List<ImgFileData> origImgFiles = new ArrayList<>();

                updateMessage("Setting up directories...");
                File projectDir = Projects.getBaseDirectory(origProj);
                ImgNormDirectoryManager dirManager;
                try {
                    dirManager = new ImgNormDirectoryManager(projectDir);
                } catch(IOException e){ // This shouldn't happen
                    showErrorMessage("Error", "Failed to set up directories for image processing!");
                    return null;
                }

                // Create the list of working entries from the current project
                updateMessage("Gathering image entries...");
                List<ProjectImageEntry<BufferedImage>> origEntryList = origProj.getImageList();
                System.out.println("origEntryList: " + origEntryList);

                Platform.runLater(() -> updateTaskProgress(getProgress()*100 + 1, 100));
                checkAndHandleCancel();

                // Iterate over the entries, taking their ImageData and creating tiles/patches from them
                Set<File> duplicateTracker = new HashSet<>();
                for (ProjectImageEntry<BufferedImage> entry : origEntryList) {
                    updateMessage("Opening " + entry);
                    checkAndHandleCancel();

                    try {
                        Platform.runLater(() -> updateTaskProgress(getProgress()*100 + 25.0/origEntryList.size() /* increment = 25 */, 100));

                        Collection<URI> uris = entry.getURIs();
                        URI firstUri = uris.iterator().next();
                        if (uris.size() != 1 && !Files.exists(Paths.get(firstUri))) { // skip if URI is invalid
                            logger.warn("{} was skipped because URI is invalid", entry);
                            continue;
                        }

                        ImageData<BufferedImage> entryImageData = entry.readImageData(); // readImageData() can be a costly operation
                        if (entryImageData.getImageType() != ImageData.ImageType.BRIGHTFIELD_H_E) { // skip if not set to H&E
                            logger.warn("{} was skipped because image type is not set to Brightfield H&E", entry);
                            continue;
                        }

                        File entryImgFile = new File(firstUri);
                        ImgFileData imgFileData = new ImgFileData(entryImgFile, entryImageData.getHierarchy().getAnnotationObjects());
                        origImgFiles.add(imgFileData);

                        // Require all entries with Ignore annotations to have their images tiled even if they share the same image file
                        // But entries without Ignore annotations that share the same image file can skip tiling if tiling for one has been done already
                        if (!imgFileData.isHasMod()) {
                            if (duplicateTracker.contains(entryImgFile)) continue;
                            duplicateTracker.add(entryImgFile); // same as duplicateTracker.add(imgFileData.getImageFile())
                        }

                        System.gc();
                        updateMessage("Writing tiles for " + entry);
                        ImgNormImageTools.writeTiles(entryImageData, dirManager.getImgTempDir(), TILE_SIZE_PIXELS, imgFileData.getBaseName());

                    } catch (RuntimeException | IOException | InterruptedException | OutOfMemoryError e) {
                        logger.error(e.getMessage());
                        hasErrors = true;
                    }
                }

                System.out.println("origImgFiles: " + origImgFiles);

                // Take the resultant saved tiles, normalize them using Python, and stitch them back to their original dimensions
                checkAndHandleCancel();
                updateMessage("Initializing normalization algorithm...");
                ImgNormRunPython pythonRunner = new ImgNormRunPython(dirManager.getImgTempDir(), this, 40.0, false);
                // TODO: ^^^ Multiprocess or not?
                pythonRunner.runPython();

                checkAndHandleCancel();
                updateMessage("Stitching images...");
                List<File> patchDirectories = new ArrayList<>();
                Arrays.stream(dirManager.getImgTempDir().listFiles()).toList().forEach(file -> {
                    if (file.isDirectory()) { // <- this removes any invisible files (they usually aren't directories)
                        patchDirectories.add(file);
                    }
                });
                for (File patchDirectory : patchDirectories) {
                    updateMessage("Stitching for " + patchDirectory);
                    try {
                        System.gc();
                        ImgNormImageTools.stitchTiles(patchDirectory, dirManager.getImgFinalDir(), FINAL_IMAGE_SUFFIX,true);
                    } catch (RuntimeException | OutOfMemoryError e) {
                        if (e instanceof IndexOutOfBoundsException) {
                            logger.error("Processing failed for " + patchDirectory.getName() + ": " + e.getMessage() +
                                    "\nThis may be due to the names of the image files being too large.");
                        } else {
                            logger.error("Processing failed for " + patchDirectory.getName() + ": " + e.getMessage());
                        }

                        hasErrors = true;
                        continue;
                    }
                    Platform.runLater(() -> updateTaskProgress(getProgress()*100 + 32.0/(double)patchDirectories.size() /* increment = 32 */, 100));
                    checkAndHandleCancel();
                }

                updateMessage("Transferring to new project...");

                // Create a new project for the normalized images
                Platform.runLater(() -> {
                    // TODO: Find out why the line below needs to be run twice to work
                    qupath.setProject(Projects.createProject(dirManager.getNewProjDir(), BufferedImage.class));
                    qupath.setProject(Projects.createProject(dirManager.getNewProjDir(), BufferedImage.class));
                    Project<BufferedImage> normProj = qupath.getProject();

                    // Populate new project with the normalized images
                    origImgFiles.forEach(imgFileData -> {
                        try {
                            String origImgFileStrFinal =  imgFileData.getBaseName();
                            File normImgFile = null;

                            // Locate the corresponding normalized image via string matching
                            for (File file : Objects.requireNonNull(dirManager.getImgFinalDir().listFiles())){
                                String normImgStr = file.getName();
                                // Chop off extension
                                String normImgStrInt = normImgStr.substring(0, normImgStr.lastIndexOf("."));
                                // Chop off added suffix
                                String normImgStrFinal = normImgStrInt.substring(0, normImgStrInt.length() - FINAL_IMAGE_SUFFIX.length());

                                System.out.println("normImgStr: " + normImgStr);
                                System.out.println("normImgStrInt: " + normImgStrInt);
                                System.out.println("normImgStrFinal: " + normImgStrFinal + "\n");

                                if (origImgFileStrFinal.equals(normImgStrFinal)) {
                                    System.out.println("MATCH: " + normImgStrFinal);
                                    normImgFile = file;
                                    break;
                                }

                                System.out.println("The original " + origImgFileStrFinal + " is not equal to " + normImgStrFinal);
                            }

                            if (normImgFile != null) { // null shouldn't happen because the normalized images should all be located
                                var imageServer = ImageServers.buildServer(normImgFile.toURI());
                                ProjectImageEntry<BufferedImage> imageEntryNorm = normProj.addImage(imageServer.getBuilder());
                                imageEntryNorm.setImageName(normImgFile.getName());
                                var imageDataNorm = imageEntryNorm.readImageData();
                                // Set image to H&E
                                imageDataNorm.setImageType(ImageData.ImageType.BRIGHTFIELD_H_E);
                                // Transfer the annotations from the un-normalized image
                                imageDataNorm.getHierarchy().addObjects(imgFileData.getAnnotationsList());
                                // Set image to new stain vectors
                                imageDataNorm.setColorDeconvolutionStains(FINAL_STAINS);
                                // Save the entry
                                imageEntryNorm.saveImageData(imageDataNorm);
                            }

                        } catch (IOException e){
                            throw new RuntimeException(e);
                        }
                    });

                    // "Reload" the project to update GUI elements. // TODO: Find a better way of doing this
                    qupath.setProject(null);
                    qupath.setProject(normProj);
                });

                Platform.runLater(() -> updateTaskProgress(getProgress()*100 + 2, 100));
                checkAndHandleCancel();

                String time;
                long elapsedTime = (System.currentTimeMillis() - startTime) / 1000; // seconds
                long hours = elapsedTime / 3600;
                long minutes = (elapsedTime % 3600) / 60;
                long seconds = elapsedTime % 60;

                if (hours == 0 && minutes == 0)
                    time = String.format("Total processing time: %ds.", seconds);
                else if (hours == 0)
                    time = String.format("Total processing time: %dm %ds.", minutes, seconds);
                else
                    time = String.format("Total processing time: %dh %dm %ds.", hours, minutes, seconds);


                logger.info("ImgNorm Done! " + time);
                updateMessage("Done!");
                if (hasErrors)
                    showInfoMessage("ImgNorm Run Completed", "Run completed with errors. " + time + " See log for details.");
                else
                    showInfoMessage("ImgNorm Run Completed", "Run completed! " + time);

                ImgNormDirectoryManager.deleteDirectory(dirManager.getImgTempDir()); // in case previous attempts to delete didn't work

                return null;

            } catch (TaskCancelledException tce) {
                logger.warn(tce.getMessage());
                return null;
            } catch (Exception e) {
                logger.error(e.getMessage());
                showErrorMessage("Error", "Run aborted due to an unhandled error. See log for details.");
                return null;
            } finally {
                functionallyDone = true;
                scheduler.shutdown();
            }

        }


        private class ImgFileData {
            private final File imageFile;
            private final List<PathObject> annotationsList;
            private final boolean hasMod;
            private final String groupID;
            private static final AtomicInteger modInstancesMade = new AtomicInteger(0);

            public ImgFileData(File imageFile, Collection<PathObject> annotationsCollection) {
                this.imageFile = imageFile;

                // Convert collection to list
                List<PathObject> annotationsCollectionAsList = new ArrayList<>(annotationsCollection);
                // PathObjects include their child objects. Therefore, preprocess each annotation to remove underlying detection objects.
                annotationsCollectionAsList.replaceAll(annotation -> {
                    annotation.removeChildObjects(annotation.getChildObjects().stream()
                            .filter(PathObject::isDetection) // remove detection objects
                            .toList());
                    return annotation;
                });

                this.annotationsList = Collections.unmodifiableList(annotationsCollectionAsList);
                this.hasMod = annotationsList.stream()
                        .anyMatch(annotation -> annotation.getPathClass() == PathClass.fromString("Ignore*"));

                this.groupID = this.hasMod ? "_" + modInstancesMade.incrementAndGet() : "";
            }

            public File getImageFile() {
                return imageFile;
            }

            public List<PathObject> getAnnotationsList() {
                return annotationsList;
            }

            public boolean isHasMod() {
                return hasMod;
            }

            public String getBaseName() {
                String imageFileName = imageFile.getName();
                return imageFileName.substring(0, imageFileName.lastIndexOf(".")) + groupID;
            }

            public String getGroupID() {
                return groupID;
            }

            public static void resetModInstancesMade() {
                modInstancesMade.set(0);
            }

            @Override
            public String toString() {
                return imageFile.toString();
            }

        }


        public synchronized void updateTaskProgress(double workDone, double maxWork) {
            updateProgress(workDone, maxWork);
        }

        /**
         * Synchronized version of updateMessage.
         * @param message
         */
        public synchronized void updateTaskMessage(String message) {
            updateMessage(message);
        }

        /**
         * Synchronized version of getProgress.
         * @return the current progress
         */
        public synchronized double getTaskProgress() {
            return getProgress();
        }

        /**
         * Set the error status for the ImgNormRunner instance
         * @param errorStatus
         */
        public void setErrorStatus(boolean errorStatus) {
            hasErrors = errorStatus;
        }

        public void checkAndHandleCancel() throws TaskCancelledException {
            if (isQuietlyCancelled() || isCancelled()) {
                throw new TaskCancelledException("ImgNorm run cancelled by user.");
            }
        }

        class TaskCancelledException extends Exception {
            public TaskCancelledException(String errorMessage) {
                super(errorMessage);
            }
        }

        /*
        I can't find Dialogs.showInfoMessage() so might as well make the dialog components from scratch
         */
        private Label createContentLabel(String text) {
            var label = new Label(text);
            label.setMaxWidth(Double.MAX_VALUE);
            label.setMaxHeight(Double.MAX_VALUE);
            label.setMinSize(Label.USE_PREF_SIZE, Label.USE_PREF_SIZE);
            label.setWrapText(true);
            label.setPrefWidth(360);
            return label;
        }

        private void showErrorMessage(final String title, final String message) {
                showErrorMessage(title, createContentLabel(message));
        }

        private void showErrorMessage(final String title, final Node node) {
            functionallyDone = true;
            new Dialogs.Builder()
                    .alertType(Alert.AlertType.ERROR)
                    .title(title)
                    .content(node)
                    .showAndWait();
        }

        private void showInfoMessage(final String title, final String message) {
            showInfoMessage(title, createContentLabel(message));
        }

        private void showInfoMessage(final String title, final Node node) {
            functionallyDone = true;
            new Dialogs.Builder()
                    .alertType(Alert.AlertType.INFORMATION)
                    .title(title)
                    .content(node)
                    .showAndWait();
        }

    }

}
