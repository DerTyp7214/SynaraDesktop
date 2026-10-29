import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class VerifyClasses {
    private static final List<String> PREFIXES = List.of("icons/", "com/composables/icons/");
    private static final int REPORTED = 20;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java VerifyClasses.java <lib/app directory>");
            System.exit(2);
        }
        File[] jars = new File(args[0]).listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            System.err.println("No jars in " + args[0]);
            System.exit(2);
        }
        Arrays.sort(jars, Comparator.comparing(File::getName));
        List<URL> urls = new ArrayList<>();
        for (File jar : jars) {
            urls.add(jar.toURI().toURL());
        }
        int checked = 0;
        int failed = 0;
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            for (File jar : jars) {
                try (JarFile file = new JarFile(jar)) {
                    for (JarEntry entry : file.stream().toList()) {
                        String name = entry.getName();
                        if (!name.endsWith(".class") || PREFIXES.stream().noneMatch(name::startsWith)) {
                            continue;
                        }
                        String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                        checked++;
                        try {
                            Class.forName(className, true, loader);
                        } catch (ClassNotFoundException | LinkageError e) {
                            failed++;
                            if (failed <= REPORTED) {
                                System.out.println(jar.getName() + ": " + className + ": " + e.getClass().getName() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
                            }
                        }
                    }
                }
            }
        }
        System.out.println(args[0] + ": checked " + checked + " classes, " + failed + " failed");
        if (checked == 0 || failed > 0) {
            System.exit(1);
        }
    }
}
