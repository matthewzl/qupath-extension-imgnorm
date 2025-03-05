package qupath.extension.imgnorm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: Consider a hierarchical structure where all other files are accessed through 'mainDir'
public class ImgNormDirectoryManager {

    private File mainDir;
    private File imgTempDir;
    private File newProjDir;
    private File imgFinalDir;
    static final Logger logger = LoggerFactory.getLogger(ImgNormDirectoryManager.class);

    public ImgNormDirectoryManager(File projectDir) throws IOException {
        this.mainDir = createUniqueDirectory(projectDir.toString(), "normalized");
        this.imgTempDir =  createUniqueDirectory(this.mainDir.toString(), "img_temp");
        this.newProjDir =  createUniqueDirectory(this.mainDir.toString(), "QuPath_project");
        this.imgFinalDir = createUniqueDirectory(this.mainDir.toString(), "img_final");
    }


    /**
     * Makes a new directory inside a specified path.
     *
     * @param   parentDir
     *          the parent directory
     * @param   dirName
     *          name of the child directory
     *
     * @return  the resulting {@code Path}
     */
    public static File createUniqueDirectory(String parentDir, String dirName) throws IOException {
        Path path = Paths.get(parentDir, dirName);
        int counter = 0;

        while (Files.exists(path)) {
            counter++;
            path = Paths.get(parentDir, dirName + counter);
        }
        return Files.createDirectory(path).toFile();
    }

    /**
     * Delete a directory including its contents
     *
     * @param   dir
     *          the directory
     *
     * @return  the success of the operation
     */
    public static boolean deleteDirectory(File dir) {
        Path pathToBeDeleted = dir.toPath();
        AtomicBoolean success = new AtomicBoolean(true);
        logger.info("Deleting " + pathToBeDeleted + " and its contents...");

        try (Stream<Path> walk = Files.walk(pathToBeDeleted)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(file -> {
                        if (!file.delete()) {
                            success.set(false);
                            logger.warn("Failed to delete " + file.getAbsolutePath() + " in " + pathToBeDeleted);
                        }
                    });
        } catch (IOException e) {
            success.set(false);
            throw new RuntimeException(e.getMessage());
        }
        return success.get();
    }

    public File getMainDir() {
        return this.mainDir;
    }
    public File getImgTempDir() {
        return this.imgTempDir;
    }
    public File getNewProjDir() {
        return this.newProjDir;
    }
    public File getImgFinalDir() {
        return this.imgFinalDir;
    }

}
