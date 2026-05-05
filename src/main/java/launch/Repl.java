package launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

public final class Repl {

    private Repl() { }

    public static void main(String[] args) throws Exception {
        int port = 0;
        String host = "127.0.0.1";
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--host".equals(args[i]) && i + 1 < args.length) {
                host = args[++i];
            }
        }

        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("nrepl.server"));
        require.invoke(Clojure.read("minicpbp.repl"));

        IFn startServer = Clojure.var("nrepl.server", "start-server");
        Object server = startServer.invoke(
                Clojure.read(":bind"), host,
                Clojure.read(":port"), port);

        IFn get = Clojure.var("clojure.core", "get");
        Object actualPort = get.invoke(server, Clojure.read(":port"));

        Path portFile = Paths.get(".nrepl-port");
        Files.write(portFile, actualPort.toString().getBytes());
        portFile.toFile().deleteOnExit();

        System.out.println("nREPL server started on " + host + ":" + actualPort);
        System.out.println("Editors that read .nrepl-port (Calva, CIDER, Cursive) will auto-connect.");
        System.out.println("Helpers are pre-loaded in the minicpbp.repl namespace.");
        System.out.println("Press Ctrl-C to stop.");

        Thread.currentThread().join();
    }
}
