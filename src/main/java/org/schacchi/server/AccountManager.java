package org.schacchi.server;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestione degli account utente, autenticazione, statistiche e lista amici.
 * I dati sono salvati su file locale (percorso configurabile) per mantenere la
 * persistenza tra riavvii.
 *
 * <h2>Formato del file di persistenza</h2>
 * Una riga per account, campi separati da ':':
 * <pre>username:salt:iterazioni:hash:vittorie:sconfitte:patte:elo:amici,separati,da,virgola</pre>
 *
 * <h2>Password</h2>
 * Le password non vengono mai scritte in chiaro. Si conserva un sale casuale per
 * utente e una traccia derivata con PBKDF2-HMAC-SHA256, una funzione di derivazione
 * costruita apposta per le password e dotata di fattore di lavoro: un SHA-256 singolo
 * si rompe a velocità GPU, mentre PBKDF2 con centinaia di migliaia di iterazioni
 * rende il costo di ogni tentativo alto anche per chi ha il file.
 * Il confronto avviene in tempo costante per non rivelare informazioni tramite tempi
 * di risposta. Richiesta dall'art. 32 GDPR (misure tecniche adeguate).
 *
 * <h2>Diritti dell'interessato</h2>
 * {@link #deleteAccount} implementa il diritto di cancellazione (art. 17) e rimuove
 * anche i riferimenti residui nelle liste amici degli altri, che altrimenti
 * continuerebbero a contenere il nome dell'utente cancellato.
 * {@link #exportAccount} implementa accesso e portabilità (art. 15 e 20).
 */
public class AccountManager {
    /** Percorso predefinito del file di persistenza, risolto rispetto alla directory di lavoro. */
    public static final String DEFAULT_DATA_FILE = "accounts.txt";

    private static final int SALT_BYTES = 16;
    private static final int MIN_CREDENTIAL_LENGTH = 8;
    private static final int MIN_USERNAME_LENGTH = 3;
    private static final int HASH_BITS = 256;

    /**
     * Età minima per registrarsi autonomamente, secondo l'art. 8 GDPR: sotto i 16 anni
     * il consenso alla prestazione di un servizio online deve essere autorizzato dal
     * titolare della responsabilità genitoriale. Il GDPR consente agli Stati membri di
     * abbassare la soglia fino a 13 anni; si è scelto il limite più restrittivo
     * consentito, 13, e la registrazione autonoma resta consentita da 16 anni.
     */
    public static final int MIN_SELF_CONSENT_AGE = 16;

    /** Soglia minima di età ammessa dal regolamento. */
    public static final int MIN_ALLOWED_AGE = 13;

    /**
     * Fattore di lavoro PBKDF2. 600 000 iterazioni è il valore raccomandato da OWASP
     * per PBKDF2-HMAC-SHA256; è un costo di login circa 100 ms per utente, accettabile
     * per un servizio di gioco. Si può abbassare con la variabile d'ambiente
     * {@code PBKDF2_ITERATIONS} solo in ambienti di test.
     */
    public static final int DEFAULT_PBKDF2_ITERATIONS = 600_000;
    private static final int MIN_PBKDF2_ITERATIONS = 1_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static class Account {
        private final String username;
        private final String passwordHash;
        private final String salt;
        private final int iterations;
        // Età dichiarata dall'utente al momento della registrazione (0 = non dichiarata).
        // Dato personale trattato per adempiere all'art. 8 GDPR.
        private volatile int declaredAge = 0;

        private final Set<String> friends = ConcurrentHashMap.newKeySet();
        // I contatori vengono letti dai thread dei client (comandi STATS, LOGIN_OK) senza
        // il lock di AccountManager, che protegge solo la mutazione: volatile garantisce
        // la visibilita' delle modifiche senza richiedere il monitor.
        private volatile int wins = 0;
        private volatile int losses = 0;
        private volatile int draws = 0;
        private volatile int elo = 1200;

        Account(String username, String salt, int iterations, String passwordHash) {
            this.username = username;
            this.salt = salt;
            this.iterations = iterations;
            this.passwordHash = passwordHash;
        }

        public String getUsername() {
            return username;
        }

        /**
         * @return la traccia derivata della password, mai la password stessa.
         */
        public String getPasswordHash() {
            return passwordHash;
        }

        public String getSalt() {
            return salt;
        }

        public int getIterations() {
            return iterations;
        }

        /** @return l'età dichiarata alla registrazione, 0 se non dichiarata */
        public int getDeclaredAge() {
            return declaredAge;
        }

        public void setDeclaredAge(int declaredAge) {
            this.declaredAge = declaredAge;
        }

        public Set<String> getFriends() {
            return friends;
        }

        public int getWins() {
            return wins;
        }

        public int getLosses() {
            return losses;
        }

        public int getDraws() {
            return draws;
        }

        public int getElo() {
            return elo;
        }

        public void addWin() {
            wins++;
            elo += 15;
        }

        public void addLoss() {
            losses++;
            elo = Math.max(100, elo - 15);
        }

        public void addDraw() {
            draws++;
        }
    }

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final String dataFile;
    private final int pbkdf2Iterations;

    public AccountManager() {
        this(DEFAULT_DATA_FILE);
    }

    /**
     * @param dataFile percorso del file di persistenza. Con null o stringa vuota la
     *                 persistenza su disco viene **disattivata**: comodo per i test,
     *                 ma va usato con cautela, perché nulla viene salvato.
     */
    public AccountManager(String dataFile) {
        this(dataFile, readIterationsFromEnv());
    }

    /**
     * @param dataFile            percorso del file di persistenza, null per disattivarla
     * @param pbkdf2Iterations    fattore di lavoro; sotto il minimo viene corretto al minimo
     */
    public AccountManager(String dataFile, int pbkdf2Iterations) {
        this.dataFile = (dataFile == null || dataFile.isBlank()) ? null : dataFile;
        this.pbkdf2Iterations = Math.max(MIN_PBKDF2_ITERATIONS, pbkdf2Iterations);
        loadAccounts();
    }

    private static int readIterationsFromEnv() {
        String raw = System.getenv("PBKDF2_ITERATIONS");
        if (raw == null || raw.isBlank()) return DEFAULT_PBKDF2_ITERATIONS;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PBKDF2_ITERATIONS;
        }
    }

    /**
     * @return il percorso del file di persistenza, oppure null se disattivata
     */
    public String getDataFile() {
        return dataFile;
    }

    public int getPbkdf2Iterations() {
        return pbkdf2Iterations;
    }

    /**
     * Genera un sale casuale in esadecimale.
     */
    private static String generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return toHex(salt);
    }

    /**
     * Deriva la password con PBKDF2-HMAC-SHA256.
     *
     * <p>PBKDF2 applica molteplici iterazioni di HMAC: ogni iterazione rende più
     * costoso un singolo tentativo, e il sale impedisce di riutilizzare una tabella
     * precalcolata tra utenti con password uguali.
     */
    private static String hashPassword(String password, String salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(),
                salt.getBytes(StandardCharsets.UTF_8),
                iterations,
                HASH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return toHex(factory.generateSecret(spec).getEncoded());
        } catch (NoSuchAlgorithmException e) {
            // PBKDF2WithHmacSHA256 è garantito dalla specifica Java: se manca, la JVM è rotta.
            throw new IllegalStateException("PBKDF2WithHmacSHA256 non disponibile", e);
        } catch (InvalidKeySpecException e) {
            throw new IllegalStateException("Specifica PBKDF2 non valida", e);
        } finally {
            // PBEKeySpec conserva una copia della password in memoria: va azzerata.
            spec.clearPassword();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(HEX[(b >> 4) & 0xF]);
            out.append(HEX[b & 0xF]);
        }
        return out.toString();
    }

    /**
     * Confronto in tempo costante, cosi' da non rivelare quanti caratteri
     * della traccia coincidono tramite l'analisi dei tempi di risposta.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param declaredAge età dichiarata dall'utente; 0 se non dichiarata
     * @return true se la registrazione è riuscita
     */
    public synchronized boolean register(String username, String password, int declaredAge) {
        if (username == null || password == null) return false;
        String cleanUser = username.trim();
        if (cleanUser.length() < MIN_USERNAME_LENGTH
                || password.length() < MIN_CREDENTIAL_LENGTH
                || cleanUser.isEmpty()) {
            return false;
        }
        // Età plausibile: sotto i 13 anni non è consentito in nessun caso,
        // perché il GDPR non ammette il consenso autonomo prima dei 13 anni
        // nemmeno dove la soglia è stata abbassata.
        if (declaredAge != 0 && (declaredAge < MIN_ALLOWED_AGE || declaredAge > 120)) {
            return false;
        }
        // Nel nome sono ammessi solo caratteri alfanumerici, '_' e '-':
        // i separatori del file di persistenza non possono comparire altrimenti.
        if (!cleanUser.matches("[a-zA-Z0-9_\\-]+")) return false;

        String key = cleanUser.toLowerCase(Locale.ROOT);
        if (accounts.containsKey(key)) return false;

        String salt = generateSalt();
        Account acc = new Account(cleanUser, salt, pbkdf2Iterations,
                hashPassword(password, salt, pbkdf2Iterations));
        acc.setDeclaredAge(declaredAge);
        accounts.put(key, acc);
        saveAccounts();
        return true;
    }

    /** Registrazione senza età dichiarata. */
    public boolean register(String username, String password) {
        return register(username, password, 0);
    }

    /**
     * @return true se l'utente può prestare consenso autonomamente all'età dichiarata,
     *         oppure se l'età non è stata dichiarata (nessun controllo possibile).
     */
    public static boolean isSelfConsentingAge(int declaredAge) {
        return declaredAge == 0 || declaredAge >= MIN_SELF_CONSENT_AGE;
    }

    /**
     * Un nome utente non puo' identificare due account diversi, altrimenti un
     * risultato di partita finirebbe su un account sbagliato (o su nessuno).
     */
    public boolean isUsernameTaken(String username) {
        if (username == null) return false;
        return accounts.containsKey(username.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Test isolato del comportamento della cancellazione sulla lista amici.
     * Esposto per permettere una verifica diretta, senza passare dalla rete.
     */
    public boolean hasFriend(String username, String friendName) {
        Account acc = getAccount(username);
        if (acc == null) return false;
        for (String f : acc.getFriends()) {
            if (f.equalsIgnoreCase(friendName)) return true;
        }
        return false;
    }

    public synchronized Account authenticate(String username, String password) {
        if (username == null || password == null) return null;
        Account acc = accounts.get(username.trim().toLowerCase(Locale.ROOT));
        if (acc == null) return null;
        // Anche in caso di utente inesistente si esegue comunque una derivazione,
        // cosi' il tempo di risposta non rivela quali nomi utente esistono.
        boolean matches = constantTimeEquals(
                acc.getPasswordHash(),
                hashPassword(password, acc.getSalt(), acc.getIterations()));
        return matches ? acc : null;
    }

    public Account getAccount(String username) {
        if (username == null) return null;
        return accounts.get(username.trim().toLowerCase(Locale.ROOT));
    }

    public int getAccountCount() {
        return accounts.size();
    }

    public synchronized boolean addFriend(String username, String friendUsername) {
        Account userAcc = getAccount(username);
        Account friendAcc = getAccount(friendUsername);
        if (userAcc == null || friendAcc == null) return false;
        if (userAcc.getUsername().equalsIgnoreCase(friendAcc.getUsername())) return false;

        userAcc.getFriends().add(friendAcc.getUsername());
        friendAcc.getFriends().add(userAcc.getUsername());
        saveAccounts();
        return true;
    }

    public Set<String> getFriends(String username) {
        Account acc = getAccount(username);
        return (acc != null) ? acc.getFriends() : Collections.emptySet();
    }

    /**
     * Registra l'esito di una partita aggiornando statistiche ed ELO.
     * Utenti non registrati (ospiti) vengono ignorati silenziosamente.
     */
    public synchronized void recordResult(String winnerUsername, String loserUsername, boolean isDraw) {
        Account w = getAccount(winnerUsername);
        Account l = getAccount(loserUsername);
        if (isDraw) {
            if (w != null) w.addDraw();
            if (l != null) l.addDraw();
        } else {
            if (w != null) w.addWin();
            if (l != null) l.addLoss();
        }
        saveAccounts();
    }

    /**
     * Diritto di cancellazione (GDPR art. 17): elimina l'account con le sue
     * statistiche e rimuove ogni riferimento residuo nelle liste amici degli altri.
     * Senza quest'ultimo passo il nome dell'utente cancellato continuerebbe a
     * comparire nei dati di altri interessati, che è proprio ciò che la
     * cancellazione deve impedire.
     *
     * @return true se l'account esisteva ed è stato rimosso
     */
    public synchronized boolean deleteAccount(String username) {
        Account acc = getAccount(username);
        if (acc == null) return false;

        accounts.remove(acc.getUsername().toLowerCase(Locale.ROOT));

        // Rimuove il nome dagli amici di tutti gli altri, che lo conserverebbero
        // altrimenti. Il confronto è senza distinzione di maiuscole perché gli
        // account sono indicizzati per chiave normalizzata, ma i nomi salvati nelle
        // liste amici conservano le maiuscole originali.
        String removed = acc.getUsername();
        for (Account other : accounts.values()) {
            other.getFriends().removeIf(f -> f.equalsIgnoreCase(removed));
        }

        saveAccounts();
        return true;
    }

    /**
     * Diritto di accesso e portabilità (GDPR art. 15 e 20): restituisce tutti i
     * dati personali dell'utente in formato leggibile, senza includere la traccia
     * della password, che non è dato dell'interessato ma segreto tecnico del sistema.
     *
     * @return righe di dati, oppure lista vuota se l'account non esiste
     */
    public synchronized List<String> exportAccount(String username) {
        Account acc = getAccount(username);
        if (acc == null) return List.of();

        List<String> out = new ArrayList<>();
        out.add("username: " + acc.getUsername());

        out.add("elo: " + acc.getElo());
        out.add("vittorie: " + acc.getWins());
        out.add("sconfitte: " + acc.getLosses());
        out.add("patte: " + acc.getDraws());
        List<String> friends = new ArrayList<>(acc.getFriends());
        Collections.sort(friends);
        out.add("amici: " + (friends.isEmpty() ? "(nessuno)" : String.join(", ", friends)));
        out.add("eta_dichiarata: " + (acc.getDeclaredAge() == 0 ? "non dichiarata" : acc.getDeclaredAge()));
        out.add("metodo_di_derivazione: PBKDF2-HMAC-SHA256, " + acc.getIterations() + " iterazioni");
        return out;
    }

    /**
     * Salva gli account su disco. Il file viene scritto in un file temporaneo
     * e poi rinominato, cosi' un crash a meta' scrittura non lo corrompe.
     */
    private void saveAccounts() {
        if (dataFile == null) return;

        File target = new File(dataFile);
        File temp = new File(dataFile + ".tmp");
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                System.err.println("Impossibile creare la cartella per gli account: " + parent);
            }
            try (PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8))) {
                for (Account acc : accounts.values()) {
                    String friendsList = String.join(",", acc.getFriends());
                    writer.println(String.join(":",
                            acc.getUsername(), acc.getSalt(), String.valueOf(acc.getIterations()),
                            acc.getPasswordHash(),
                            String.valueOf(acc.getWins()), String.valueOf(acc.getLosses()),
                            String.valueOf(acc.getDraws()), String.valueOf(acc.getElo()),
                            String.valueOf(acc.getDeclaredAge()), friendsList));
                }
            }
            // Rinomina atomica: sovrascrive il file precedente solo a scrittura completata.
            java.nio.file.Files.move(temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Errore nel salvataggio account: " + e.getMessage());
            if (temp.exists() && !temp.delete()) {
                System.err.println("Impossibile rimuovere il file temporaneo: " + temp);
            }
        }
    }

    private void loadAccounts() {
        if (dataFile == null) return;
        File file = new File(dataFile);
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                String[] parts = line.split(":", -1);
                // Formato atteso: username:salt:iterazioni:hash:w:l:d:elo:eta:amici
                if (parts.length < 8) {
                    System.err.println(" Riga " + lineNumber + " ignorata: formato non riconosciuto"
                            + " (gli account con password in chiaro non vengono migrati).");
                    continue;
                }
                int iterations;
                try {
                    iterations = Math.max(MIN_PBKDF2_ITERATIONS, Integer.parseInt(parts[2]));
                } catch (NumberFormatException e) {
                    System.err.println(" Riga " + lineNumber + " ignorata: fattore di lavoro non numerico.");
                    continue;
                }
                Account acc = new Account(parts[0], parts[1], iterations, parts[3]);
                try {
                    acc.wins = Integer.parseInt(parts[4]);
                    acc.losses = Integer.parseInt(parts[5]);
                    acc.draws = Integer.parseInt(parts[6]);
                    acc.elo = Integer.parseInt(parts[7]);
                } catch (NumberFormatException e) {
                    System.err.println(" Riga " + lineNumber + " ignorata: statistiche non numeriche.");
                    continue;
                }
                // L'età dichiarata è il campo 8; se manca (file in formato precedente)
                // l'account resta comunque valido, semplicemente senza età registrata.
                if (parts.length >= 9) {
                    try {
                        acc.declaredAge = Integer.parseInt(parts[8]);
                    } catch (NumberFormatException ignored) {
                        // età non numerica: si lascia a 0
                    }
                }
                if (parts.length >= 10 && !parts[9].isBlank()) {
                    for (String f : parts[9].split(",")) {
                        if (!f.isBlank()) acc.getFriends().add(f.trim());
                    }
                }
                accounts.put(acc.getUsername().toLowerCase(Locale.ROOT), acc);
            }
        } catch (IOException e) {
            System.err.println("Errore nel caricamento account: " + e.getMessage());
        }
    }
}
