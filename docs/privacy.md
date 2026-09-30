# Informativa privacy — Server Scacchi `game.cristianrenosto.party`

> ⚠️ **DOCUMENTO DA COMPLETARE PRIMA DELLA PUBBLICAZIONE.**
> I campi marcati **[DA COMPILARE]** richiedono dati che solo il titolare del
> trattamento conosce (identità, recapiti, DPO, hosting provider). Il testo
> è tecnicamente accurato ma non è un'informativa privacy valida finché quei
> campi restano vuoti. Questo documento è un template, non un adempimento.

Base di lavoro: Regolamento (UE) 2016/679 (GDPR), articoli 4, 6, 8, 13, 15-22, 30, 32, 33.

---

## 1. Titolare del trattamento

| Campo | Valore |
|---|---|
| Denominazione / nome | **[DA COMPILARE]** |
| Indirizzo | **[DA COMPILARE]** |
| Email di contatto | **[DA COMPILARE]** |
| Responsabile della protezione dei dati (DPO) | **[DA COMPILARE — oppure "non designato" con motivazione]** |

## 2. Responsabili esterni e trasferimenti extra SEE

| Ruolo | Soggetto | Dati trattati | Sede |
|---|---|---|---|
| Hosting del server | **[DA COMPILARE]** | tutto il traffico | **[DA COMPILARE]** |
| Dominio e DNS | **[DA COMPILARE]** | indirizzi IP di risoluzione | **[DA COMPILARE]** |

**Trasferimenti extra SEE:** se hosting o DNS sono fuori dallo Spazio Economico
Europeo, occorre dichiararlo e indicare la garanzia adottata (art. 44-49 GDPR:
decisioni di adeguatezza, Clausole Contrattuali Standard o misure supplementari).
**[DA COMPILARE]**

## 3. Dati trattati e finalità (art. 13.1.c)

| Dato | Finalità | Base giuridica | Dove vive | Conservazione |
|---|---|---|---|---|
| Username | identificare il giocatore nelle partite | art. 6.1.b | `accounts.txt` | finché l'account resta attivo |
| Password | autenticazione | art. 6.1.b | mai scritta in chiaro; solo derivata PBKDF2 in `accounts.txt` | finché l'account resta attivo |
| Età dichiarata | adempiere all'art. 8 GDPR | art. 6.1.c (obbligo legale) | `accounts.txt` | finché l'account resta attivo |
| Statistiche (V/S/P, ELO) | ranking e statistiche di gioco | art. 6.1.b | `accounts.txt` | finché l'account resta attivo |
| Lista amici | funzione sociale del servizio | art. 6.1.a (consenso) | `accounts.txt` | finché l'account resta attivo |
| Messaggi di chat | comunicazione durante la partita | art. 6.1.b | **non conservati**: inoltrati ai soli partecipanti | — |
| Indirizzo IP | **non trattato**: non viene scritto nei log del server | — | — | — |

### Cosa NON viene raccolto

- **Nessun indirizzo IP nei log.** Il server non scrive `getRemoteSocketAddress()`
  da nessuna parte. Di conseguenza non esiste un log di connessioni consultabile.
- **Nessuna chat archiviata.** I messaggi vivono in memoria per il tempo della
  partita e spariscono alla disconnessione.
- **Nessun cookie, nessun fingerprinting, nessun profilo pubblico oltre il nickname**
  che l'utente sceglie e che è visibile agli altri giocatori durante le partite.

### Il nickname è dato personale

Il nome scelto dall'utente compare nelle liste stanze, nei messaggi `GAME_START`
e nella chat della partita: altri giocatori lo vedono. L'informativa deve dirlo
esplicitamente, e l'utente deve scegliere un nickname non identificativo.

## 4. Base giuridica (art. 6.1)

- **art. 6.1.b — esecuzione del contratto**: username, statistiche, ELO, chat.
  Senza questi dati il servizio non può funzionare.
- **art. 6.1.c — obbligo legale**: età dichiarata, per adempiere all'art. 8.
- **art. 6.1.a — consenso**: lista amici. È revocabile in qualsiasi momento.

## 5. Minori (art. 8)

Il GDPR fissa a **16 anni** la soglia per il consenso autonomo ai servizi online,
lasciando agli Stati membri la facoltà di abbassarla **fino a 13 anni**.

- **Età minima ammessa: 13 anni.** È il limite più restrittivo consentito dal regolamento.
- **Da 13 a 15 anni**: l'uso è lecito, ma **subordinato al consenso del titolare
  della responsabilità genitoriale**. Il server notifica questa condizione al momento
  della registrazione.
- **Da 16 anni**: consenso autonomo.
- Sotto i 13 anni la registrazione viene rifiutata dal server.

> **Limitazione da dichiarare nell'informativa:** l'età è **dichiarata dall'utente e
> non verificata**. Il controllo è solo dichiarativo: un minore può dichiarare
> un'età falsa. Un servizio rivolto ai minori dovrebbe prevedere una verifica
> dell'età o un meccanismo di segnalazione da parte dei genitori. **[DA VALUTARE]**

## 6. I diritti dell'interessato (art. 15-22)

