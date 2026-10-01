#!/usr/bin/env bash
# Avvia il server di scacchi (solo terminale) e, con --client, anche il client grafico.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

PORT="${PORT:-12345}"
CLASSES="target/classes"
WITH_CLIENT=0
SERVER_ARGS=()

usage() {
    cat <<'EOF'
Uso: ./start.sh [opzioni] [-- <argomenti del server>]

  --client        Avvia anche il client grafico (richiede un display)
  --client-only   Avvia solo il client, senza server
  --port <n>      Porta del server (default: 12345, o $PORT)
  --recompile     Forza la ricompilazione
  -h, --help      Mostra questo messaggio

Il server resta in primo piano e si ferma con Ctrl+C.
Gli argomenti dopo -- vengono passati a org.schacchi.Main
(es. ./start.sh -- --port 8080 --no-console).
EOF
}

RECOMPILE=0
CLIENT_ONLY=0
while [ $# -gt 0 ]; do
    case "$1" in
        --client)      WITH_CLIENT=1 ;;
        --client-only) WITH_CLIENT=1; CLIENT_ONLY=1 ;;
        --port)        PORT="$2"; shift ;;
        --recompile)   RECOMPILE=1 ;;
        -h|--help)     usage; exit 0 ;;
        --)            shift; SERVER_ARGS=("$@"); break ;;
        *) echo "Opzione sconosciuta: $1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

if [ "$RECOMPILE" -eq 1 ] || [ ! -f "$CLASSES/org/schacchi/Main.class" ]; then
    echo "[*] Compilazione in corso..."
    rm -rf "$CLASSES"
    mkdir -p "$CLASSES"
    javac -d "$CLASSES" $(find src/main/java -name "*.java")
fi

# Su Wayland/niri senza DISPLAY il client non parte: si avvia Xwayland su :1.
ensure_display() {
    if [ -n "${DISPLAY:-}" ]; then
        return
    fi
    if [ ! -e "/tmp/.X11-unix/X1" ]; then
        echo "[*] Nessun display: avvio Xwayland su :1 per il client grafico..."
        Xwayland :1 >/dev/null 2>&1 &
        sleep 1
    fi
    export DISPLAY=:1
}

server_pid=""
cleanup() {
    if [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null; then
        echo "[*] Arresto del server..."
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

if [ "$CLIENT_ONLY" -eq 0 ]; then
    # args espliciti hanno la precedenza su --port, che altrimenti passerebbe due volte
    if [ ${#SERVER_ARGS[@]} -eq 0 ] && [ "${PORT:-12345}" != "12345" ]; then
        SERVER_ARGS=(--port "$PORT")
    fi
    echo "[*] Avvio del server (porta $PORT). Ctrl+C per fermarlo."

    if [ "$WITH_CLIENT" -eq 0 ]; then
        # Solo server: si esegue in primo piano. In background la shell di
        # script ridirige stdin da /dev/null e la console interattiva del server
        # (che si attiva solo se stdin e' un terminale) non partirebbe mai.
        exec java -cp "$CLASSES" org.schacchi.Main "${SERVER_ARGS[@]}"
    fi

    java -cp "$CLASSES" org.schacchi.Main "${SERVER_ARGS[@]}" &
    server_pid=$!
    # Dato che il server parte in background, si attende che sia in ascolto: se il
    # client si connettesse subito fallirebbe a intermittenza.
    for _ in $(seq 1 50); do
        if ! kill -0 "$server_pid" 2>/dev/null; then
            echo "[!] Il server si e' chiuso in avvio." >&2
            exit 1
        fi
        if ss -tln 2>/dev/null | grep -q ":${PORT}\b"; then
            break
        fi
        sleep 0.1
    done
fi

if [ "$WITH_CLIENT" -eq 1 ]; then
    ensure_display
    echo "[*] Avvio del client su $DISPLAY:$(hostname 2>/dev/null || echo localhost)"
    exec java -cp "$CLASSES" org.schacchi.ClientMain
fi

# Solo server: si resta in primo piano e si esce con lo stesso codice del server.
if [ -n "$server_pid" ]; then
    wait "$server_pid"
fi
