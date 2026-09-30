# docs.md — Architettura di `javaSchacchiServer`

Documento di riferimento interno. Per la panoramica e i limiti noti vedi
[`README.md`](./README.md).

---

## 1. Forma del sistema

Un server TCP autoritativo e due client Swing. Il server possiede l'unica copia
reale della scacchiera e ne applica le regole; i client non arbitrano, si limitano
a proporre mosse e a disegnare ciò che il server conferma.

```
                         ┌──────────────────────────────┐
                         │  Server  (singleton)         │
                         │  ServerSocket 0..65535       │
    ┌────────────┐       │  PORT env o 12345            │       ┌────────────┐
    │  Client A  │◀─────▶│                              │◀─────▶│  Client B  │
    │  Swing     │ TCP   │  ┌────────────────────────┐  │  TCP  │  Swing     │
    └────────────┘       │  │ SessionManager         │  │       └────────────┘
                         │  │  ├ room_101  (in corso) │  │
    ┌────────────┐       │  │  ├ room_102  (in attesa)│  │       ┌────────────┐
    │  Client C  │◀─────▶│  │  └ coda matchmaking     │  │◀─────▶│  Client D  │
    │  spettatore│       │  ├────────────────────────┤  │       └────────────┘
    └────────────┘       │  │ GameSession × N         │  │
                         │  │  └ ChessBoard  ← regole │  │
                         │  │ AccountManager          │  │
                         │  └────────────────────────┘  │
                         └──────────────────────────────┘
```

## 2. Modello di dominio (`org.schacchi.model`)

Cinque tipi, deliberatamente piccoli e quasi tutti immutabili.

| Tipo | Ruolo | Note |
|---|---|---|
| `Position` | Casella `(row, col)` | 64 istanze pre-costruite in una `static final Position[][]`; `of(row,col)` non alloc mai. `equals`/`hashCode` per valore. |
| `Piece` | Coppia `(PieceType, PieceColor)` | **Immutabile**: i pezzi si possono condividere liberamente tra scacchiere, quindi `copy()` è un `System.arraycopy` di riferimenti. |
| `PieceType` | enum `PAWN KNIGHT BISHOP ROOK QUEEN KING` | `fromChar` per la conversione FEN. |
| `PieceColor` | enum `WHITE BLACK` | Solo `opposite()`. |
| `Move` | `(from, to, promotion?)` | Immutabile, `equals`/`hashCode` per valore, `toUci`/`fromUci`. |
| `ChessBoard` | La scacchiera e **tutte** le regole | 897 righe, l'unico oggetto mutabile di rilievo. |

### Indici di riga e colonna

`row 0` è la traversa 8, `row 7` la traversa 1. `getRank()` restituisce `8 - row`,
`getFile()` restituisce `'a' + col`. I metodi alternano semplicemente la direzione
in base al colore, ed è per questo che il motore non ha bisogno di due implementazioni
di mossa una per colore.

### FEN

`toFen()` produce i sei campi standard. `fromFen(String)` fa il contrario e valida
tutto prima di toccare lo stato: la disposizione viene decodificata in una matrice
locale, poi vengono controllati turno, diritti di arrocco, en passant e contatori, e
solo alla fine il contenuto viene copiato nei campi. Un FEN malformato lascia quindi
la scacchiera **esattamente** com'era (test `loadFenIsAtomic`).

I diritti di arrocco vengono **ricalcolati** sulla disposizione appena caricata: se
il FEN dichiara `K` ma il Re non è su `e1`, o la Torre non è su `h1`, il diritto viene
scartato. Serve a evitare che un FEN incoerente produca un "arrocco" che
teletrasporta il Re di due caselle lateralmente senza spostare la Torre.

## 3. Il motore di regole

### Pipeline di una mossa

```
Move.fromUci(str)
   → ChessBoard.isLegalMove(move)          genera e valida
   → ChessBoard.makeMove(move)              applica e registra la posizione
   → board.toFen()                          autorevole, va in rete
```

`getPseudoLegalMoves(Position)` produce solo le mosse conformi alla geometria del
 pezzo; **`isLegalMove` è il filtro che rende una mossa "legale"**: ogni candidata
