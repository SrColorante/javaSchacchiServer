# javaSchacchiServer

> Progetto in mostra su **[cristianrenosto.party/projects](https://cristianrenosto.party/projects)**

Server di scacchi online in **Java 17**: motore di regole scritto a mano, server
TCP e client desktop Swing. Zero dipendenze runtime.

Tre funzioni distinte:

1. **Relè autoritativo** — due client giocano; il server gestisce ogni messaggio
2. **Arbitro delle regole** — un motore scritto a mano valida ogni mossa lato
   server e decide tutte le condizioni di fine partita
3. **Gestore multi-sessione** — stanze illimitate indipendenti, coda FIFO di
   matchmaking rapido, account, ELO e lista amici

Target dichiarato: `game.cristianrenosto.party:12345`.

> Non è un bot, non è un motore UCI, non è un'applicazione web e non usa un
> database.

---

## Requisiti

| Componente | Versione |
|---|---|
| JDK | 17 (compilato con 21) |
| Maven | con i plugin pinnati in `pom.xml` |
| Display | Necessario per la dashboard server e per il **client**; il server funziona headless |
| Database | Nessuno — gli account stanno in un file di testo |

**Dipendenze: una sola, in scope `test`** — `junit-jupiter` 5.10.0. A runtime non
c'è nulla: Swing/AWT vengono dal JDK, nessun driver, nessuna libreria JSON, nessun
framework di logging.

## Comandi

```bash
mvn compile
mvn test
mvn clean package          # -> target/SchacchiServer-1.0-SNAPSHOT-jar-with-dependencies.jar

# Server (dashboard Swing, oppure console se headless)
mvn compile exec:java -Dexec.mainClass="org.schacchi.Main"
PORT=8080 mvn compile exec:java -Dexec.mainClass="org.schacchi.Main"
java -jar target/SchacchiServer-1.0-SNAPSHOT-jar-with-dependencies.jar

# Client (richiede un display)
mvn compile exec:java -Dexec.mainClass="org.schacchi.ClientMain"
java -cp target/SchacchiServer-1.0-SNAPSHOT-jar-with-dependencies.jar org.schacchi.ClientMain
```

> **`java -jar … 8080` non funziona.** `Main.main` **ignora `args`**: la porta si
> imposta solo con la variabile d'ambiente `PORT`. Passare la porta come argomento
> viene silenziosamente ignorato e il server apre comunque 12345.
>
> `mvn exec:java` senza `-Dexec.mainClass` non funziona: il plugin ha
> `org.schacchi.Main` hardcoded.

`Main` rileva automaticamente l'ambiente headless con
`GraphicsEnvironment.isHeadless()` e salta del tutto Swing su VPS o container,
restando in console. Il server supporta la porta `0` per i test (il SO assegna
una porta libera).

## Test

```bash
mvn test
mvn -Dtest=ChessBoardTest test
mvn -Dtest=ChessServerIntegrationTest test
```

**70 test** su 4 classi:

| Classe | Righe | Test | Copre |
|---|---:|---:|---|
| `ChessBoardTest` | 530 | 40 | Perft, regole speciali, FEN, patte, copia |
| `ChessServerIntegrationTest` | 639 | 15 | End-to-end su socket reali |
| `GameSessionDrawTest` | 281 | 8 | Logica di patta a livello di sessione |
| `ChessServerTest` | 161 | 7 | Smoke test piatti (pre-JUnit, ha ancora un `main`) |

La cosa più notevole: **`ChessBoardTest` contiene una suite perft** verificata
contro i valori pubblicati.

| Posizione | Profondità | Nodi attesi |
|---|---|---|
| Iniziale | 1–4 | 20 · 400 · 8 902 · **197 281** |
| Kiwipete | 1–3 | 48 · 2 039 · 97 862 |
| pos-3 | 1–4 | 14 · 191 · 2 812 · 43 238 |
| pos-4 | 1–3 | 6 · 264 · 9 467 |
| pos-5 | 1–3 | 44 · 1 486 · 62 379 |

## Struttura

```
javaSchacchiServer/
├── pom.xml
├── README.md
├── docs.md                 architettura, dominio, motore, protocollo, concorrenza
├── .gitignore
├── .vscode/settings.json    (3 righe: nessun launch.json, nessun tasks.json)
└── src/
    ├── main/java/org/schacchi/
    │   ├── Main.java              158   entry point server: dashboard Swing o console headless
    │   ├── ClientMain.java        227   entry point client: JFrame + CardLayout
    │   ├── model/                       Il dominio e il motore di regole
    │   │   ├── ChessBoard.java     918   generazione mosse, legalità, scacco/matto/stallo,
    │   │   │                             castello, en passant, promozione, FEN, patte
    │   │   ├── Move.java            87   immutabile (da, a, promozione) + UCI
    │   │   ├── Position.java        88   casella internata e immutabile
    │   │   ├── Piece.java           51   immutabile (tipo, colore) + conversione FEN
    │   │   ├── PieceType.java       32
    │   │   └── PieceColor.java      10
    │   ├── server/                      Il networking e le sessioni
    │   │   ├── GameSession.java     404   una partita: giocatori, spettatori, pipeline mosse
    │   │   ├── ConnectionHandler.java 366 per connessione; lo switch a 20 casi, 21 comandi
    │   │   ├── AccountManager.java  321   register/auth/amici/stats/ELO + persistenza
    │   │   ├── Server.java          294   singleton TCP acceptor, thread pool, listener
    │   │   ├── SessionManager.java  112   registro stanze + coda di matchmaking
    │   │   └── ServerListener.java   17   9 callback di default no-op
    │   └── client/                     La GUI Swing
    │       ├── ChessBoardPanel.java  341   scacchiera interattiva disegnata a mano
    │       ├── GameViewPanel.java    340   schermata di gioco
    │       ├── ClientNetwork.java    328   socket, thread lettore, builder di comandi
    │       ├── LobbyPanel.java       298   lobby: stanze, amici, profilo/ELO
    │       ├── LoginPanel.java       264   configurazione + tab Login/Register/Guest
    │       ├── ClientListener.java    35   interfaccia di callback (tutti default)
    │       ├── RoomInfo.java          30   DTO immutabile
    │       ├── FriendInfo.java        24   DTO immutabile
    │       └── ClientMain.java        10   shim verso org.schacchi.ClientMain
    └── test/java/org/schacchi/         4 classi, 1 611 righe, 70 test
```

**Totale: 4 755 righe di main, 1 611 di test.**

## Protocollo

TCP in chiaro, righe separate da `\n`, `CMD argomento` separati da spazi. Nessun
handshake, nessuna versione, nessun prefisso di lunghezza, nessun request id,
nessun codice di errore.

**21 comandi** del client: `REGISTER`, `LOGIN`, `FRIEND_ADD`, `FRIENDS`, `STATS`,
`NAME`, `CREATE`, `JOIN`, `PLAY`/`QUICKMATCH`/`MATCH`, `LIST`, `MOVE`, `RESIGN`,
`DRAW_OFFER`, `DRAW_ACCEPT`, `DRAW_DECLINE`, `CHAT`, `BOARD`/`FEN`, `LEAVE`,
`PING`, `QUIT`/`EXIT`.

**30 messaggi** dal server: `CONNECTED`, `YOUR_NAME`, `HELP`, `REGISTER_OK`,
`LOGIN_OK`, `NAME_CHANGED`, `FRIEND_ADDED`, `FRIENDS_LIST`, `FRIEND`,
`FRIENDS_END`, `STATS`, `ROOM_CREATED`, `ROOM_LIST`, `ROOM`, `GAME_START`,
`SPECTATING`, `MOVE_OK`, `OPPONENT_MOVE`, `MOVE`, `FEN`, `CHECK`, `GAME_OVER`,
`CHAT`, `DRAW_OFFER`, `DRAW_DECLINED`, `OPPONENT_DISCONNECTED`, `INFO`, `ERROR`,
`PONG`, `SERVER_SHUTDOWN`.

Motivi di `GAME_OVER`: `CHECKMATE`, `STALEMATE`, `INSUFFICIENT_MATERIAL`,
`FIFTY_MOVE_RULE`, `THREEFOLD_REPETITION`, `AGREEMENT`, `RESIGNATION`, `FORFEIT`.

## Il fatto di design più importante

**La scacchiera non viene mai trasmessa come oggetti.** La rappresentazione
autorevole sul filo è la **stringa FEN**, che viaggia con ogni mossa
(`MOVE_OK <uci> <fen>`), e il client ricostruisce **tutta** la propria scacchiera da
essa a ogni mossa tramite `ChessBoard.fromFen`. Il client non ha uno stato di
partita autorevole: il replay locale è solo un fallback se il server non invia un
FEN utilizzabile.

## Persistenza

Gli account stanno in **`accounts.txt`**, risolto **rispetto alla directory di
lavoro corrente**. Otto campi separati da due punti:

```
username:salt:hash:w:l:d:elo:friends
```

- `salt`: 16 byte da `SecureRandom`, esadecimale
- `hash`: SHA-256 di `salt + ":" + password`
- Confronto in **tempo costante** via `MessageDigest.isEqual`; il tempo di risposta
  non distingue "utente inesistente" da "password errata"
- Scrittura atomica: `write <file>.tmp` poi `Files.move(…, REPLACE_EXISTING)`, così
  un crash a metà scrittura non corrompe il file
- Al caricamento servono **almeno 7 campi**; l'ottavo (`friends`, lista separata da
  virgole) è **facoltativo**. Le righe con meno di 7 campi vengono saltate e gli
  account con password in chiaro **non vengono migrati** (scelta deliberata)