Il client espone questi diritti in una tab dedicata ("I Miei Dati"). Via protocollo:

| Diritto | Comando | Art. |
|---|---|---|
| Accesso e portabilità | `EXPORT_DATA` | 15, 20 |
| Cancellazione | `DELETE_ACCOUNT <password>` | 17 |
| Chiusura sessione | `LOGOUT` | — |
| Limitazione, opposizione, rettifica | **non implementati** | 18, 21, 16 |

**Dati in formato leggibile e machine-readable**: `EXPORT_DATA` restituisce righe
`chiave: valore` separate da `\n`, senza dato superfluo. La traccia della password
è esclusa perché non è dato dell'interessato ma segreto tecnico del sistema.

**Cancellazione**: rimuove account, statistiche, ELO, età dichiarata e lista amici,
**e anche i riferimenti al nome cancellato nelle liste amici degli altri utenti** —
altrimenti il nome sopravvivrebbe nei dati di altri interessati. Richiede la password
per impedire che una sessione lasciata aperta su un computer condiviso consenta a un
terzo di cancellare un account altrui. Irreversibile.

**Rettifica**: il nome si cambia con `NAME`; le statistiche non sono modificabili
dall'utente per scelta di progetto. **[DA VALUTARE se conforme alle tue esigenze]**

**Reclamo**: all'Autorità di controllo competente (per l'Italia:
Garante per la protezione dei dati personali, `garanteprivacy.it`).

## 7. Sicurezza (art. 32)

| Minaccia | Misura |
|---|---|
| Accesso al file delle password | derivata **PBKDF2-HMAC-SHA256, 600 000 iterazioni** (OWASP), sale 16 byte per utente, confronto in tempo costante |
| Furto di `accounts.txt` | file in chiaro solo se il processo ha i permessi: **cifratura a riposo NON implementata** |
| Password in transito | **NESSUNA**: il protocollo è TCP in chiaro |
| Sovrascrittura del file | scrittura atomica: file temporaneo + `Files.move(REPLACE_EXISTING)` |
| Esaurimento risorse | pool di thread a dimensione fissa, tetto di connessioni (default 200) |
| Connessioni zombie | timeout di inattività sui socket, impostabile con `SOCKET_TIMEOUT_MS` |
| Corruzione da concorrenza | registrazione/autenticazione sincronizzate sul manager |

### Non conformità note (art. 32)

1. 🔴 **Traffico TCP in chiaro.** Le password passano in chiaro sul filo. Un
   attaccante sulla rete li intercetta. **Da risolvere con TLS**, che è una misura
   tecnica e non può essere simulata nel codice applicativo.
2. 🟠 **Nessuna cifratura a riposo** di `accounts.txt`. Chi legge il file ottiene
   username, età, statistiche e liste amici. Le password sono protette dalla KDF,
   gli altri dati no.
3. 🟠 **Nessuna procedura di notifica dei breach** (art. 33): non esiste un piano
   documentato di notifica entro 72 ore al Garante. **[DA COMPILARE]**
4. 🟡 Nessun `Content-Security-Policy`/`HSTS`: irrelevante finché il traffico è
   TCP proprietario, diventa necessario quando si passa a HTTPS/WSS.

## 8. Registro delle attività di trattamento (art. 30)

| Trattamento | Categorie di interessati | Categorie di dati | Destinatari | Trasferimenti | Conservazione | Misure |
|---|---|---|---|---|---|---|
| Gestione account | utenti registrati | username, età dichiarata, statistiche, ELO | nessuno | nessuno | fino a revoca | PBKDF2, lock, file con permessi ristretti |
| Lista amici | utenti registrati | relazioni fra username | altri utenti registrati (vedono stato online) | nessuno | fino a revoca | rimozione reciproca |
| Chat in partita | giocatori della partita | testo del messaggio | solo i partecipanti alla partita | nessuno | non conservata | non persistita |
| Autenticazione | utenti registrati | password | nessuno | nessuno | — | PBKDF2 600k, confronto costante |

## 9. Come esercitare i diritti fuori dal client

Se un utente non può usare il client (per esempio ha perso la password), i diritti
si esercitano scrivendo al titolare. Serve una procedura documentata per verificare
l'identità del richiedente prima di cancellare un account, pena la cancellazione
per errore di persona altrui. **[DA COMPILARE]**

## 10. Decisioni aperte

- [ ] **TLS.** Il punto più grave. Necessario prima di esporre il servizio.
- [ ] **Verifica dell'età** dei minori, o almeno una procedura di segnalazione genitoriale.
- [ ] **Cifratura a riposo** di `accounts.txt`, o almeno permessi file restrittivi.
- [ ] **Procedura di notifica breach** (art. 33) e di aggiornamento delle informative (art. 12).
- [ ] **Rettifica delle statistiche** se l'utente le ritiene non corrette.
- [ ] **Verifica legale** del testo da parte di un professionista prima della pubblicazione.

> Gli strumenti tecnici esistono e sono testati; gli obblighi documentali restano
> quasi interamente da svolgere. Questo progetto **non è a norma GDPR** finché i
> campi **[DA COMPILARE]** e le non conformità elencate sopra restano aperti.
