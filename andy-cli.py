#!/usr/bin/env python3
"""
andy-cli - Desktop CLI for AndyClaw

Talk to your AndyClaw AI agent on your phone from a desktop terminal.
Uses the WebSocket API exposed by CliApiServer.

Setup:
    1. In AndyClaw on your phone: Settings > CLI Access > Enable
    2. Note the port (default 8642) and copy the bearer token
    3. Make sure phone and desktop are on the same network
       (or use `adb forward tcp:8642 tcp:8642` over USB)

Usage:
    # Interactive mode (REPL)
    python andy-cli.py --host 192.168.1.42 --token YOUR_TOKEN

    # Single message
    python andy-cli.py --host 192.168.1.42 --token YOUR_TOKEN -m "check my battery"

    # List sessions
    python andy-cli.py --host 192.168.1.42 --token YOUR_TOKEN --sessions

    # Resume a session
    python andy-cli.py --host 192.168.1.42 --token YOUR_TOKEN --session-id SESSION_ID

Dependencies:
    pip install websocket-client requests
"""

import argparse
import json
import sys
import threading

try:
    import websocket
except ImportError:
    print("Missing dependency: pip install websocket-client", file=sys.stderr)
    sys.exit(1)

try:
    import requests
except ImportError:
    requests = None

BLUE = "\033[34m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
RED = "\033[31m"
DIM = "\033[2m"
BOLD = "\033[1m"
RESET = "\033[0m"


def list_sessions(base_url: str, token: str):
    if requests is None:
        print("Missing dependency for --sessions: pip install requests", file=sys.stderr)
        sys.exit(1)
    resp = requests.get(
        f"{base_url}/api/sessions",
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )
    resp.raise_for_status()
    sessions = resp.json()
    if not sessions:
        print("No sessions found.")
        return
    for s in sessions:
        print(f"  {DIM}{s['id'][:8]}...{RESET}  {s['title']}")


def show_session(base_url: str, token: str, session_id: str):
    if requests is None:
        print("Missing dependency for --session-id: pip install requests", file=sys.stderr)
        sys.exit(1)
    resp = requests.get(
        f"{base_url}/api/sessions/{session_id}",
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )
    resp.raise_for_status()
    messages = resp.json()
    for m in messages:
        role = m["role"]
        content = m["content"]
        if role == "user":
            print(f"\n{BOLD}You:{RESET} {content}")
        elif role == "assistant":
            print(f"\n{GREEN}AI:{RESET} {content}")
        elif role == "tool":
            name = m.get("tool_name", "tool")
            print(f"  {DIM}[{name}]{RESET} {content[:200]}")


def run_websocket(host: str, port: int, token: str, session_id: str = None,
                  single_message: str = None):
    url = f"ws://{host}:{port}/ws?token={token}"
    done_event = threading.Event()
    current_session_id = session_id

    def on_message(ws, message):
        nonlocal current_session_id
        try:
            msg = json.loads(message)
        except json.JSONDecodeError:
            return

        msg_type = msg.get("type")

        if msg_type == "session":
            current_session_id = msg.get("session_id")

        elif msg_type == "token":
            print(msg.get("text", ""), end="", flush=True)

        elif msg_type == "tool_start":
            name = msg.get("name", "unknown")
            print(f"\n  {YELLOW}[running {name}...]{RESET}", flush=True)

        elif msg_type == "tool_result":
            name = msg.get("name", "tool")
            data = msg.get("data", "")
            is_error = msg.get("is_error", False)
            color = RED if is_error else DIM
            preview = data[:300] + ("..." if len(data) > 300 else "")
            print(f"  {color}[{name}] {preview}{RESET}", flush=True)

        elif msg_type == "approval_needed":
            desc = msg.get("description", "")
            print(f"\n  {YELLOW}Approval needed:{RESET} {desc}")
            try:
                answer = input(f"  {BOLD}Approve? [y/n]:{RESET} ").strip().lower()
            except EOFError:
                answer = "n"
            ws.send(json.dumps({"type": "approval", "approved": answer in ("y", "yes")}))

        elif msg_type == "complete":
            print(flush=True)
            if single_message:
                done_event.set()
                ws.close()

        elif msg_type == "error":
            print(f"\n{RED}Error: {msg.get('message', 'unknown')}{RESET}", flush=True)
            if single_message:
                done_event.set()
                ws.close()

    def on_open(ws):
        payload = {"type": "chat", "message": single_message or ""}
        if current_session_id:
            payload["session_id"] = current_session_id

        if single_message:
            ws.send(json.dumps(payload))
        else:
            def input_loop():
                try:
                    while True:
                        user_input = input(f"\n{BOLD}You:{RESET} ")
                        if not user_input.strip():
                            continue
                        if user_input.strip().lower() in ("exit", "quit", "/q"):
                            ws.close()
                            break
                        msg = {"type": "chat", "message": user_input}
                        if current_session_id:
                            msg["session_id"] = current_session_id
                        ws.send(json.dumps(msg))
                        print(f"\n{GREEN}AI:{RESET} ", end="", flush=True)
                except (EOFError, KeyboardInterrupt):
                    print()
                    ws.close()

            threading.Thread(target=input_loop, daemon=True).start()

        if single_message:
            print(f"{GREEN}AI:{RESET} ", end="", flush=True)

    def on_error(ws, error):
        print(f"\n{RED}Connection error: {error}{RESET}", file=sys.stderr)
        done_event.set()

    def on_close(ws, close_status_code, close_msg):
        done_event.set()

    ws = websocket.WebSocketApp(
        url,
        on_open=on_open,
        on_message=on_message,
        on_error=on_error,
        on_close=on_close,
    )

    if single_message:
        wst = threading.Thread(target=ws.run_forever, daemon=True)
        wst.start()
        done_event.wait(timeout=120)
    else:
        print(f"{DIM}Connected to AndyClaw at {host}:{port}{RESET}")
        print(f"{DIM}Type your message and press Enter. 'exit' to quit.{RESET}")
        print(f"\n{GREEN}AI:{RESET} ", end="", flush=True)
        try:
            ws.run_forever()
        except KeyboardInterrupt:
            pass


def main():
    parser = argparse.ArgumentParser(
        description="Talk to AndyClaw on your phone from the desktop terminal.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--host", required=True, help="Phone IP address or hostname")
    parser.add_argument("--port", type=int, default=8642, help="CLI server port (default: 8642)")
    parser.add_argument("--token", required=True, help="Bearer token from AndyClaw settings")
    parser.add_argument("-m", "--message", help="Send a single message and exit")
    parser.add_argument("--sessions", action="store_true", help="List chat sessions")
    parser.add_argument("--session-id", help="Resume a specific session")
    parser.add_argument("--show", help="Show messages from a session ID")
    args = parser.parse_args()

    base_url = f"http://{args.host}:{args.port}"

    if args.sessions:
        list_sessions(base_url, args.token)
        return

    if args.show:
        show_session(base_url, args.token, args.show)
        return

    run_websocket(
        host=args.host,
        port=args.port,
        token=args.token,
        session_id=args.session_id,
        single_message=args.message,
    )


if __name__ == "__main__":
    main()
