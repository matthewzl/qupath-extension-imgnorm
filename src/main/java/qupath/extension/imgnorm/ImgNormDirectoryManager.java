package qupath.extension.imgnorm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: Consider a hierarchical structure where all other files are accessed through 'mainDir'
public class ImgNormDirectoryManager {

    private File mainDir;
    private File imgTempDir;
    private File newProjDir;
    private File imgFinalDir;
    final Logger logger = LoggerFactory.getLogger(ImgNormDirectoryManager.class);

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
