"""
Rauchtest für das ganze System: läuft gegen ein gestartetes "docker compose up".

Er macht genau das, was man sonst von Hand im Browser prüft:
1. als alice über Keycloak anmelden (echter Login, echtes Formular),
2. die Räume abfragen,
3. per WebSocket in der Lobby eine Nachricht senden,
4. prüfen, dass sie über den Zustellweg zurückkommt,
5. prüfen, dass bob im Raum M321 sie NICHT bekommt (Zustellung nach Raum),
6. prüfen, dass sie im Verlauf steht (batch-writer hat sie gespeichert),
7. prüfen, dass nur admin die Queue-Tiefe sieht,
8. den Weg des Desktop-Clients gehen: Login mit PKCE, Bearer-Token für
   REST und WebSocket.

Aufruf:  python scripts/smoke-test.py
Braucht: pip install requests websocket-client
"""

import base64
import hashlib
import html
import json
import os
import random
import re
import sys
import time
import urllib.parse
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
    check_desktop_client_path()
    print("Rauchtest bestanden")


def desktop_login(username, password):
    """
    Meldet sich an wie der Desktop-Client (Schritt 7): öffentlicher Client
    "desktop-client" ohne Secret, PKCE, Rückleitung auf 127.0.0.1 mit
    zufälligem Port (RFC 8252). Den kleinen Webserver des Clients braucht
    es hier nicht: wir lesen den Code direkt aus der Weiterleitung.
    """
    verifier = base64.urlsafe_b64encode(os.urandom(32)).rstrip(b"=").decode()
    digest = hashlib.sha256(verifier.encode()).digest()
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode()
    state = str(uuid.uuid4())
    port = random.randint(40000, 60000)
    redirect_uri = "http://127.0.0.1:" + str(port) + "/callback"

    session = requests.Session()
    parameters = {
        "client_id": "desktop-client",
        "response_type": "code",
        "scope": "openid profile",
        "redirect_uri": redirect_uri,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "state": state,
    }
    realm_url = BASE_URL + "/auth/realms/chat/protocol/openid-connect"
    login_page = session.get(realm_url + "/auth", params=parameters, timeout=10)
    match = re.search(r'<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"', login_page.text)
    if match is None:
        fail("Keycloak zeigt dem Desktop-Client kein Login (HTTP " + str(login_page.status_code) + "): "
             + login_page.text[:300])
    form_action = html.unescape(match.group(1))

    after_login = session.post(form_action, data={"username": username, "password": password},
                               allow_redirects=False, timeout=10)
    location = after_login.headers.get("Location", "")
    if not location.startswith(redirect_uri):
        fail("Keine Rückleitung auf " + redirect_uri + ", sondern: " + location)
    query = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)
    if query["state"][0] != state:
        fail("state stimmt nicht")
    code = query["code"][0]

    token_response = requests.post(realm_url + "/token", data={
        "grant_type": "authorization_code",
        "client_id": "desktop-client",
        "code": code,
        "redirect_uri": redirect_uri,
        "code_verifier": verifier,
    }, timeout=10)
    if token_response.status_code != 200:
        fail("Token-Tausch fehlgeschlagen: " + token_response.text)
    print("Desktop-Login mit PKCE und Rückleitung auf Port", port, "erfolgreich")
    return token_response.json()["access_token"]


def check_desktop_client_path():
    """Der Weg des Desktop-Clients: Bearer-Token statt Cookie, für REST und WebSocket."""
    access_token = desktop_login("bob", "bob")
    bearer = {"Authorization": "Bearer " + access_token, "Accept": "application/json"}

    me = requests.get(BASE_URL + "/api/me", headers=bearer, timeout=10)
    if me.status_code != 200 or me.json()["username"] != "bob":
        fail("/api/me mit Bearer-Token: HTTP " + str(me.status_code))
    rooms = requests.get(BASE_URL + "/api/rooms", headers=bearer, timeout=10)
    if rooms.status_code != 200 or len(rooms.json()) != 3:
        fail("/api/rooms mit Bearer-Token: HTTP " + str(rooms.status_code))

    socket_url = "ws://localhost:8080/ws/chat?roomId=" + M321_ROOM_ID
    socket = websocket.create_connection(socket_url, header=["Authorization: Bearer " + access_token],
                                         timeout=10)
    content = "Desktop-Rauchtest " + str(uuid.uuid4())
    socket.send(json.dumps({"roomId": M321_ROOM_ID, "content": content}))
    events = receive_events(socket, 5)
    socket.close()

    delivered = None
    for event in events:
        if event["type"] == "message" and event["payload"]["content"] == content:
            delivered = event["payload"]
    if delivered is None:
        fail("Nachricht des Desktop-Clients kam nicht zurück: " + str(events))
    if delivered["senderName"] != "Bob Beispiel":
        fail("Absender aus dem Bearer-Token stimmt nicht: " + delivered["senderName"])
    print("Desktop-Weg: /api/me, /api/rooms und WebSocket mit Bearer-Token funktionieren")

    unauthorized = requests.get(BASE_URL + "/api/rooms",
                                headers={"Authorization": "Bearer kaputt", "Accept": "application/json"},
                                timeout=10)
    if unauthorized.status_code != 401:
        fail("ungültiges Bearer-Token sollte 401 geben, war " + str(unauthorized.status_code))


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
