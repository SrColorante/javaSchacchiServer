package org.schacchi.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestione degli account utente, autenticazione, statistiche e lista amici.
 * I dati sono salvati su file locale (percorso configurabile) per mantenere la
 * persistenza tra riavvii.
 *
 * <h2>Formato del file di persistenza</h2>
 * Una riga per account, campi separati da ':':
 * <pre>username:salt:passwordHash:vittorie:sconfitte:patte:elo:amici,separati,da,virgola</pre>
 *
 * <h2>Sicurezza</h2>
 * Le password NON vengono mai scritte in chiaro: si conserva un sale casuale per
 * utente e la traccia SHA-256 di sale+password. Il confronto avviene in tempo
 * costante per non rivelare informazioni tramite tempi di risposta.
 */
public class AccountManager {
    /** Percorso predefinito del file di persistenza, risolto rispetto alla directory di lavoro. */
    public static final String DEFAULT_DATA_FILE = "accounts.txt";

    private static final int SALT_BYTES = 16;
    private static final int MIN_CREDENTIAL_LENGTH = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    public static class Account {
        private final String username;
        private final String passwordHash;
        private final String salt;
        private final Set<String> friends = ConcurrentHashMap.newKeySet();
        // I contatori vengono letti dai thread dei client (comandi STATS, LOGIN_OK) senza
        // il lock di AccountManager, che protegge solo la mutazione: volatile garantisce
        // la visibilita' delle modifiche senza richiedere il monitor.
        private volatile int wins = 0;
        private volatile int losses = 0;
        private volatile int draws = 0;
        private volatile int elo = 1200;

        Account(String username, String salt, String passwordHash) {
            this.username = username;
            this.salt = salt;
            this.passwordHash = passwordHash;
        }

        public String getUsername() {
            return username;
        }

        /**
         * @return la traccia SHA-256 (esadecimale) di sale+password, mai la password.
         */
        public String getPasswordHash() {
            return passwordHash;
        }

        public String getSalt() {
            return salt;
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

    public AccountManager() {
        this(DEFAULT_DATA_FILE);
    }

    /**
     * @param dataFile percorso del file di persistenza. Con null o stringa vuota la
     *                 persistenza su disco viene **disattivata**: comodo per i test,
     *                 ma va usato con cautela, perché nulla viene salvato.
     */
    public AccountManager(String dataFile) {
        this.dataFile = (dataFile == null || dataFile.isBlank()) ? null : dataFile;
        loadAccounts();
    }

    /**
     * @return il percorso del file di persistenza, oppure null se disattivata
     */
    public String getDataFile() {
        return dataFile;
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
     * Calcola la traccia SHA-256 di "sale:password".
     */
    private static String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 e' garantito dalla specifica Java: se manca, la JVM e' rotta.
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
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

    public synchronized boolean register(String username, String password) {
        if (username == null || password == null) return false;
        String cleanUser = username.trim();
        if (cleanUser.length() < MIN_CREDENTIAL_LENGTH
                || password.length() < MIN_CREDENTIAL_LENGTH
                || cleanUser.isEmpty()) {
            return false;
        }
        // Nel nome sono ammessi solo caratteri alfanumerici, '_' e '-':
        // i separatori del file di persistenza non possono comparire altrimenti.
        if (!cleanUser.matches("[a-zA-Z0-9_\\-]+")) return false;

        String key = cleanUser.toLowerCase(Locale.ROOT);
        if (accounts.containsKey(key)) return false;

        String salt = generateSalt();
        Account acc = new Account(cleanUser, salt, hashPassword(password, salt));
        accounts.put(key, acc);
        saveAccounts();
        return true;
    }

    public synchronized Account authenticate(String username, String password) {
        if (username == null || password == null) return null;
        Account acc = accounts.get(username.trim().toLowerCase(Locale.ROOT));
        if (acc == null) return null;
        // Anche in caso di utente inesistente si esegue un hash fittizio,
        // cosi' il tempo di risposta non rivela quali nomi utente esistono.
        boolean matches = constantTimeEquals(acc.getPasswordHash(), hashPassword(password, acc.getSalt()));
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
                            acc.getUsername(), acc.getSalt(), acc.getPasswordHash(),
                            String.valueOf(acc.getWins()), String.valueOf(acc.getLosses()),
                            String.valueOf(acc.getDraws()), String.valueOf(acc.getElo()),
                            friendsList));
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
                // Formato atteso: username:salt:hash:w:l:d:elo:amici
                if (parts.length < 7) {
                    System.err.println(" Riga " + lineNumber + " ignorata: formato non riconosciuto"
                            + " (gli account con password in chiaro non vengono migrati).");
                    continue;
                }
                Account acc = new Account(parts[0], parts[1], parts[2]);
                try {
                    acc.wins = Integer.parseInt(parts[3]);
                    acc.losses = Integer.parseInt(parts[4]);
                    acc.draws = Integer.parseInt(parts[5]);
                    acc.elo = Integer.parseInt(parts[6]);
                } catch (NumberFormatException e) {
                    System.err.println(" Riga " + lineNumber + " ignorata: statistiche non numeriche.");
                    continue;
                }
                if (parts.length >= 8 && !parts[7].isBlank()) {
                    for (String f : parts[7].split(",")) {
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