- I contatori sono `volatile`: il lock di `AccountManager` protegge solo la
  mutazione, i thread dei client li leggono senza passarci
- ELO: `+15` alla vittoria, `max(100, elo - 15)` alla sconfitta, invariato alla
  patta. Default 1200. **Non è la formula FIDE**

## Confronto con `chess-server`

`~/Progetti/chess-server` è un progetto **fratello separato** dello stesso autore,
non un fork: nessun codice, package, protocollo o documentazione condiviso.

| | `javaSchacchiServer` | `chess-server` |
|---|---|---|
| Build | Maven, `org.schacchi` | `javac` + `build.sh`, package `game`/`server`/`client` |
| Porta | 12345 (env `PORT`) | 6700 |
| Protocollo | `CMD arg` separati da spazio | `KEY:payload` separati da due punti |
| Account | file di testo, SHA-256 + salt | PostgreSQL, PBKDF2 120k iterazioni |
| Motore | chiavi di ripetizione da stringa FEN | Zobrist 64 bit, cache del re |
| Extra | matchmaking, amici, ELO, spettatori | orologio di turno, stanze private, codici a 6 caratteri |

## Limiti noti

- 🔴 **Tutto il traffico è TCP in chiaro.** Le password passano in chiaro come
  `LOGIN <utente> <password>`. Lo storage lato server è fatto bene, ma non
  serve a nulla sul filo.