viene applicata su una `copy()` e la mossa è accettata solo se il Re non resta in
scacco. È il trucco che rende corretto il pinning, gli schemi con pezzi che si
proteggono a vicenda e l'en passant orizzontale, senza casi speciali dedicati.

### `copy()`

```java
ChessBoard copy = new ChessBoard();
for (r) System.arraycopy(this.squares[r], 0, copy.squares[r], 0, 8);
```

`Piece` è immutabile, quindi copiare i riferimenti è sufficiente. `copy()` azzera
però la cronologia delle ripetizioni e la riallinea a una singola occorrenza: la
copia serve a simulare **una** mossa, e registrarne la posizione falserebbe il
conteggio delle ripetizioni.

### Ripetizione: perché sta in `makeMove` e non in `applyMoveInternal`

`applyMoveInternal` è chiamato sia dalla simulazione (`isLegalMove`, `getAllLegalMoves`)
sia dalla mossa reale. Registrare la posizione lì dentro conterebbe ogni simulazione.
Per questo la registrazione è in `makeMove`, l'unico punto di ingresso "reale":

```java
public synchronized boolean makeMove(Move move) { … applyMoveInternal(…);
    repetitionCounts.merge(getPositionKey(), 1, Integer::sum); }
```

La chiave di posizione sono i **primi quattro campi FEN**: disposizione, turno,
arrocco, en passant. I contatori sono esclusi per definizione. `getFenCastlingRights()`
è condiviso fra `toFen()` e `getPositionKey()`, così le due serializzazioni non
possono divergere.

Test che copre la trappola: `repetitionNotPollutedBySimulation`.

### Regole di patta

| Metodo | Condizione |
|---|---|
| `isCheckmate()` | Re in scacco e nessuna mossa legale |
| `isStalemate()` | Re non in scacco e nessuna mossa legale |
| `isInsufficientMaterial()` | Re/Re, Re+1 minore, Re+2 cavalli, soli alfieri stesso colore |
| `isFiftyMoveDraw()` | `halfmoveClock >= 100` |
| `isThreefoldRepetition()` | Chiave di posizione vista ≥ 3 volte |

`halfmoveClock` era già calcolato dal motore originale ma **non veniva mai usato**:
era il pezzo mancante più evidente.

### Precedenza a `GameSession`

```
scacco matto  →  stallo  →  { materiale insufficiente, 50 mosse, ripetizione }  →  scacco
```

Lo scacco matto viene **prima** delle patte automatiche per un motivo preciso: un
mate dato alla centesima semi-mossa soddisferebbe anche la regola delle 50 mosse, ma
l'art. 9.6.2 FIDE dice che in quel caso la partita è **vinta**. C'è un test dedicato
(`checkmateBeatsFiftyMoveRule`).

## 4. Verifica del motore: perft

Il test più forte disponibile per un generatore di mosse è il **perft**: il numero di
nodi dell'albero di mosse fino a una data profondità. I valori attesi sono noti e
calcolati da implementazioni indipendenti, quindi qualsiasi errore di generazione
(arrocco, en passant, promozione, legalità) cambia il numero.

| Posizione | Profondità | Nodi attesi |
|---|---|---|
| Iniziale | 1–4 | 20 · 400 · 8 902 · **197 281** |
| Kiwipete | 1–3 | 48 · 2 039 · 97 862 |
| pos-3 | 1–4 | 14 · 191 · 2 812 · 43 238 |
| pos-4 | 1–3 | 6 · 264 · 9 467 |
| pos-5 | 1–3 | 44 · 1 486 · 62 379 |

Il perft ha ripagato subito: ha rivelato che `loadFen` non azzerava la scacchiera, e
di conseguenza i segmenti numerici del FEN ("8", "3") non cancellavano nulla. Le
caselle vuote indicate dal FEN venivano semplicemente saltate, lasciando i pezzi della
posizione iniziale al loro posto. Il sintomo più subdolo non era il conteggio sbagliato
ma `findKing`, che trovava il Re pedina rimasto su `e8` invece di quello vero: lo
scacco veniva attribuito al Re sbagliato e una cattura en passant che esponeva il Re
passava come legale.

## 5. Protocollo

TCP in chiaro, righe separate da `\n`, `CMD argomento` separati da spazi. Nessun
handshake, nessuna versione, nessun request id, nessun codice di errore numerico.

