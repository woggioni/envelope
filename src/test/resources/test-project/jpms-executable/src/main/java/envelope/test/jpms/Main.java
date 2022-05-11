package envelope.test.jpms;

import java.net.MalformedURLException;
import java.net.URL;
public class Main {
    public static void main(String[] args) throws MalformedURLException {
        new URL("https://www.google.com");
        System.out.println("Hello World!!");
    }
}