- 🔴 **SHA-256 non è una KDF per password.** Nessun PBKDF2/bcrypt/scrypt/Argon2,
  nessun work factor: un `accounts.txt` trapelato si rompe a velocità GPU.
- 🔴 **La disconnessione è forfeit immediato e permanente**, senza periodo di
  grazia, e **muove l'ELO**. Il pulsante "Arresta Server" della dashboard chiude
  ogni socket: ogni partita in corso finisce come forfeit e ogni punteggio cambia.
- **La ripetizione a tre mosse è trattata come patta automatica**, non come
  dichiarazione. Il regolamento FIDE la tratta come *claim*; il quintplice è
  automatico e questo codice non lo implementa. Perciò l'affermazione "garante
  delle regole ufficiali FIDE" sovradichiara.
- **Nessuna unicità dei nomi**: `NAME` e `REGISTER` non verificano che l'account
  non sia già collegato. Due connessioni vive possono condividere un'identità, e
  i risultati vengono registrati su quel nome condiviso.
- **`newCachedThreadPool()` non è limitato**: nessun tetto di connessioni, nessun
  rate limiting, nessun timeout di lettura sui socket lato server. Un flood di
  connessioni crea thread illimitati.
- **Gli spettatori non vedono la cronologia**: `moveHistory` non viene mai
  trasmessa, quindi chi si collega a metà partita ha un pannello vuoto per sempre.
- **Nessun riconnessione**: un calo TCP è un forfeit. Il client non ha logica di
  reconnect né backoff.
- **`HELP` del server è incompleto**: omette `REGISTER`, `LOGIN`, `STATS`,
  `FRIENDS`, `FRIEND_ADD`, `PING` e `SPECTATING`.
- Il client **ignora silenziosamente** `HELP`, `PONG` e `SERVER_SHUTDOWN`: un
  riavvio del server è invisibile, il socket muore e basta.
- **`loadFen` rifiuta un FEN malformato senza alterare lo stato**: la posizione
  viene decodificata e validata in strutture locali e copiata nei campi solo dopo
  l'ultimo controllo, quindi un caricamento fallito lascia la scacchiera intatta
  (non azzerata né parzialmente popolata).
- Nessun `.gitignore` iniziale (aggiunto ora): `target/` era tracciato. Nessuna
  `LICENSE`, nessuna CI. Un solo commit in assoluto: tutto il motore di scacchi è
  lavoro non committato.

Dettaglio completo in [`docs.md`](./docs.md).

## Documentazione

| File | Contenuto |
|---|---|
| [`docs.md`](./docs.md) | Architettura, modello di dominio, il motore di regole, protocollo, concorrenza, test con perft, limiti con riferimenti di codice |
