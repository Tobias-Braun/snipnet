import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Started by smoke-test.sh limited to the modules of the jlink runtime that the installers bundle, with the app's own
 * jars on the classpath. It touches what the app needs at startup, using reflection so that compiling it only needs a JDK: the SQLite JDBC
 * driver (needs java.sql) and the bundled FFmpeg natives (need jdk.unsupported and friends). A module missing from
 * the runtime surfaces here as a NoClassDefFoundError or UnsatisfiedLinkError and the process exits non-zero.
 */
public class RuntimeProbe {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var rows = connection.createStatement().executeQuery("select sqlite_version()");
            rows.next();
            System.out.println("sqlite " + rows.getString(1));
        }

        // Same flavour selection as FfmpegRuntime: the build ships only the -gpl natives.
        System.setProperty("org.bytedeco.javacpp.platform.extension", "-gpl");
        Class<?> loader = Class.forName("org.bytedeco.javacpp.Loader");
        Class<?> avutil = Class.forName("org.bytedeco.ffmpeg.global.avutil");
        Object library = loader.getMethod("load", Class.class).invoke(null, avutil);
        System.out.println("ffmpeg natives " + library);
    }
}
