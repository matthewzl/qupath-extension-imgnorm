package qupath.extension.imgnorm.utility;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

public class WKTLoader {
    private static String readResourceFile(String resourcePath) throws IOException {
        InputStream inputStream = WKTLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) throw new FileNotFoundException("File not found: " + resourcePath);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString().trim();
        }
    }

    public static Geometry getGeometryFromResource(String resourcePath) {
        try {
            String wktString = readResourceFile(resourcePath);
            WKTReader wktReader = new WKTReader();
            return wktReader.read(wktString);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
