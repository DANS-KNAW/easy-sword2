import org.apache.commons.codec.binary.Base64;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;

public class DepositTest {

    private static final String filename = "example-bag.zip";
    private static final String filepath = "test-resources/" + filename;
    private static final String MD5 = "2d48ff55b2c745345db1a86694068b84"; // example-bag.zip
//    private static final String MD5 = "50de3630aae7cf09a24092f46ba817d6"; // example-bag2.zip

    public static void main(String[] args) throws Exception {
        System.out.println("Starting test...");

        File bagFile = new File(filepath);
        Response response = deposit(new FileInputStream(bagFile), "https://act.easy.dans.knaw.nl/sword2/collection", "elsevier", "elsevier");
        System.out.println("Response body:\n" + response.getXml());
    }

    public static Response deposit(InputStream fis, String theUrl, String username, String password) throws Exception {
        // Setup the http connection
        System.out.println("Setting up the HTTPS connection: " + theUrl);
        int bytesRead, bytesAvailable, bufferSize;
        byte[] buffer;
        int maxBufferSize = 1024 * 1024;
        URL url = new URL(theUrl);
        HttpsURLConnection conn = (HttpsURLConnection)url.openConnection();
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setUseCaches(false);

        // Set the authentication headers
        String encodedAuthorization = new String(Base64.encodeBase64((username + ":" + password).getBytes()));
        conn.setRequestProperty("Authorization", "Basic " + encodedAuthorization);

        // Set the http headers
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Connection", "Keep-Alive");
        conn.setRequestProperty("Content-Type", "application/zip");
        conn.setRequestProperty("Content-MD5", MD5);
        conn.setRequestProperty("Content-Disposition", "attachment; filename=" + filename);
        conn.setRequestProperty("X-Packaging", "http://easy.dans.knaw.nl/schemas/index.xml");

        // Send the file
        System.out.println("Sending file:");
        DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
        bytesAvailable = fis.available();
        bufferSize = Math.min(bytesAvailable, maxBufferSize);
        buffer = new byte[bufferSize];
        bytesRead = fis.read(buffer, 0, bufferSize);
        while (bytesRead > 0) {
            dos.write(buffer, 0, bufferSize);
            bytesAvailable = fis.available();
            bufferSize = Math.min(bytesAvailable, maxBufferSize);
            bytesRead = fis.read(buffer, 0, bufferSize);
        }

        // Get the response from the server
        System.out.println("Received response from server: " + conn.getResponseCode());
        final Integer serverResponseCode = conn.getResponseCode();
        fis.close();
        try {
            dos.flush();
            dos.close();
        } catch (Exception e) {
            // Ignore
        }
        InputStreamReader respis = new InputStreamReader(conn.getInputStream());
        final AtomicReference<String> xml = new AtomicReference<String>("");
        if (respis != null) {
            BufferedReader rd = new BufferedReader(respis);
            String line;
            String atom = "";
            while ((line = rd.readLine()) != null) {
                atom += line + "\n";
            }
            rd.close();

            xml.set(atom);
        }

        // Return the HTTP status code
        return new Response() {

            @Override
            public int getResponseCode() {
                return serverResponseCode;
            }

            @Override
            public String getXml() {
                return xml.get();
            }
        };
    }

    private interface Response {
        int getResponseCode();
        String getXml();
    }
}


