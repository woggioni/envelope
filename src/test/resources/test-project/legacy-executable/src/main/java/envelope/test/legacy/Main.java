package envelope.test.legacy;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
public class Main {
    public static void main(String[] args) throws Exception {
        new URL("https://www.google.com");
        System.out.println("Hello World!!");
        URL url = Main.class.getResource("someResource.xml");
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        try(InputStream is = url.openStream()) {
            builder.parse(is);
        }
    }
}