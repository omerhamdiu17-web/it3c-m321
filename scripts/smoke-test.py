"""
Rauchtest für das ganze System: läuft gegen ein gestartetes "docker compose up".

Er macht genau das, was man sonst von Hand im Browser prüft:
1. als alice über Keycloak anmelden (echter Login, echtes Formular),
2. die Räume abfragen,
3. per WebSocket in der Lobby eine Nachricht senden,
4. prüfen, dass sie über den Zustellweg zurückkommt,
5. prüfen, dass bob im Raum M321 sie NICHT bekommt (Zustellung nach Raum),
6. prüfen, dass sie im Verlauf steht (batch-writer hat sie gespeichert),
7. prüfen, dass nur admin die Queue-Tiefe sieht.

Aufruf:  python scripts/smoke-test.py
Braucht: pip install requests websocket-client
"""

import html
import json
import re
import sys
import time
import uuid

import requests
import websocket

BASE_URL = "http://localhost:8080"
LOBBY_ROOM_ID = "00000000-0000-0000-0000-000000000001"
M321_ROOM_ID = "00000000-0000-0000-0000-000000000002"


def wait_for_keycloak():
    """Keycloak braucht beim ersten Start rund 30 Sekunden. So lange antwortet /auth mit einem Fehler."""
    url = BASE_URL + "/auth/realms/chat/.well-known/openid-configuration"
    for attempt in range(90):
        try:
            response = requests.get(url, timeout=5)
            if response.status_code == 200:
                print("Keycloak ist bereit")
                return
        except requests.RequestException:
            pass
        time.sleep(2)
    fail("Keycloak ist nach 3 Minuten nicht bereit")


def login(username, password):
    """
    Meldet einen Benutzer an wie ein Browser: das Gateway leitet zu Keycloak
    weiter, wir füllen das Login-Formular aus, Keycloak leitet mit einem Code
    zurück, das Gateway tauscht ihn gegen ein Token und setzt sein Cookie.
    """
    session = requests.Session()
    login_page = session.get(BASE_URL + "/api/me", timeout=10)
    match = re.search(r'<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"', login_page.text)
    if match is None:
        fail("Login-Formular von Keycloak nicht gefunden (HTTP " + str(login_page.status_code) + ")")
    form_action = html.unescape(match.group(1))

    form_data = {"username": username, "password": password}
    after_login = session.post(form_action, data=form_data, timeout=10)
    if after_login.status_code != 200:
        fail("Login als " + username + " fehlgeschlagen (HTTP " + str(after_login.status_code) + ")")

    current_user = after_login.json()
    print("Angemeldet als", current_user["username"], "/", current_user["displayName"])
    return session


def open_socket(session, room_id):
    """Öffnet die WebSocket-Verbindung für einen Raum, mit dem Session-Cookie des Logins."""
    cookie_parts = []
    for cookie in session.cookies:
        if cookie.name == "JSESSIONID":
            cookie_parts.append(cookie.name + "=" + cookie.value)
    cookie_header = "; ".join(cookie_parts)
    socket_url = "ws://localhost:8080/ws/chat?roomId=" + room_id
    return websocket.create_connection(socket_url, cookie=cookie_header, timeout=10)


def receive_events(socket, seconds):
    """Sammelt alles, was in der angegebenen Zeit über den WebSocket kommt."""
    events = []
    deadline = time.time() + seconds
    while time.time() < deadline:
        socket.settimeout(max(0.1, deadline - time.time()))
        try:
            text = socket.recv()
        except websocket.WebSocketTimeoutException:
            break
        events.append(json.loads(text))
    return events


def wait_for_history(session, room_id, content):
    """Fragt den Verlauf ab, bis die Nachricht darin steht (der batch-writer schreibt in Stapeln)."""
    url = BASE_URL + "/api/rooms/" + room_id + "/messages"
    for attempt in range(30):
        history = session.get(url, timeout=10).json()
        for message in history:
            if message["content"] == content:
                return message
        time.sleep(0.5)
    return None


def fail(reason):
    print("FEHLER:", reason)
    sys.exit(1)


def main():
    wait_for_keycloak()

    alice = login("alice", "alice")
    bob = login("bob", "bob")

    rooms = alice.get(BASE_URL + "/api/rooms", timeout=10).json()
    room_names = []
    for room in rooms:
        room_names.append(room["name"])
    print("Räume:", room_names)
    if room_names != ["Lobby", "M321", "Lasttest"]:
        fail("unerwartete Räume")

    alice_socket = open_socket(alice, LOBBY_ROOM_ID)
    bob_socket = open_socket(bob, M321_ROOM_ID)

    content = "Rauchtest " + str(uuid.uuid4())
    outgoing = {"roomId": LOBBY_ROOM_ID, "content": content}
    alice_socket.send(json.dumps(outgoing))

    alice_events = receive_events(alice_socket, 5)
    event_types = []
    delivered = None
    for event in alice_events:
        event_types.append(event["type"])
        if event["type"] == "message" and event["payload"]["content"] == content:
            delivered = event["payload"]
    print("alice bekam:", event_types)
    if "accepted" not in event_types:
        fail("keine Bestätigung vom chat-service")
    if delivered is None:
        fail("Nachricht kam nicht über den Zustellweg zurück")
    if delivered["senderName"] != "Alice Muster":
        fail("Absender stimmt nicht: " + delivered["senderName"])

    bob_events = receive_events(bob_socket, 2)
    print("bob (Raum M321) bekam:", len(bob_events), "Ereignisse")
    if len(bob_events) != 0:
        fail("bob im Raum M321 hat eine Lobby-Nachricht bekommen")

    stored = wait_for_history(bob, LOBBY_ROOM_ID, content)
    if stored is None:
        fail("Nachricht steht nicht im Verlauf (batch-writer?)")
    print("im Verlauf gespeichert:", stored["id"], stored["sentAt"])

    alice_socket.close()
    bob_socket.close()

    check_queue_stats_only_for_admin(alice)
    print("Rauchtest bestanden")


def check_queue_stats_only_for_admin(normal_user):
    """Die Queue-Tiefe sieht nur die Rolle admin (PLANUNG.md, offener Punkt 6)."""
    url = BASE_URL + "/api/admin/queue"

    forbidden = normal_user.get(url, timeout=10)
    print("alice fragt nach der Queue-Tiefe: HTTP", forbidden.status_code)
    if forbidden.status_code != 403:
        fail("alice darf die Queue-Tiefe nicht sehen")

    admin = login("admin", "admin")
    me = admin.get(BASE_URL + "/api/me", timeout=10).json()
    if me["admin"] is not True:
        fail("admin hat die Rolle admin nicht im Token")
    stats = admin.get(url, timeout=10).json()
    print("admin sieht:", stats)
    if stats["queueName"] != "chat.persist" or stats["consumers"] < 1:
        fail("Queue-Tiefe unvollständig: kein batch-writer an chat.persist?")


if __name__ == "__main__":
    main()
