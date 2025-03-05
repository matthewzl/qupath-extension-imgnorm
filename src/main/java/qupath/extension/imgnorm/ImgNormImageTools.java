package qupath.extension.imgnorm;

import org.locationtech.jts.geom.Geometry;
import qupath.extension.imgnorm.utility.WKTLoader;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.writers.ImageWriterTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import qupath.lib.images.servers.SparseImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.ImageServers;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import qupath.lib.images.writers.ome.OMEPyramidWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImgNormImageTools {

    static final Logger logger = LoggerFactory.getLogger(ImgNormImageTools.class);
    private static final int MAX_REFERENCE_IMAGE_PIXELS = 7000*7000;
    private static final Geometry watermarkGeometry = WKTLoader.getGeometryFromResource("geometries/watermarks/excludedText.wkt");
    private static final Geometry watermarkBoundariesGeometry = WKTLoader.getGeometryFromResource("geometries/watermarks/excludedTextBoundaries.wkt");
    private enum Shading {
        BLACK, HATCHED, WATERMARKED
    }

    public static void writeTiles(ImageData<BufferedImage> imageData, File outputDir, int tileSizePx, String baseName) throws IOException, InterruptedException {
        // Get server associated with the ImageData
        var server = imageData.getServer();

        // Create tiles
        PathObject borderAnnotation = makeTileAnnotations(tileSizePx, imageData);
        Collection<PathObject> tiles = borderAnnotation.getChildObjects();

        File subDir = new File(outputDir.getAbsolutePath() + "/" + baseName);
        subDir.mkdirs();

        try {

            logger.info("Writing patches for " + baseName + " ...");

            ROI ignoreRoi = RoiTools.union(imageData.getHierarchy().getAnnotationObjects().stream()
                    .filter(annotation -> annotation.getPathClass() == PathClass.fromString("Ignore*"))
                    .map(PathObject::getROI)
                    .toList());

            if (ignoreRoi.getArea() > 0.0) {
                tiles/*.parallelStream()*/.forEach(tile -> { // TODO: parallelStream？
                    ROI tileRoi = tile.getROI();
                    RegionRequest region = RegionRequest.createInstance(server.getPath(), 1, tileRoi);
                    String outputPath = "[x-" + region.getMinX() + ",y-" + region.getMinY() + ",w-" + region.getWidth() + ",h-" + region.getHeight() + "]";
                    File file = new File(subDir, outputPath + ".tif");
                    try {
                        BufferedImage imgMasked = createMaskedBufferedImageFromRoi(server, tileRoi, ignoreRoi, 1, Shading.WATERMARKED);
                        ImageWriterTools.writeImage(imgMasked, file.toString()); // checked exception...
                    } catch (IOException e){
                        throw new RuntimeException("Error making tiles for " + imageData + " (" + e + ")");
                    }
                });
            } else {
                tiles/*.parallelStream()*/.forEach(tile -> { // TODO: parallelStream？
                    ROI tileRoi = tile.getROI();
                    RegionRequest region = RegionRequest.createInstance(server.getPath(), 1, tileRoi);
                    String outputPath = "[x-" + region.getMinX() + ",y-" + region.getMinY() + ",w-" + region.getWidth() + ",h-" + region.getHeight() + "]";
                    File file = new File(subDir, outputPath + ".tif");
                    try {
                        ImageWriterTools.writeImageRegion(server, region, file.toString()); // checked exception...
                    } catch (IOException e){
                        throw new RuntimeException("Error making tiles for " + imageData + " (" + e + ")");
                    }
                });
            }

            System.gc();

            // Generate a downsampled "reference" image for its stain vectors to be estimated later
            logger.info("Generating reference image...");
            ROI refRoi = borderAnnotation.getROI();
            double downsample = Math.max(1, Math.sqrt(refRoi.getArea()/MAX_REFERENCE_IMAGE_PIXELS));
            File refFile = new File(subDir, "reference.tif");
            if (ignoreRoi.getArea() > 0.0) {
                BufferedImage refImgMasked = createMaskedBufferedImageFromRoi(server, refRoi, ignoreRoi, downsample, Shading.BLACK);
                ImageWriterTools.writeImage(refImgMasked, refFile.toString());
            } else {
                RegionRequest refRegion = RegionRequest.createInstance(server.getPath(), downsample, refRoi);
                ImageWriterTools.writeImageRegion(server, refRegion, refFile.toString());
            }

            // Get pixel metadata
            double pixelHeight = (double)server.getPixelCalibration().getPixelHeight();
            double pixelWidth = (double)server.getPixelCalibration().getPixelWidth();
            double zSpacing = (double)server.getPixelCalibration().getZSpacing();
            double[] downsamples = server.getMetadata().getPreferredDownsamplesArray();

            // Write the metadata into the folder containing the pixel & downsample info
            File metadataFile = new File(subDir, "metadata.txt");
            try (FileWriter metadataWriter = new FileWriter(metadataFile)) {
                metadataWriter.write("[pH-" + pixelHeight + ",pW-" + pixelWidth + ",zSp-" + zSpacing + ",dsp-" + Arrays.toString(downsamples) + "]");
            } catch (IOException e) {
                throw new RuntimeException("Error writing metadata for " + imageData + " (" + e + ")");
            }

        } catch (Exception | OutOfMemoryError e) {
            ImgNormDirectoryManager.deleteDirectory(subDir);
            throw new RuntimeException("Files for " + baseName + " were removed because an error occurred: " + e);
        }

    }

    /**
     * Overlay square tile annotations on an image.
     *
     * @param tileSizePixels the length of the square tile in pixels
     * @param imageData the image data
     * @return the annotation forming the border of the image containing the tile annotations as its child objects
     */
    public static PathObject makeTileAnnotations(int tileSizePixels, ImageData<BufferedImage> imageData) {

        var server = imageData.getServer();
        var plane = ImagePlane.getPlane(0, 0);

        int imageWidth = server.getWidth();
        int imageHeight = server.getHeight();

        int calculatedTileCount = (int)(Math.ceil((double)imageHeight/tileSizePixels)*Math.ceil((double)imageWidth/tileSizePixels));
        ArrayList<PathObject> tileAnnotations = new ArrayList<>(calculatedTileCount);

        for (int i = 0; i < imageHeight; i += tileSizePixels) { // Not <= or you will get zero-area ROIs

            for (int j = 0; j < imageWidth; j += tileSizePixels) { // Not <= or you will get zero-area ROIs

                int roiWidth = tileSizePixels;
                int roiHeight = tileSizePixels;

                if (i + tileSizePixels > imageHeight)
                    roiHeight = tileSizePixels - (i + tileSizePixels - imageHeight);

                if (j + tileSizePixels > imageWidth)
                    roiWidth = tileSizePixels - (j + tileSizePixels - imageWidth);

                var roi = ROIs.createRectangleROI(j, i, roiWidth, roiHeight, plane);

                tileAnnotations.add(PathObjects.createAnnotationObject(roi));

            }

        }

        ROI roi = ROIs.createRectangleROI(0, 0, server.getWidth(),server.getHeight(), plane);
        PathObject borderAnnotation = PathObjects.createAnnotationObject(roi);
        borderAnnotation.addChildObjects(tileAnnotations);

        imageData.getHierarchy().addObject(borderAnnotation);
        borderAnnotation.setLocked(true);
        tileAnnotations.forEach(tile -> tile.setLocked(true));

        return borderAnnotation;

    }


    /**
     * Get a BufferedImage within a specified ROI masked by the shape of
     * another ROI.
     *
     * @param server the image server
     * @param mainRoi the ROI used to get the BufferedImage within
     * @param maskRoi the ROI used to create the masking
     * @param downsample downsample
     * @param shading shading of the mask
     * @return the BufferedImage
     * @throws IOException
     */
    public static BufferedImage createMaskedBufferedImageFromRoi(
            ImageServer<BufferedImage> server,
            ROI mainRoi,
            ROI maskRoi, // ignore roi
            double downsample,
            Shading shading) throws IOException {

        RegionRequest request = RegionRequest.createInstance(server.getPath(), downsample, mainRoi);
        BufferedImage img = server.readRegion(request);

        ROI maskROIMainIntersection = RoiTools.intersection(maskRoi, mainRoi)
                .translate(-mainRoi.getBoundsX(), -mainRoi.getBoundsY())
                .scale(1/downsample, 1/downsample);

        if (maskROIMainIntersection.getArea() == 0) return img;

        switch(shading) {
            case BLACK -> {
                Shape maskShape = RoiTools.getShape(maskROIMainIntersection);
                Graphics2D g2d = img.createGraphics();
                g2d.setColor(Color.BLACK);
                g2d.fill(maskShape);
                g2d.dispose();
            }
            case HATCHED -> { // this is much slower
                Shape maskShape = RoiTools.getShape(maskROIMainIntersection);
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        if (x % 2 == 0 && y % 2 == 0 /* adjust numbers to change hatching pattern */) continue;
                        if (!maskShape.contains(x, y)) continue;

                        img.setRGB(x, y, (int) 0xFF000000);
                    }
                }
            }
            case WATERMARKED -> { // Designed so that passing in tiles of a larger image won't cause watermark misalignment.
                ROI maskRoiPre = maskRoi.translate(-mainRoi.getBoundsX(), -mainRoi.getBoundsY())
                        .scale(1/downsample, 1/downsample);

//                Geometry watermarkGeometry = WKTLoader.getGeometryFromResource("geometries/watermarks/excludedText.wkt");
//                Geometry watermarkBoundariesGeometry = WKTLoader.getGeometryFromResource("geometries/watermarks/excludedTextBoundaries.wkt");

                ROI watermarkRoi = GeometryTools.geometryToROI(watermarkGeometry, ImagePlane.getPlane(0, 0))
                        .scale(1/downsample, 1/downsample);
                ROI watermarkBoundariesRoi = GeometryTools.geometryToROI(watermarkBoundariesGeometry, ImagePlane.getPlane(0, 0))
                        .scale(1/downsample, 1/downsample);

                List<ROI> watermarkRoiList = new ArrayList<>();
                double watermarkRoiWidth = (watermarkRoi.getBoundsWidth() + watermarkRoi.getBoundsWidth()/10);
                double watermarkRoiHeight = (watermarkRoi.getBoundsHeight() + watermarkRoi.getBoundsWidth()/10);

//                double maskBoundsX = maskRoiPre.getBoundsX();
//                double maskBoundsY = maskRoiPre.getBoundsY();
//                double maskBoundsWidth = maskRoiPre.getBoundsWidth() + 1; // +1 buffer
//                double maskBoundsHeight = maskRoiPre.getBoundsHeight() + 1; // +1 buffer

                double startX = maskRoiPre.getBoundsX() >= mainRoi.getBoundsX()
                        ? maskRoiPre.getBoundsX()
                        : maskRoiPre.getBoundsX() + Math.floor(Math.abs(maskRoiPre.getBoundsX())/watermarkRoiWidth) * watermarkRoiWidth;
                double startY = maskRoiPre.getBoundsY() >= mainRoi.getBoundsY()
                        ? maskRoiPre.getBoundsY()
                        : maskRoiPre.getBoundsY() + Math.floor(Math.abs(maskRoiPre.getBoundsY())/watermarkRoiHeight) * watermarkRoiHeight;
                double endX = Math.min((maskRoiPre.getBoundsX() + maskRoiPre.getBoundsWidth()), (mainRoi.getBoundsX() + mainRoi.getBoundsWidth()));
                double endY = Math.min((maskRoiPre.getBoundsY() + maskRoiPre.getBoundsHeight()), (mainRoi.getBoundsY() + mainRoi.getBoundsHeight()));
                
                for (double x = startX; x < endX; x += watermarkRoiWidth) {
                    for (double y = startY; y < endY; y += watermarkRoiHeight) {
                        if (RoiTools.intersection(watermarkBoundariesRoi.translate(x, y), maskROIMainIntersection).getArea() == 0) continue;
                        ROI excludedRoiTranslated = watermarkRoi.translate(x, y);
                        watermarkRoiList.add(excludedRoiTranslated);
                    }
                }

                ROI maskRoiFinal = RoiTools.subtract(maskRoiPre, watermarkRoiList);
                Shape maskShape = RoiTools.getShape(maskRoiFinal);

                Graphics2D g2d = img.createGraphics();
                g2d.setColor(Color.BLACK);
                g2d.fill(maskShape);
                g2d.dispose();
            }
        }

        return img;
    }

    /**
     * Stitch the patches and save as an ome.tiff.
     *
     * @param patchDirectory the directory containing the patches
     * @param outputDir the output directory to write the stitched image
     * @param finalImageSuffix suffix to append to the stitched image name
     * @param deleteOriginalTiles whether to delete the entire directory containing the tiles
     * @throws OutOfMemoryError
     */
    public static void stitchTiles(File patchDirectory, File outputDir, String finalImageSuffix, boolean deleteOriginalTiles)
            throws OutOfMemoryError {

        try {
            if (new File(patchDirectory, "ERROR.txt").exists()) {
                throw new RuntimeException("Stitching process for " + patchDirectory + " was skipped because " +
                        "its patches failed to normalize properly.");
            } else if (!new File(patchDirectory, "metadata.txt").exists()) { // <- this should NOT happen
                throw new RuntimeException("Stitching process for " + patchDirectory + " was skipped because " +
                        "its metadata is missing.");
            }

            logger.info("Stitching patches in " + patchDirectory.getName());

            String metadataString = Files.readString(new File(patchDirectory, "metadata.txt").toPath());
            double[] pixelMetadata = getPixelMetadata(metadataString);
            double[] preferredDownsamples = getDownsampleMetadata(metadataString);

            List<File> patchFiles = Arrays.stream(patchDirectory.listFiles())
                    .filter(file -> (file.getName().endsWith(".tif") || file.getName().endsWith(".tiff")))
                    .filter(file -> !file.getName().equals("reference.tif") && !file.getName().equals("reference.tiff"))
                    .toList();

            var builder = new SparseImageServer.Builder();

            patchFiles.parallelStream().forEach(patchFile -> {
                try {
                    String patchFilePath = patchFile.getAbsolutePath();
                    String patchFileName = patchFile.getName();

                    ImageServer<BufferedImage> server = ImageServerProvider.buildServer(patchFilePath, BufferedImage.class);
                    RegionRequest request = RegionRequest.createInstance(server);
                    BufferedImage img = server.readRegion(request);

                    int xPos = extractPosition(patchFileName, "x-(\\d+)");
                    int yPos = extractPosition(patchFileName, "y-(\\d+)");
                    int height = img.getHeight();
                    int width = img.getWidth();
                    var region = ImageRegion.createInstance(xPos, yPos, width, height, 0, 0);
                    var serverBuilder = ImageServerProvider.getPreferredUriImageSupport(BufferedImage.class, patchFile.toURI().toString()).getBuilders().get(0);
                    builder.jsonRegion(region, 1.0, serverBuilder);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            SparseImageServer serverPre = builder.build();
            ImageServer<BufferedImage> serverMain = ImageServers.pyramidalize(serverPre);

            QP.setPixelSizeMicrons(new ImageData<>(serverMain), pixelMetadata[0], pixelMetadata[1], pixelMetadata[2]);

            // Generate output file path
            String outputFileName = patchDirectory.getName() + finalImageSuffix + ".tiff";
            String outputPath = new File(outputDir, outputFileName).getAbsolutePath();

            new OMEPyramidWriter.Builder(serverMain)
                    .downsamples(preferredDownsamples) // Use pyramid levels calculated in the ImageServers.pyramidalize(server) method
                    .tileSize(512)      // Requested tile size
                    .channelsInterleaved()      // Because SparseImageServer returns all channels in a BufferedImage, it's more efficient to write them interleaved
//                    .parallelize()   // TODO: Parallelize or not?
                    .losslessCompression()      // Use lossless compression (often best for fluorescence, by lossy compression may be ok for brightfield)
                    .build()
                    .writeSeries(outputPath);

            serverMain.close();

            logger.info("Successfully stitched patches in " + patchDirectory.getName() + "!");

        } catch (Exception e){
            throw new RuntimeException(e.getMessage());
        } catch (OutOfMemoryError ome){
            System.gc();
            throw new OutOfMemoryError(ome.getMessage());
        } finally {
            // TODO: How to guarantee file deletion (issue on Windows)?
            if (deleteOriginalTiles) {
                ImgNormDirectoryManager.deleteDirectory(patchDirectory); // Not strictly needed, but eases the storage load on the computer
            }
        }

    }

    /**
     * Get the annotation objects from a project entry.
     * @param entry the project entry
     * @return the annotation objects
     * @throws IOException
     */
    @Deprecated
    public static Collection<PathObject> getAnnotationObjects(ProjectImageEntry<BufferedImage> entry) throws IOException {
        return entry.readImageData().getHierarchy().getAnnotationObjects();
    }

    public static double[] trimArray(double[] originalArray) { // Truncate to first 4 elements
        if (originalArray.length >= 5) {
            return new double[]{originalArray[0], originalArray[1], originalArray[2], originalArray[3]};
        }
        return originalArray;
    }

    private static String getImageBaseName() {
        var imageData = QP.getCurrentImageData();
        String name = GeneralTools.getNameWithoutExtension(imageData.getServer().getMetadata().getName());
        String pathOutput = QP.buildFilePath(QP.PROJECT_BASE_DIR, "tiles", name);
        File imageFile = new File(pathOutput);

        return imageFile.getName();
    }

    private static double[] getPixelMetadata(String patchFileName) {
        double pixelHeight = extractValue(patchFileName, "pH-([\\d.]+)");
        double pixelWidth = extractValue(patchFileName, "pW-([\\d.]+)");
        double zSpacing = extractValue(patchFileName, "zSp-([\\d.]+)");

        return new double[]{pixelHeight, pixelWidth, zSpacing};
    }

    private static double[] getDownsampleMetadata(String patchFileName) {
        // Extract the downsample metadata
        String dspPattern = "dsp-\\[(.*?)\\]";
        Pattern pattern = Pattern.compile(dspPattern);
        Matcher matcher = pattern.matcher(patchFileName);

        if (!matcher.find()) {
            return new double[] {1.0};
        }

        String dspData = matcher.group(1); // Extract the content within dsp-[...]

        // Split the extracted string by commas and parse each part to a double
        String[] dspStrings = dspData.split(",");
        double[] dspValues = new double[dspStrings.length];
        for (int i = 0; i < dspStrings.length; i++) {
            try {
                dspValues[i] = Double.parseDouble(dspStrings[i].trim());
            } catch (NumberFormatException e) {
                logger.error("Failed to parse downsample value: " + dspStrings[i]);
                dspValues[i] = 1.0;
            }
        }

        return dspValues;
    }

    private static double extractValue(String text, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        } else {
            throw new IllegalArgumentException("Pattern not found: " + regex);
        }
    }

    private static int extractPosition(String fileName, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(fileName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        } else {
            throw new IllegalArgumentException("Pattern not found: " + regex);
        }
    }

}