**20 casi di comando** in `ConnectionHandler.handleCommand`, che diventano **21 comandi**
perché `PLAY`, `QUICKMATCH` e `MATCH` condividono lo stesso handler, e così
`BOARD`/`FEN` e `QUIT`/`EXIT`:

`REGISTER` · `LOGIN` · `FRIEND_ADD` · `FRIENDS` · `STATS` · `NAME` · `CREATE` ·
`JOIN` · `PLAY`/`QUICKMATCH`/`MATCH` · `LIST` · `MOVE` · `RESIGN` · `DRAW_OFFER` ·
`DRAW_ACCEPT` · `DRAW_DECLINE` · `CHAT` · `BOARD`/`FEN` · `LEAVE` · `PING` · `QUIT`/`EXIT`

**30 messaggi** dal server, di cui `ClientNetwork.handleServerLine` ne tratta 26.
I non trattati sono `HELP`, `PONG`, `SERVER_SHUTDOWN` e `INFO` dei canali non
interattivi: il client li ignora (vedi i limiti noti nel README).

### Il FEN è l'unica rappresentazione autorevole sul filo

Ogni mossa viaggia con il FEN autorevole (`MOVE_OK <uci> <fen>`,
`OPPONENT_MOVE <uci> <fen>`) e il client ricostruisce **tutta** la propria scacchiera
da quel FEN tramite `ChessBoard.fromFen`. Il client non ha uno stato di partita
autorevole: il replay locale della mossa è solo un fallback, usato se il server non
invia un FEN utilizzabile.

Prima di questa scelta il client ignorava il `fen` e riapplicava la mossa in locale,
il che desincronizzava in modo silenzioso e irreversibile non appena i due stati
divergevano.

## 6. Concorrenza

Un monitor per oggetto, con l'ordine di acquisizione **sempre** nella stessa direzione.

| Oggetto | Monitor | Protegge |
|---|---|---|
| `ConnectionHandler` | nessuno su `sendMessage`; `AtomicBoolean closed` per `close()` | — |
| `GameSession` | `synchronized` su metodi di stato | giocatori, spettatori, mosse, fine partita |
| `AccountManager` | `synchronized` su metodi mutanti | mappa account + scrittura su file |
| `Server` | `synchronized` su `stop()` | pool client, socket, arresti |
| `ChessBoard.makeMove` | `synchronized` | singola partita |

### L'inversione di lock che non deve tornare

C'era un deadlock reale, non ipotetico, che bloccava `Server.stop()` in modo
permanente:

```
Server.stop()  ──(monitor Server)──▶  client.close()  ──(monitor Handler)──▶  handlePlayerDisconnect()
                                                                                    │
processMove()  ──(monitor Session)────────────────────────────────────────▶ sendMessage()
                                          (monitor Handler)                     │
                                                                                    ▼
                                                          inversion: Session → Handler
                                                          e Handler → Session
```

Il ciclo chiudeva sia l'arresto del server sia i turni di gioco. La correzione non è
stato cambiare `synchronized`, ma **togliere il monitor dal percorso di invio**:

- `sendMessage` non è più `synchronized`. `PrintWriter` è già thread-safe da sé, e il
  monitor di `ConnectionHandler` non serve.
- `close()` non è più `synchronized`: usa un `AtomicBoolean` per l'idempotenza e non
  trattiene alcun monitor mentre invoca i metodi di `GameSession`.

Ora l'unica direzione possibile è `Server → GameSession`, senza ritorno.

Questo bug è stato trovato dai test di integrazione (che si bloccavano al teardown),
non a una lettura isolata: con il solo codice di produzione il sintomo sarebbe stato
un server che non si arresta.

## 7. Persistenza degli account

`accounts.txt`, risolto rispetto alla **directory di lavoro corrente**. Otto campi
separati da `:`:

```
username:salt:hash:w:l:d:elo:friends
```

- `salt` — 16 byte da `SecureRandom`, esadecimale
- `hash` — SHA-256 di `salt + ":" + password`
- confronto in **tempo costante** (`MessageDigest.isEqual`); il tempo di risposta non
  distingue "utente inesistente" da "password errata"
