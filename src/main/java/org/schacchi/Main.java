package org.schacchi;

import org.schacchi.server.AccountManager;
import org.schacchi.server.ConnectionHandler;
import org.schacchi.server.GameSession;
import org.schacchi.server.Server;
import org.schacchi.server.ServerListener;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Entry point del SERVER di scacchi. Solo terminale, zero Swing.
 *
 * <p>Sostituisce la vecchia dashboard Swing con cio' che serve davvero lato server:
 * log in streaming leggibili, stato dei client e delle partite, e una piccola
 * console di comandi quando lo stdin e' un terminale reale. Sotto systemd, Docker o
 * in una pipeline stdin non e' un terminale: li' il server resta un puro processo
 * con log puliti su stdout, che e' esattamente cio' che un service manager si aspetta.
 *
 * <pre>
 *   java -jar schacchi.jar [porta] [opzioni]
 *   java -jar schacchi.jar --port 8080 --max-clients 50 --no-console
 * </pre>
 */
public class Main {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Errore negli argomenti: " + e.getMessage());
            System.err.println("Usa --help per la lista delle opzioni.");
            System.exit(2);
            return;
        }

        if (options.help) {
            printUsage();
            return;
        }
        if (options.version) {
            System.out.println("ScacchiServer " + version() + " (Java " + System.getProperty("java.version") + ")");
            return;
        }

        int port = options.port != null ? options.port : envInt("PORT", Server.DEFAULT_PORT);

        // Colore solo se lo stdout e' un terminale e nessuno ha chiesto il contrario.
        // Senza questo i log di systemd mostrerebbero codici escape come testo.
        Console.setColorEnabled(detectColorSupport(options));
        // Deciso qui e non in startConsole: l'intestazione deve poter dire, prima di
        // partire, se la console sara' attiva o no.
        Console.interactive = options.interactiveConsole && System.console() != null;

        Console terminal = Console.create();
        // installInstance, non il semplice new: ConnectionHandler e SessionManager
        // raggiungono il server con Server.getInstance(). Senza installare l'istanza
        // creata qui, il networking lavorerebbe su un oggetto diverso da quello che
        // sta accettando connessioni, con due SessionManager separati.
        Server server = Server.installInstance(new Server(port, options.accountsFile, options.maxClients));
        // Il terminale stampa i log al proprio modo: l'eco su stdout li duplicherebbe.
        server.setEchoLogToStdout(false);
        server.addListener(new TerminalLogAdapter(terminal, server));

        Banner.print(terminal, server);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            terminal.println(Console.DIM + "Chiusura richiesta, arresto del server..." + Console.RESET);
            server.stop();
        }, "chess-shutdown"));

        if (options.interactiveConsole) {
            terminal.startConsole(server);
        } else {
            terminal.println(Console.GRAY + "Console interattiva disabilitata (--no-console): il server gira come processo." + Console.RESET);
        }

        try {
            server.start();   // bloccante: accetta connessioni finche' non viene fermato
        } catch (IOException e) {
            terminal.error("Impossibile mettersi in ascolto sulla porta " + server.getPort() + ": " + e.getMessage());
            System.exit(1);
        } finally {
            server.stop();
        }
    }

    private static String version() {
        String v = (Main.class.getPackage() == null) ? null : Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "1.0-SNAPSHOT";
    }

    private static int envInt(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean detectColorSupport(Options options) {
        if (!options.color) return false;
        if (System.getenv("NO_COLOR") != null) return false;
        if (options.interactiveConsole && System.console() == null) {
            // Senza terminale interattivo i log finiscono quasi sempre in un file o
            // in journald: il colore renderebbe il testo illeggibile.
            return false;
        }
        return !"0".equals(System.getenv("CHESS_COLOR"));
    }

    private static void printUsage() {
        System.out.printf(
                "ScacchiServer - server di scacchi online (solo terminale)%n%n"
                + "Uso:%n"
                + "  java -jar schacchi.jar [porta] [opzioni]%n%n"
                + "Opzioni:%n"
                + "  -p, --port <n>          Porta di ascolto (default: $PORT o %d)%n"
                + "      --max-clients <n>   Tetto di connessioni contemporanee (default: $MAX_CLIENTS o %d)%n"
                + "      --accounts <file>   File degli account (default: %s)%n"
                + "      --no-console        Solo log su stdout, senza console interattiva%n"
                + "      --no-color          Log senza codici colore ANSI%n"
                + "  -h, --help              Mostra questo messaggio%n"
                + "  -v, --version           Mostra la versione%n%n"
                + "Comandi della console interattiva:%n"
                + "  status      Stato di server, client e partite%n"
                + "  rooms       Elenco delle stanze%n"
                + "  clients     Client connessi%n"
                + "  help        Comandi disponibili%n"
                + "  clear       Pulisce il terminale%n"
                + "  stop        Arresta il server%n%n"
                + "Variabili d'ambiente: PORT, MAX_CLIENTS, SOCKET_TIMEOUT_MS, PBKDF2_ITERATIONS,%n"
                + "                     NO_COLOR, CHESS_COLOR%n",
                Server.DEFAULT_PORT, Server.DEFAULT_MAX_CLIENTS, AccountManager.DEFAULT_DATA_FILE);
    }

    /** Argomenti della riga di comando, gia' validati. */
    private static final class Options {
        Integer port;
        int maxClients;
        String accountsFile = AccountManager.DEFAULT_DATA_FILE;
        boolean interactiveConsole = true;
        boolean color = true;
        boolean help;
        boolean version;

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-h", "--help" -> o.help = true;
                    case "-v", "--version" -> o.version = true;
                    case "--no-console" -> o.interactiveConsole = false;
                    case "--no-color" -> o.color = false;
                    case "-p", "--port" -> o.port = requireInt(args, ++i, "--port");
                    case "--max-clients" -> o.maxClients = requireInt(args, ++i, "--max-clients");
                    case "--accounts" -> {
                        if (++i >= args.length) throw new IllegalArgumentException("--accounts richiede un percorso");
                        o.accountsFile = args[i];
                    }
                    default -> {
                        // Un numero nudo resta accettato: "java -jar schacchi.jar 8080"
                        // e' la forma piu' naturale e non deve fallire.
                        if (args[i].matches("\\d+")) {
                            o.port = Integer.parseInt(args[i]);
                        } else {
                            throw new IllegalArgumentException("opzione sconosciuta: " + args[i]);
                        }
                    }
                }
            }
            return o;
        }

        private static int requireInt(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException(option + " richiede un numero");
            try {
                return Integer.parseInt(args[index].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " richiede un numero, trovato: " + args[index]);
            }
        }
    }

    /** Intestazione mostrata all'avvio. */
    private static final class Banner {
        private static final int WIDTH = 54;

        static void print(Console terminal, Server server) {
            String line = "-".repeat(WIDTH);
            String box = Console.CYAN;
            terminal.println("");
            terminal.println(box + "  +" + line + "+" + Console.RESET);
            terminal.println(box + "  |" + Console.RESET
                    + pad("  " + Console.BOLD + "SCACCHI SERVER" + Console.RESET
                    + "   " + Console.DIM + "solo terminale" + Console.RESET)
                    + box + "|" + Console.RESET);
            terminal.println(box + "  |" + Console.RESET);
            row(terminal, "porta", String.valueOf(server.getPort()));
            row(terminal, "connessioni max", String.valueOf(server.getMaxClients()));
            row(terminal, "account", server.getAccountManager().getDataFile());
            row(terminal, "console", Console.interactive ? "attiva" : "disabilitata");
            terminal.println(box + "  +" + line + "+" + Console.RESET);
            terminal.println("");
        }

        private static void row(Console terminal, String name, String value) {
            terminal.println(Console.CYAN + "  |" + Console.RESET
                    + pad("   " + Console.GRAY + String.format("%-16s", name) + Console.RESET + value)
                    + Console.CYAN + "|" + Console.RESET);
        }

        /**
         * Allunga una riga fino alla larghezza interna della cornice. La lunghezza si
         * calcola sul testo nudo: i codici colore non occupano colonne.
         */
        private static String pad(String painted) {
            int visible = painted.replaceAll(Console.ANSI.pattern(), "").length();
            return painted + " ".repeat(Math.max(1, WIDTH - visible));
        }
    }

    /**
     * Terminale: stream colorato, log in streaming e console di comandi.
     *
     * <p>Log e prompt condividono lo stesso stream, quindi ogni riga di log che
     * arriva mentre l'utente sta scrivendo pulisce prima la riga corrente e la
     * ristampa dopo: senza questo, "status" finirebbe in mezzo a quello che
     * l'utente stava digitando.
     */
    private static final class Console {
        private static final String ESC = "\u001B";
        private static final String RESET = ESC + "[0m";
        private static final String BOLD = ESC + "[1m";
        private static final String DIM = ESC + "[2m";
        private static final String RED = ESC + "[38;5;203m";
        private static final String GREEN = ESC + "[38;5;114m";
        private static final String YELLOW = ESC + "[38;5;221m";
        private static final String BLUE = ESC + "[38;5;110m";
        private static final String CYAN = ESC + "[38;5;80m";
        private static final String GRAY = ESC + "[38;5;245m";
        private static final String MAGENTA = ESC + "[38;5;176m";

        /** Rimoove i codici ANSI quando l'output non e' un terminale. */
        static final Pattern ANSI = Pattern.compile(ESC + "\\[[0-9;]*[A-Za-z]");
        private static boolean colorEnabled = true;

        /** Vero solo se la console interattiva e' stata effettivamente avviata. */
        static boolean interactive;

        private final PrintStream out;
        private final PrintStream err;
        private boolean atPrompt;

        private Console(PrintStream out, PrintStream err) {
            this.out = out;
            this.err = err;
        }

        static void setColorEnabled(boolean enabled) {
            colorEnabled = enabled;
        }

        static Console create() {
            // PrintStream espliciti: System.out puo' essere reindirizzato altrove,
            // e il prompt deve finire sul terminale vero.
            return new Console(
                    new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8),
                    new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        }

        private String paint(String line) {
            return colorEnabled ? line : ANSI.matcher(line).replaceAll("");
        }

        synchronized void println(String line) {
            if (atPrompt) out.print(paint("\r" + ESC + "[K"));
            out.println(paint(line));
            out.flush();
            if (atPrompt) printPrompt();
        }

        synchronized void error(String message) {
            if (atPrompt) out.print(paint("\r" + ESC + "[K"));
            err.println(paint(RED + "x " + RESET + message));
            err.flush();
            if (atPrompt) printPrompt();
        }

        private void printPrompt() {
            out.print(paint(CYAN + "scacchi" + RESET + GRAY + " > " + RESET));
            out.flush();
        }

        /**
         * Legge i comandi da stdin. Se stdin non e' un terminale il thread termina
         * subito: il server non deve mai bloccare l'avvio aspettando input che non
         * arrivera' mai.
         */
        void startConsole(Server server) {
            if (System.console() == null) {
                println(GRAY + "stdin non e' un terminale: avvio senza console interattiva." + RESET);
                return;
            }
            interactive = true;
            Thread reader = new Thread(() -> readCommands(server), "chess-console");
            reader.setDaemon(true);
            reader.start();
        }

        /**
         * Aspetta che il server sia in ascolto prima di mostrare il prompt.
         *
         * <p>La console parte subito, ma {@code start()} e' bloccante e viene
         * chiamato solo dopo: senza questo attesa, il primo comando dell'utente
         * risponderebbe "fermo" su un server che sta per alzare.
         */
        private void awaitListening(Server server) {
            for (int i = 0; i < 100 && !server.isRunning() && !Thread.currentThread().isInterrupted(); i++) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void readCommands(Server server) {
            awaitListening(server);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                atPrompt = true;
                printPrompt();
                String line;
                while ((line = in.readLine()) != null) {
                    atPrompt = false;
                    String command = line.trim();
                    boolean keepGoing;
                    if (command.isEmpty()) {
                        keepGoing = true;
                    } else {
                        keepGoing = execute(server, command.toLowerCase(Locale.ROOT));
                    }
                    atPrompt = true;
                    if (keepGoing) {
                        printPrompt();
                    } else {
                        return;
                    }
                }
            } catch (IOException ignored) {
                // stdin chiuso (Ctrl+D o servizio senza input): il server continua.
            } finally {
                atPrompt = false;
            }
        }

        /** @return false se il server e' stato arrestato e la console deve chiudersi */
        private boolean execute(Server server, String command) {
            switch (command) {
                case "status" -> printStatus(server);
                case "rooms" -> printRooms(server);
                case "clients" -> printClients(server);
                case "clear" -> out.print(paint(ESC + "[2J" + ESC + "[H"));
                case "help", "?" -> printHelp();
                case "stop", "quit", "exit" -> {
                    server.stop();
                    return false;
                }
                default -> println(RED + "Comando sconosciuto: " + RESET + command + GRAY + "  (prova 'help')" + RESET);
            }
            return true;
        }

        private void printStatus(Server server) {
            SessionManagerStats stats = SessionManagerStats.of(server);
            String state = server.isRunning() ? GREEN + "● in ascolto" + RESET : RED + "● fermo" + RESET;

            println("");
            println("  " + BOLD + "Server" + RESET + "    " + state + GRAY + "   porta " + RESET + server.getPort());
            println("  " + BOLD + "Client" + RESET + "    " + stats.clients + "/" + server.getMaxClients() + " connessi");
            println("  " + BOLD + "Partite" + RESET + "   " + stats.active + " in corso" + GRAY + ", " + RESET + stats.waiting + GRAY + " in attesa" + RESET);
            println("  " + BOLD + "Stanze" + RESET + "   " + stats.total + " create in totale");
            println("");
        }

        private void printRooms(Server server) {
            List<GameSession> sessions = new ArrayList<>(server.getSessionManager().getAllSessions().values());
            if (sessions.isEmpty()) {
                println(GRAY + "  Nessuna stanza creata." + RESET);
                return;
            }
            sessions.sort(Comparator.comparing(GameSession::getSessionId));
            println("");
            println("  " + BOLD + String.format("%-14s%-24s%s", "ID", "NOME", "STATO") + Console.RESET);
            for (GameSession s : sessions) {
                String state = switch (s.getStatus()) {
                    case WAITING_FOR_OPPONENT -> YELLOW + "in attesa" + RESET;
                    case IN_PROGRESS -> GREEN + "in corso" + RESET;
                    case FINISHED -> GRAY + "finita" + RESET;
                };
                println("  " + String.format("%-14s%-24s", s.getSessionId(),
                        s.getRoomName() == null ? "-" : s.getRoomName()) + state);
            }
            println("");
        }

        private void printClients(Server server) {
            List<ConnectionHandler> clients = new ArrayList<>(server.getConnectedClients());
            if (clients.isEmpty()) {
                println(GRAY + "  Nessun client connesso." + RESET);
                return;
            }
            println("");
            for (ConnectionHandler c : clients) {
                String name = (c.getUsername() == null || c.getUsername().isBlank()) ? "(senza nome)" : c.getUsername();
                println("  " + GREEN + "●" + RESET + " " + name);
            }
            println("");
        }

        private void printHelp() {
            println("");
            println("  " + BOLD + "Comandi" + RESET);
            println("    " + CYAN + "status" + RESET + "   stato di server, client e partite");
            println("    " + CYAN + "rooms" + RESET + "    stanze aperte e in corso");
            println("    " + CYAN + "clients" + RESET + "  client connessi");
            println("    " + CYAN + "clear" + RESET + "    pulisce il terminale");
            println("    " + CYAN + "stop" + RESET + "     arresta il server");
            println("");
        }
    }

    /** Foto istantanea dei contatori, per non ripetere le chiamate in ogni riga. */
    private record SessionManagerStats(int clients, int active, int waiting, int total) {
        static SessionManagerStats of(Server server) {
            return new SessionManagerStats(
                    server.getConnectedClients().size(),
                    server.getSessionManager().getActiveSessionsCount(),
                    server.getSessionManager().getOpenSessions().size(),
                    server.getSessionManager().getAllSessions().size());
        }
    }

    /**
     * Adatta le callback di {@link Server} a righe di log colorate. Vive qui e non
     * dentro Server perche' il server non deve sapere nulla del terminale.
     */
    static final class TerminalLogAdapter implements ServerListener {
        private final Console console;
        private final Server server;

        TerminalLogAdapter(Console console, Server server) {
            this.console = console;
            this.server = server;
        }

        @Override
        public void onLog(String message) {
            console.println(Console.GRAY + TIME.format(LocalTime.now()) + Console.RESET
                    + "  " + level(message) + "  " + body(message));
        }

        @Override
        public void onClientConnected(ConnectionHandler client) {
            console.println(Console.GRAY + TIME.format(LocalTime.now()) + Console.RESET
                    + "  " + Console.GREEN + "NET " + Console.RESET
                    + "  " + Console.GREEN + "+" + Console.RESET + " client connesso"
                    + Console.GRAY + "  (connessi " + server.getConnectedClients().size() + ")" + Console.RESET);
        }

        @Override
        public void onClientDisconnected(ConnectionHandler client) {
            console.println(Console.GRAY + TIME.format(LocalTime.now()) + Console.RESET
                    + "  " + Console.GREEN + "NET " + Console.RESET
                    + "  " + Console.RED + "-" + Console.RESET + " client disconnesso"
                    + Console.GRAY + "  (connessi " + server.getConnectedClients().size() + ")" + Console.RESET);
        }

        /** Etichetta a 4 caratteri, cosi' i messaggi restano incolonnati. */
        private String level(String message) {
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.startsWith("errore") || lower.contains("impossibile") || lower.contains("rifiutat")) {
                return Console.RED + "ERR ";
            }
            if (message.startsWith("[Move]")) return Console.MAGENTA + "MOVE";
            if (message.startsWith("[Session]")) return Console.BLUE + "SESS";
            if (lower.startsWith("arresto") || lower.contains("conclusa")) return Console.YELLOW + "WARN";
            return Console.GREEN + "INFO";
        }

        /** Toglie il prefisso "[Session] ": l'etichetta SESS lo dice gia'. */
        private String body(String message) {
            return message.startsWith("[") ? message.substring(message.indexOf(']') + 1).trim() : message;
        }
    }
}
