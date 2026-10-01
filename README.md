# javaSchacchiServer

> Progetto in mostra su **[cristianrenosto.party/projects](https://cristianrenosto.party/projects)**

Server di scacchi online in **Java 17**: motore di regole scritto a mano, server
TCP solo terminale e client desktop Swing. Zero dipendenze runtime.

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
| Display | Solo per il **client**; il server è solo terminale e gira su VPS, container o systemd |
| Database | Nessuno — gli account stanno in un file di testo |

**Dipendenze: una sola, in scope `test`** — `junit-jupiter` 5.10.0. A runtime non
c'è nulla: Swing/AWT vengono dal JDK, nessun driver, nessuna libreria JSON, nessun
framework di logging.

## Comandi

### Server — solo terminale

```bash
./start.sh                                    # compila se serve e avvia sulla porta 12345
./start.sh --port 8080                        # porta diversa
./start.sh --client                           # server + client grafico
./start.sh --client-only                      # solo client (server già avviato)

mvn compile exec:java -Dexec.mainClass="org.schacchi.Main"
java -jar target/SchacchiServer-1.0-SNAPSHOT-jar-with-dependencies.jar 19002
```

Il server stampa i log in streaming e, se lo stdin è un terminale, apre una console
di comandi. Sotto systemd o Docker stdin non è un terminale: il server resta un
processo con log puliti su stdout, che è ciò che un service manager si aspetta.

```
  +------------------------------------------------------+
  |  SCACCHI SERVER   solo terminale                      |
  |   porta           12345                               |
  |   connessioni max 200                                 |
  |   account         accounts.txt                        |
  |   console         attiva                              |
  +------------------------------------------------------+
```

| Argomento | Effetto |
|---|---|
| `12345` (numero nudo) | Porta di ascolto |
| `-p`, `--port <n>` | Idem, in forma esplicita |
| `--max-clients <n>` | Tetto di connessioni contemporanee |
| `--accounts <file>` | File degli account |
| `--no-console` | Solo log, niente console interattiva |
| `--no-color` | Log senza codici ANSI |

| Comando | Effetto |
|---|---|
| `status` | Server, client connessi, partite in corso e in attesa |
| `rooms` | Stanze con stato e identificativo |
| `clients` | Client connessi |
| `clear` | Pulisce il terminale |
| `stop` | Arresta il server |

### Client

```bash
mvn compile exec:java -Dexec.mainClass="org.schacchi.ClientMain"
java -cp target/SchacchiServer-1.0-SNAPSHOT-jar-with-dependencies.jar org.schacchi.ClientMain
```

L'host e la porta si precompilano con `-Dchess.host=…`, con la variabile
`CHESS_HOST`, o direttamente nel campo SERVER della schermata di login.

### Variabili d'ambiente

| Variabile | Predefinito | Effetto |
|---|---|---|
| `PORT` | `12345` | Porta di ascolto |
| `MAX_CLIENTS` | `200` | Tetto di connessioni contemporanee |
| `SOCKET_TIMEOUT_MS` | `0` (disabilitato) | Timeout di inattività sui socket |
| `PBKDF2_ITERATIONS` | `600000` | Fattore di lavoro per le password |
| `NO_COLOR` | — | Se impostata, i log escono senza codici colore |
| `CHESS_COLOR` | — | `0` forza i log senza colore, `1` li forza con |

> `mvn exec:java` senza `-Dexec.mainClass` non funziona: il plugin ha
> `org.schacchi.Main` hardcoded.

La porta si imposta con `--port`, con un numero come argomento nudo
(`java -jar … 19002`) o con la variabile d'ambiente `PORT`. In quest'ordine di
precedenza: gli argomenti vincono sull'ambiente. Il server supporta anche la porta
`0`, con cui il SO assegna una porta libera (usata dai test).

## Test

```bash
mvn test
mvn -Dtest=ChessBoardTest test
mvn -Dtest=ChessServerIntegrationTest test
```

**80 test** su 4 classi:

| Classe | Righe | Test | Copre |
|---|---:|---:|---|
| `ChessBoardTest` | 530 | 40 | Perft, regole speciali, FEN, patte, copia |
| `ChessServerIntegrationTest` | 916 | 25 | End-to-end su socket reali, diritti GDPR, limiti di connessione |
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
    │   ├── Main.java              515   entry point server: solo terminale, log + console
    │   ├── ClientMain.java        327   entry point client: JFrame su sfondo animato
    │   ├── model/                       Il dominio e il motore di regole
    │   │   ├── ChessBoard.java     936   generazione mosse, legalità, scacco/matto/stallo,
    │   │   │                             castello, en passant, promozione, FEN, patte
    │   │   ├── Move.java            87   immutabile (da, a, promozione) + UCI
    │   │   ├── Position.java        88   casella internata e immutabile
    │   │   ├── Piece.java           51   immutabile (tipo, colore) + conversione FEN
    │   │   ├── PieceType.java       32
    │   │   └── PieceColor.java      10
    │   ├── server/                      Il networking e le sessioni
    │   │   ├── GameSession.java     425   una partita: giocatori, spettatori, pipeline mosse
    │   │   ├── ConnectionHandler.java 529 per connessione; lo switch a 24 casi, 28 comandi
    │   │   ├── AccountManager.java  535   auth PBKDF2, amici, ELO, cancellazione GDPR
    │   │   ├── Server.java          413   acceptor TCP singleton, pool limitato, tetto connessioni
    │   │   ├── SessionManager.java  112   registro stanze + coda di matchmaking
    │   │   └── ServerListener.java   17   9 callback di default no-op
    │   └── client/                     La GUI Swing
    │       ├── Glass.java           661   design system: palette, tipografia, vetro, widget
    │       ├── Dialogs.java          249   popup modali in stile vetro (niente JOptionPane)
    │       ├── ChessBoardPanel.java  424   scacchiera interattiva disegnata a mano
    │       ├── GameViewPanel.java    370   schermata di gioco
    │       ├── ClientNetwork.java    392   socket, thread lettore, builder di comandi
    │       ├── LobbyPanel.java       520   lobby: stanze, amici, profilo e tab "I miei dati"
    │       ├── LoginPanel.java       400   connessione, tab Accedi/Account/Ospite, informativa
    │       ├── ClientListener.java    52   callback, incluso onDataExported/onAccountDeleted
    │       ├── RoomInfo.java          30   DTO immutabile
    │       ├── FriendInfo.java        24   DTO immutabile
    │       └── ClientMain.java        10   shim verso org.schacchi.ClientMain
    └── test/java/org/schacchi/         4 classi, 1 888 righe, 80 test
```

**Totale: 7 217 righe di main, 1 888 di test.**

### Lo stile del client

L'interfaccia è disegnata a mano in stile *liquid glass*: niente LookAndFeel di
sistema, che reintroduce sempre bordi e sfondi opachi. `Glass.java` tiene insieme
palette, tipografia e widget — pannelli di vetro, bottoni a pillola, campi con
riflesso in focus, tab disegnate a mano — e `Glass.Backdrop` disegna uno sfondo
aurora animato dietro tutto, così le superfici translucide hanno qualcosa da
mostrare. `Dialogs.java` sostituisce `JOptionPane`, che altrimenti mostrerebbe
pulsanti grigi e icone a colori in mezzo a una finestra dipinta a mano.

Tutto è `paintComponent` a mano: è l'unico modo con Swing di avere angoli
arrotondati, bordi di luce e animazioni di hover.

## Protocollo

TCP in chiaro, righe separate da `\n`, `CMD argomento` separati da spazi. Nessun
handshake, nessuna versione, nessun prefisso di lunghezza, nessun request id,
nessun codice di errore.

**28 comandi** del client, dai **24 casi** dello switch: `REGISTER`, `LOGIN`,
`LOGOUT`, `EXPORT_DATA`, `DELETE_ACCOUNT`, `FRIEND_ADD`, `FRIENDS`, `STATS`,
`NAME`, `CREATE`, `JOIN`, `PLAY`/`QUICKMATCH`/`MATCH`, `LIST`, `MOVE`, `RESIGN`,
`DRAW_OFFER`, `DRAW_ACCEPT`, `DRAW_DECLINE`, `CHAT`, `BOARD`/`FEN`, `LEAVE`,
`HELP`, `PING`, `QUIT`/`EXIT`.

**36 messaggi** dal server: `CHAT`, `CHECK`, `CONNECTED`, `DELETE_OK`, `DRAW_DECLINED`, `DRAW_OFFER`, `ERROR`, `EXPORT_EMPTY`, `EXPORT_END`, `EXPORT_LINE`, `FEN`, `FRIEND`, `FRIEND_ADDED`, `FRIENDS_END`, `FRIENDS_LIST`, `GAME_START`, `GAME_OVER`, `HELP`, `INFO`, `LOGIN_OK`, `LOGOUT_OK`, `MOVE`, `MOVE_HISTORY`, `MOVE_OK`, `NAME_CHANGED`, `OPPONENT_DISCONNECTED`, `OPPONENT_MOVE`, `PONG`, `REGISTER_OK`, `ROOM`, `ROOM_CREATED`, `ROOM_LIST`, `SERVER_SHUTDOWN`, `SPECTATING`, `STATS`, `YOUR_NAME`.

Motivi di `GAME_OVER`: `CHECKMATE`, `STALEMATE`, `INSUFFICIENT_MATERIAL`,
`SEVENTY_FIVE_MOVE_RULE`, `FIFTY_MOVE_RULE`, `FIVEFOLD_REPETITION`,
`THREEFOLD_REPETITION`, `AGREEMENT`, `RESIGNATION`, `FORFEIT`.

## Il fatto di design più importante

**La scacchiera non viene mai trasmessa come oggetti.** La rappresentazione
autorevole sul filo è la **stringa FEN**, che viaggia con ogni mossa
(`MOVE_OK <uci> <fen>`), e il client ricostruisce **tutta** la propria scacchiera da
essa a ogni mossa tramite `ChessBoard.fromFen`. Il client non ha uno stato di
partita autorevole: il replay locale è solo un fallback se il server non invia un
FEN utilizzabile.

## Persistenza

Gli account stanno in **`accounts.txt`**, risolto **rispetto alla directory di
lavoro corrente**. Dieci campi separati da due punti:

```
username:salt:iterazioni:hash:w:l:d:elo:eta:friends
```

- `salt`: 16 byte da `SecureRandom`, esadecimale
- `iterazioni`: fattore di lavoro PBKDF2 (default `600000`, minimo `1000`)
- `hash`: **PBKDF2-HMAC-SHA256** di `salt + ":" + password`
- Confronto in **tempo costante** via `MessageDigest.isEqual`; il tempo di risposta
  non distingue "utente inesistente" da "password errata"
- Scrittura atomica: `write <file>.tmp` poi `Files.move(…, REPLACE_EXISTING)`, così
  un crash a metà scrittura non corrompe il file
- Al caricamento servono **almeno 8 campi**; `eta` e `friends` sono **facoltativi**.
  Le righe con meno campi vengono saltate e gli account con password in chiaro
  **non vengono migrati** (scelta deliberata): con il formato a 8 campi
  l'iterazione letta è quella memorizzata, che per i file precedenti è assente
  e viene riportata al minimo
- I contatori sono `volatile`: il lock di `AccountManager` protegge solo la
  mutazione, i thread dei client li leggono senza passarci
- ELO: `+15` alla vittoria, `max(100, elo - 15)` alla sconfitta, invariato alla
  patta. Default 1200. **Non è la formula FIDE**

### Migrazione dai formati precedenti

Il formato è cambiato due volte (da 7 a 8 campi con l'hash SHA-256, poi a 10 con
PBKDF2 e l'età). **Le righe nel vecchio formato non vengono migrate e vengono
saltate**: gli account vanno ricreati. È una scelta deliberata — migrare
automaticamente da SHA-256 a PBKDF2 richiederebbe di conservare la vecchia traccia
accanto a quella nuova, vanificando il vantaggio del cambio.

## Privacy e GDPR

Dettaglio completo in [`docs/privacy.md`](./docs/privacy.md).

| Richiesta GDPR | Stato |
|---|---|
| art. 17 — cancellazione | `DELETE_ACCOUNT <password>` |
| art. 15 e 20 — accesso e portabilità | `EXPORT_DATA` |
| art. 8 — minori | età minima 13, consenso autonomo da 16 |
| art. 32 — password | PBKDF2-HMAC-SHA256, 600 000 iterazioni, sale per utente |
| minimizzazione | **nessun IP nei log**, chat non conservata |
| art. 13 — informativa | `docs/privacy.md` (template, **da completare**) |
| art. 30 — registro trattamenti | `docs/privacy.md` |

> **Non è ancora a norma.** Restano due lacune che il codice non può chiudere:
> il traffico è TCP in chiaro (le password passano in chiaro sul filo) e
> l'informativa privacy è un template con campi `[DA COMPILARE]`. Vedi
> `docs/privacy.md` §7 e §10.

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

### Risolti in questa versione

- ~~SHA-256 non è una KDF~~ → ora **PBKDF2-HMAC-SHA256, 600 000 iterazioni**
- ~~nessuna unicità dei nomi~~ → `NAME` e `LOGIN` rifiutano un nome già collegato
- ~~`newCachedThreadPool()` non è limitato~~ → pool a dimensione fissa con tetto
  `MAX_CLIENTS` (default 200); le connessioni oltre vengono rifiutate con un motivo
- ~~nessun timeout di lettura~~ → `SOCKET_TIMEOUT_MS` (default disabilitato)
- ~~gli spettatori non vedono la cronologia~~ → `MOVE_HISTORY` all'ingresso
- ~~`HELP` incompleto~~ → elenca tutti i 28 comandi, ed è interrogabile con `HELP`
- ~~il client ignora `HELP`, `PONG` e `SERVER_SHUTDOWN`~~ → tutti gestiti
- ~~quintuple ripetizione e 75 mosse non implementate~~ → ora automatiche
- ~~IP dei client scritti nei log~~ → rimossi

### Aperti

- 🔴 **Tutto il traffico è TCP in chiaro.** Le password passano in chiaro come
  `LOGIN <utente> <password>`. Lo storage lato server è fatto bene (PBKDF2,
  sale, confronto costante), ma non serve a nulla sul filo. **È la lacuna più
  grave e richiede TLS, che il codice applicativo non può risolvere.**
- 🔴 **Informativa privacy incompleta**: `docs/privacy.md` è un template con
  campi `[DA COMPILARE]` (titolare, DPO, hosting, trasferimenti extra SEE).
- 🔴 **Nessuna procedura di notifica breach** (art. 33 GDPR).
- 🟠 **`accounts.txt` non è cifrato a riposo.** Chi legge il file ottiene username,
  età, statistiche e liste amici. Le password sono protette dalla KDF, gli altri dati no.
- 🟠 **L'età è dichiarata, non verificata.** Un minore può dichiarare un'età falsa.
- 🔴 **La disconnessione è forfeit immediato e permanente**, senza periodo di
  grazia, e **muove l'ELO**. Il comando `stop` (o `Ctrl+C`) chiude ogni socket:
  ogni partita in corso finisce come forfeit e ogni punteggio cambia.
- **La ripetizione a tre mosse è trattata come patta automatica**, non come
  dichiarazione. Il regolamento FIDE la tratta come *claim*. Il quintplice, che è
  automatico, ora è implementato; resta la sovradichiarazione sulla tripla.
- **Nessun rate limiting**: c'è un tetto di connessioni, ma nessun limite di
  richieste al secondo per connessione.
- **Nessun riconnessione**: un calo TCP è un forfeit. Il client non ha logica di
  reconnect né backoff.
- **Nessun `LICENSE`, nessuna CI.**
- **`loadFen` rifiuta un FEN malformato senza alterare lo stato**: la posizione
  viene decodificata e validata in strutture locali e copiata nei campi solo dopo
  l'ultimo controllo, quindi un caricamento fallito lascia la scacchiera intatta
  (non azzerata né parzialmente popolata).

Dettaglio completo in [`docs.md`](./docs.md).

## Documentazione

| File | Contenuto |
|---|---|
| [`docs.md`](./docs.md) | Architettura, modello di dominio, il motore di regole, protocollo, concorrenza, test con perft, limiti con riferimenti di codice |
| [`docs/privacy.md`](./docs/privacy.md) | Informativa privacy GDPR (template), registro trattamenti, misure di sicurezza, non conformità note |

## Further reading

External material covering the same ground. The cross-repo map, with the same
links for all seven projects, is in `~/Progetti/RESOURCES.md`.

### Build it from scratch

- [Beej's Guide to Network Programming](http://beej.us/guide/bgnet/) — why one
  socket read is not one message, and therefore why the protocol is
  newline-delimited plain text.
- [Code a 2D Game Engine using Java — Full Course for Beginners](https://www.youtube.com/watch?v=025QFeZfeyM)
  *(video)* — the closest analogue to the rules engine.
- [Chess Engine In C](https://www.youtube.com/playlist?list=PLZ1QII7yudbc-Ky058TEaOstZHVbT-2hg)
  *(video)* — move generation and perft.

### System design

[System Design Primer](https://github.com/donnemartin/system-design-primer)
applies to this project only in part: the room registry and the matchmaking
queue are real shared state inside a single process, but there is no
database and no reverse proxy. The sections that match the known issues above:

| Known issue | Primer section |
|---|---|
| No rate limiting beyond a connection cap | [Availability patterns](https://github.com/donnemartin/system-design-primer#availability-patterns) |
| No reconnection; a TCP drop is a permanent forfeit | [Communication](https://github.com/donnemartin/system-design-primer#communication) (TCP), [Failure modes](https://github.com/donnemartin/system-design-primer#availability-patterns) |
| Rooms and ELO live in memory and in a text file | [Database](https://github.com/donnemartin/system-design-primer#database) |

### Books

- [Google's Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- [Introduction to Programming Using Java](https://math.hws.edu/javanotes) — David J. Eck, with exercises
- [A Practical Introduction to Data Structures and Algorithm Analysis, Java version](https://people.cs.vt.edu/shaffer/Book/Java3e20100119.pdf) — Clifford A. Shaffer

### Reference

- [roadmap.sh/java](https://roadmap.sh/java) · [network-engineer](https://roadmap.sh/network-engineer) · [computer-science](https://roadmap.sh/computer-science)
- [Awesome Java](https://github.com/akullpp/awesome-java) ·
  [Awesome Chess](https://github.com/hkirat/awesome-chess) ·
  [Static Analysis](https://github.com/analysis-tools-dev/static-analysis) —
  relevant to the absence of any CI or linter in this repository
- [Project-based learning, Java section](https://github.com/practical-tutorials/project-based-learning#java):
  [Build a Simple HTTP Server with Java](http://javarevisited.blogspot.com/2015/06/how-to-create-http-server-in-java-serversocket-example.html) ·
  [concurrent servers](https://eli.thegreenplace.net/2017/concurrent-servers-part-1-introduction/)
- [Awesome GDPR](https://github.com/bakke92/awesome-gdpr) — for `docs/privacy.md`
