package qupath.extension.imgnorm;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ImgNormRunPython {

    static final Logger logger = LoggerFactory.getLogger(ImgNormRunPython.class);
    private Integer inputDirSize;
    private String lastProcessOutputLine = null;
    private final File inputDir;
    private final ImgNormRunner.ImgNormTask task;
    private final Double progressToAdd;
    private final Integer PREFERRED_NO_PROCESSORS;


    /**
     * @param inputDir directory containing directories of .tif/.tiff patches
     * @param task the ImgNormTask being run
     * @param progressToAdd how much progress to add to the task
     * @param tryMultiprocessing request if multiprocessing (parallelization) should be done
     */
    public ImgNormRunPython(File inputDir, ImgNormRunner.ImgNormTask task, Double progressToAdd, boolean tryMultiprocessing) {
        this.inputDir = inputDir;
        this.task = task;
        this.progressToAdd = progressToAdd;

        if (tryMultiprocessing)
            this.PREFERRED_NO_PROCESSORS = processorsToUse();
        else
            this.PREFERRED_NO_PROCESSORS = 1;
    }

    /**
     * @param inputDir directory containing directories of .tif/.tiff patches
     * @param task the ImgNormTask being run
     * @param progressToAdd how much progress to add to the task
     * @param processorsToUse the number of processors (i.e., instances of the Python
     *                        executable for parallelization) to use
     */
    public ImgNormRunPython(File inputDir, ImgNormRunner.ImgNormTask task, Double progressToAdd, int processorsToUse) {
        this.inputDir = inputDir;
        this.task = task;
        this.progressToAdd = progressToAdd;
        this.PREFERRED_NO_PROCESSORS = processorsToUse;
    }

    /**
     * Load and run the Python executable.
     */
    public void runPython() {
        String inputDirStr = inputDir.toString();
        List<String> inputDirContentsStr = Arrays.stream(Objects.requireNonNull(new File(inputDirStr).listFiles(),
                "Directory not found: " + inputDirStr)) // <- this should not happen...
                .filter(file -> !file.isHidden()) // <- remove invisible files
                .map(File::toString)
                .toList();

        inputDirSize = inputDirContentsStr.size();

        logger.info("Initializing Python...");
        File tempExecutable = null;

        ExecutorService pool = Executors.newCachedThreadPool();

        try {
            // Extract the executable from the JAR to a temporary file
            InputStream executableStream = null;
            if (System.getProperty("os.name").toLowerCase().contains("windows")){
                executableStream = ImgNormRunPython.class.getClassLoader().getResourceAsStream("python/imgNorm/dist/main.exe");
                tempExecutable = File.createTempFile("python/main.exe", "");
            } else if (System.getProperty("os.name").toLowerCase().contains("mac")){
                executableStream = ImgNormRunPython.class.getClassLoader().getResourceAsStream("python/imgNorm/dist/main");
                tempExecutable = File.createTempFile("python/main", "");
            }

            if (executableStream == null) {
                throw new FileNotFoundException("Executable resource not found.");
            }
            Files.copy(executableStream, tempExecutable.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tempExecutable.setExecutable(true);

            logger.info("Extracted Python executable to: " + tempExecutable.getAbsolutePath());

            List<List<String>> partitionedInputDirContentsStrList = getPartitionedList(inputDirContentsStr, PREFERRED_NO_PROCESSORS);
            List<Process> processList = new ArrayList<>();
            int count = 1;
            for (List<String> partitionedInputDirContentsStr : partitionedInputDirContentsStrList) {

                String jsonArg = new Gson().toJson(partitionedInputDirContentsStr); // make a JSON representation of the list to give to Python
                String encodedJsonArg = Base64.getEncoder().encodeToString(jsonArg.getBytes()); // encode the JSON string, as Windows path strings use "\\" interpreted as escape characters
                logger.info("JSON Argument: " + jsonArg + "\n Encoded JSON Argument: " + encodedJsonArg); // debugging only...
                ProcessBuilder pb = new ProcessBuilder(tempExecutable.getAbsolutePath(), encodedJsonArg); // <- COMMAND LINE ARGUMENTS FOR PYTHON EXECUTABLE
                pb.redirectErrorStream(true); // Combine stdout and stderr
                Process process = pb.start();
                logger.info("Python process: " + process);

                processList.add(process);
                int finalCount = count;
                CompletableFuture.runAsync(() -> readProcessOutput(process.getInputStream(), task, process, finalCount, partitionedInputDirContentsStrList.size()), pool);

                count++;
            }

            for (Process process : processList) {
                int exitValue = process.waitFor();
                if (exitValue == 1) throw new RuntimeException("Python script finished with exit code: " + exitValue);
            }

        } catch (IOException | InterruptedException e) {
            logger.error("Failed to run Python script.", e);
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
            if (tempExecutable != null && tempExecutable.exists()) {
                boolean deleted = tempExecutable.delete();
                if (deleted) {
                    logger.info("Temporary Python executable deleted successfully.");
                } else {
                    logger.warn("Failed to delete temporary Python executable.");
                }
            }
        }
    }

    private void readProcessOutput(InputStream inputStream, ImgNormRunner.ImgNormTask task, Process process, int processNumber, int totalProcesses) {
        new BufferedReader(new InputStreamReader(inputStream)).lines().forEach(line -> {

            if (line.equals("Next")) {
                updateTaskProgressByProcess(task);
            }

            if (line.contains("Failed to normalize")) {
                logger.error(line);
                task.setErrorStatus(true);
            }

            if (task.isQuietlyCancelled() || task.isCancelled()) {
                // TODO: See if Platform.runLater() is necessary
                Platform.runLater(() -> {
                    logger.info("Terminating Python script for cancellation...");
                    process.destroy();
                });
                return;
            }

            logger.info(line);
            updateTaskMessageByProcess(task, line, processNumber, totalProcesses);

        });

        updateTaskMessageByProcess(task, "[Done]", processNumber, totalProcesses);
    }

    private synchronized void updateTaskProgressByProcess(ImgNormRunner.ImgNormTask task) {
        Platform.runLater(() -> task.updateTaskProgress(task.getProgress()*100 + progressToAdd/(double)inputDirSize, 100));
    }

    private synchronized void updateTaskMessageByProcess(ImgNormRunner.ImgNormTask task, String line, int processNumber, int totalProcesses) {
        if (totalProcesses == 1) { // if configured to 1 process, then no need to indicate the number of processes used
            task.updateTaskMessage(line);
            return;
        }

        if (lastProcessOutputLine == null) {
            lastProcessOutputLine = createLineBreaks("[Retrieving processor updates...]",totalProcesses + 2); // ensure we have 2 extra lines for a header and its line break
            lastProcessOutputLine = replaceNthLine(lastProcessOutputLine, 0, "Normalizing images (No. processes: " + totalProcesses + ")");
            lastProcessOutputLine = replaceNthLine(lastProcessOutputLine, 1, " ");
        }

        lastProcessOutputLine = replaceNthLine(lastProcessOutputLine, processNumber + 1 /* +1 to make space b/w header */, "Process #" + processNumber + ": " + line);
        task.updateTaskMessage(lastProcessOutputLine);
    }

    /**
     * Get the number of Python executables to spawn for multiprocessing
     * (based on a fraction of the machine's core count). Note that this
     * returns 1 for macOS due to poor multiprocessing benchmarks observed
     * during testing.
     *
     * @return the number of Python executables to spawn
     */
    private int processorsToUse() { // limit to a 1/2 of the cores for now
        if (System.getProperty("os.name").toLowerCase().contains("mac"))
            return 1;

        double fractionOfCoresToUse = (double) 1/2;
        int processorCount = Runtime.getRuntime().availableProcessors();
        return (int)Math.ceil((double)processorCount*fractionOfCoresToUse);
    }


    /**
     * Get a list containing partitions of the input list.
     *
     * @param inputList the input list
     * @param preferredSplitCount preferred number of partitions.
     * @return list containing partitions of the input list
     */
    private static <T> List<List<T>> getPartitionedList(List <T> inputList, int preferredSplitCount) {

        List<T> mutableList = new ArrayList<>(inputList);

        int remainder = (preferredSplitCount < mutableList.size()) ? mutableList.size() % preferredSplitCount : 0;
        int partitionSize = (preferredSplitCount < mutableList.size()) ? (mutableList.size() - remainder)/preferredSplitCount : 1; // floored b/c cast to int

        List<T> remainderEntry = new ArrayList<>();
        for (int i = 0; i < remainder; i++) {
            remainderEntry.add(mutableList.remove(0));
        }

        List<List<T>> listPartitions = new ArrayList<>();

        while (!mutableList.isEmpty()) {
            List<T> nextEntry = new ArrayList<>();
            for (int j = 0; j < partitionSize; j++) {
                T element = mutableList.remove(0);
                nextEntry.add(element);
            }
            listPartitions.add(nextEntry);
        }

        if (!remainderEntry.isEmpty() && !listPartitions.isEmpty()) { // Distribute the remainder
            for (List<T> partition : listPartitions) {
                partition.add(remainderEntry.remove(0));
                if (remainderEntry.isEmpty())
                    break;
            }
        }

        return listPartitions;
    }

    private static String replaceNthLine(String input, int lineToReplace, String newLineContent) {
        String[] lines = input.split("\n");

        if (lineToReplace < 0 || lineToReplace >= lines.length) {
            throw new IllegalArgumentException("Line number out of bounds");
        }

        lines[lineToReplace] = newLineContent;

        return String.join("\n", lines);
    }

    private static String createLineBreaks(String optionalPrefix, int n) {
        return (optionalPrefix + " \n").repeat(n);
    }


}
