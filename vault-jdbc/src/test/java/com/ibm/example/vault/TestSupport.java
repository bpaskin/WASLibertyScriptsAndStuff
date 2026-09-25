package com.ibm.example.vault;

import com.ibm.json.java.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.file.Paths;

final class TestSupport {
    private TestSupport() {}
    static String driverClasspath(String jar) throws java.net.URISyntaxException {
        // Standalone test processes need the provided dependency from Maven's test classpath.
        return jar + File.pathSeparator
                + Paths.get(JSONObject.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
    static JSONObject map(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
