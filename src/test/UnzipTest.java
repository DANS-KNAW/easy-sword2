import nl.knaw.dans.api.sword2.Bagit;

import java.io.File;
import java.nio.file.Paths;

public class UnzipTest {
    public static void main(String[] args) throws Exception {
        String dirPath = "test-resources/zip-test";
        File[] files = new File(dirPath).listFiles();
        if (files == null) {
            throw new Exception("array, y u null??");
        }
        for (File file : files) {
            if (!file.isFile()) {
                throw new Exception("not a file??");
            }
            Bagit.extract(file, Paths.get(dirPath, "out").toString());
        }
    }
}