- scrittura **atomica**: `accounts.txt.tmp` e poi `Files.move(…, REPLACE_EXISTING)`,
  così un crash a metà scrittura non corrompe il file
- righe con meno di 7 campi vengono **saltate**: gli account con password in chiaro
  non vengono migrati (scelta deliberata, non un oversight)
- ELO: `+15` alla vittoria, `max(100, elo - 15)` alla sconfitta, invariato alla patta.
  Default 1200. **Non è la formula FIDE.**

I contatori sono `volatile`: vengono letti dai thread dei client (comandi `STATS` e
`LOGIN_OK`) senza passare dal lock del manager, che protegge solo la mutazione.

## 8. Test

`mvn test` — 69 test su 4 classi, nessuno richiede un display.

| Classe | Test | Che cosa dimostra |
|---|---:|---|
| `ChessBoardTest` | 40 | Perft, regole speciali, FEN, patte, indipendenza della copia |
| `ChessServerIntegrationTest` | 14 | Due o tre client reali su socket: partita fino a scacco matto, mosse rifiutate, resa, forfeit, patta, stallo, matchmaking, spettatore, chat, login, `accounts.txt` senza password in chiaro |
| `GameSessionDrawTest` | 8 | Le tre patte automatiche e la precedenza del matto sullo stallo |
| `ChessServerTest` | 7 | Smoke test originali, pre-JUnit (ha ancora un `main`) |

### Come sono costruiti i test di integrazione

- Il server parte su **porta 0**: il SO assegna una porta libera, quindi più esecuzioni
  in parallelo o in CI non si scontrano.
- `Server.installInstance(...)` è il seam di test che sostituisce il singleton, così il
  test ottiene un'istanza con porta libera e **file account temporaneo**: i test non
  scrivono mai sull'`accounts.txt` di produzione.
- Ogni client di test è un socket vero con un thread lettore proprio, che accumula i
  messaggi in una `CopyOnWriteArrayList`; `await("PREFISSO")` attende con scadenza.
  Senza il timeout di lettura sul socket, un `SocketTimeoutException` ucciderebbe il
  thread lettore e il client perderebbe i messaggi successivi.

### `GameSessionDrawTest` e le posizioni non raggiungibili

Le tre patte automatiche sono quasi irraggiungibili via protocollo pubblico: nessun
comando permette di caricare un FEN a metà partita, e una partita da K vs K richiederebbe
28 catture. Per questo `GameSession` accetta un **FEN iniziale**:

```java
public GameSession(String sessionId, String roomName, ConnectionHandler host, String initialFen)
```

È un pezzo di API utile di per sé (riprendere una partita salvata) e rende testabili
le regole altrimenti irraggiungibili. Le mosse vengono iniettate chiamando
`processMove`, esattamente come fa `handleCommand` quando arriva un `MOVE`.

## 9. Note su scelte non ovvie

**Perché `Position` ha una cache 8×8.** Le caselle sono oggetti piccoli e usati ovunque,
in particolare dentro i cicli di generazione mosse e di perft. 64 istanti condivise
eliminano l'allocazione da quel percorso caldo. Il costo è che `Position` ha il
costruttore privato e una factory che valida: le uniche 64 caselle valide esistono.

**Perché `isLegalMove` invece di un generatore con ricerca di evasioni.** Il motore
non ha bisogno di listare le evasioni in modo efficiente: la validità di una mossa
dipende solo dal Re di chi muove, mai da una successione di risposte. Con `copy()` la
semantica resta corretta e il codice molto più piccolo.

**Perché le statistiche si aggiornano in un solo punto.** `GameSession.endGame(...)`
è l'unico percorso che chiude una partita. Prima della centralizzazione, resa,
abbandono e patta concordata non chiamavano `recordResult`: l'ELO si muoveva solo per
scacco matto e stallo. I test `resignationUpdatesStats` e
`disconnectForfeitsAndRecordsResult` esistono per coprire esattamente quel buco.

**Perché la coda di matchmaking viene ripulita in `CREATE`.** Senza, un client che
aveva fatto `PLAY` e poi `CREATE` restava in coda: poteva essere abbinato a un
avversario mentre ospitava già una stanza, finendo con lo stesso client in due
partite. `removeFromMatchmaking(this)` in `CREATE` e in `JOIN` chiude la via.
