# Experimente für die Spezifikation (M321 Chat-App)

Datum: 2026-10-02 · Umgebung: Windows 11, Git Bash (`MSYS_NO_PATHCONV=1`), Docker Engine 29.3.1, Compose v5.1.1.
Laufender Stack `it3c-m321` (Netz `chat-net`: rabbitmq 3.13-management, chat-service, postgres 16, batch-writer ×2) wurde nicht verändert.
Eigene Container `exp-*`, eigene Queue `exp.*`; Images `quay.io/keycloak/keycloak:26.7.3` und `curlimages/curl:latest` lagen schon lokal (kein Pull während der Zeitmessung).

In den Befehlen steht `$W` für den Arbeitsordner
`C:/Users/hamdi/AppData/Local/Temp/claude/c--Users-hamdi-Documents-M321/629d6dde-64cf-4fd8-a74d-bdea0f8a52fe/scratchpad/spec-experimente`
und `C` für `docker run --rm --network chat-net curlimages/curl` (Hilfscontainer, als Bash-Funktion definiert).
Ausgaben sind gekürzt (`…`), aber nicht verändert. Tokens sind gekürzt.

## E1 – Platzhalter im Realm-Import von Keycloak 26.7.3

Realm-Datei `$W/realm-exp.json` (vollständig; `carol` ist ein Zusatz ohne `email`, um zu sehen, ob eine E-Mail nötig ist – deshalb hat alice eine E-Mail, damit der Platzhaltertest nicht davon abhängt):

```json
{
  "realm": "chat",
  "enabled": true,
  "clients": [
    {
      "clientId": "web-gateway",
      "enabled": true,
      "protocol": "openid-connect",
      "publicClient": false,
      "clientAuthenticatorType": "client-secret",
      "secret": "${EXP_CLIENT_SECRET}",
      "redirectUris": ["${EXP_PUBLIC_URL}/login/oauth2/code/keycloak"],
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": true,
      "serviceAccountsEnabled": true
    }
  ],
  "users": [
    {
      "username": "alice",
      "enabled": true,
      "email": "alice@example.org",
      "emailVerified": true,
      "firstName": "Alice",
      "lastName": "Exp",
      "credentials": [
        { "type": "password", "value": "${EXP_DEMO_PASSWORD}", "temporary": false }
      ]
    },
    {
      "username": "bob",
      "enabled": true,
      "email": "bob@example.org",
      "emailVerified": true,
      "firstName": "Bob",
      "lastName": "Exp",
      "credentials": [
        { "type": "password", "value": "${EXP_UNSET:bob-vorgabe}", "temporary": false }
      ]
    },
    {
      "username": "carol",
      "enabled": true,
      "emailVerified": true,
      "firstName": "Carol",
      "lastName": "OhneMail",
      "credentials": [
        { "type": "password", "value": "${EXP_DEMO_PASSWORD}", "temporary": false }
      ]
    }
  ]
}
```

Start und Warten (Obergrenze 60 Versuche):

```bash
C() { docker run --rm --network chat-net curlimages/curl "$@"; }
T0=$(date +%s)
docker run -d --name exp-kc --network chat-net \
  --mount type=bind,source="$W/realm-exp.json",target=/opt/keycloak/data/import/realm-chat.json,readonly \
  -e EXP_CLIENT_SECRET=geheim-aus-env \
  -e EXP_PUBLIC_URL=http://localhost:8080 \
  -e EXP_DEMO_PASSWORD=pw-aus-env \
  -e KC_HTTP_RELATIVE_PATH=/auth \
  -e KC_HOSTNAME=http://localhost:8080/auth \
  -e KC_HEALTH_ENABLED=true \
  -e KC_PROXY_HEADERS=xforwarded \
  quay.io/keycloak/keycloak:26.7.3 start-dev --import-realm
for i in $(seq 1 60); do
  code=$(docker run --rm --network chat-net curlimages/curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://exp-kc:8080/auth/realms/chat/.well-known/openid-configuration)
  echo "Versuch $i nach $(( $(date +%s) - T0 )) s: HTTP $code"
  [ "$code" = "200" ] && break
  sleep 2
done
```

```
T0=1790932585 (11:16:25)
61eade95c4cdbcbc9b2499f41420651ed2130aeee24deb0bb0300a052f17461d
Versuch 1 nach 2 s: HTTP 000
…
Versuch 9 nach 28 s: HTTP 000
Versuch 10 nach 32 s: HTTP 200
erste 200 nach 32 s
```

`docker logs exp-kc` (Auszug, Zeiten UTC; Container gestartet 09:16:25):

```
2026-10-02 09:16:37,879 INFO  [io.quarkus.deployment.QuarkusAugmentor] (main) Quarkus augmentation completed in 9155ms
Running the server in development mode. DO NOT use this configuration in production.
2026-10-02 09:16:40,714 INFO  [org.keycloak.url.HostnameV2ProviderFactory] (main) If hostname is specified, hostname-strict is effectively ignored
…
2026-10-02 09:16:51,502 INFO  [org.keycloak.exportimport.dir.DirImportProvider] (main) Importing from directory /opt/keycloak/bin/../data/import
2026-10-02 09:16:51,681 INFO  [org.keycloak.services] (main) KC-SERVICES0050: Initializing master realm
2026-10-02 09:16:53,336 INFO  [org.keycloak.exportimport.singlefile.SingleFileImportProvider] (main) Full importing from file /opt/keycloak/bin/../data/import/realm-chat.json
2026-10-02 09:16:54,840 INFO  [org.keycloak.exportimport.util.ImportUtils] (main) Realm 'chat' imported
2026-10-02 09:16:54,841 INFO  [org.keycloak.services] (main) KC-SERVICES0030: Full model import requested. Strategy: IGNORE_EXISTING
2026-10-02 09:16:54,841 INFO  [org.keycloak.services] (main) KC-SERVICES0032: Import finished successfully
2026-10-02 09:16:55,147 INFO  [org.keycloak.services.resources.KeycloakApplication] (main) Bootstrap completed in 11.211000 seconds
2026-10-02 09:16:55,345 INFO  [io.quarkus] (main) Keycloak 26.7.3 on JVM (powered by Quarkus 3.33.3.1) started in 17.330s. Listening on: http://0.0.0.0:8080. Management interface listening on http://0.0.0.0:9000.
2026-10-02 09:16:55,345 INFO  [io.quarkus] (main) Profile dev activated.
```

Token-Tests (`TOK=http://exp-kc:8080/auth/realms/chat/protocol/openid-connect/token`):

```bash
C -s --max-time 10 -w '\nHTTP %{http_code}\n' -X POST $TOK -d grant_type=client_credentials -d client_id=web-gateway --data-urlencode client_secret=geheim-aus-env
C -s --max-time 10 -w '\nHTTP %{http_code}\n' -X POST $TOK -d grant_type=client_credentials -d client_id=web-gateway --data-urlencode 'client_secret=${EXP_CLIENT_SECRET}'
C -s --max-time 10 -w '\nHTTP %{http_code}\n' -X POST $TOK -d grant_type=password -d client_id=web-gateway --data-urlencode client_secret=geheim-aus-env -d username=alice --data-urlencode password=pw-aus-env
C -s --max-time 10 -w '\nHTTP %{http_code}\n' -X POST $TOK -d grant_type=password -d client_id=web-gateway --data-urlencode client_secret=geheim-aus-env -d username=alice --data-urlencode 'password=${EXP_DEMO_PASSWORD}'
C -s --max-time 10 -w '\nHTTP %{http_code}\n' -X POST $TOK -d grant_type=password -d client_id=web-gateway --data-urlencode client_secret=geheim-aus-env -d username=bob --data-urlencode password=bob-vorgabe
C -s --max-time 10 -w '\nHTTP %{http_code}\n' -X POST $TOK -d grant_type=password -d client_id=web-gateway --data-urlencode client_secret=geheim-aus-env -d username=bob --data-urlencode 'password=${EXP_UNSET:bob-vorgabe}'
C -s --max-time 10 -w '\nHTTP %{http_code}\n' -X POST $TOK -d grant_type=password -d client_id=web-gateway --data-urlencode client_secret=geheim-aus-env -d username=carol --data-urlencode password=pw-aus-env
```

```
### 1a client_credentials, secret=geheim-aus-env
{"access_token":"eyJhbGciOiJSUzI1NiIsInR5cCIgOi…","expires_in":300,"refresh_expires_in":0,"token_type":"Bearer","not-before-policy":0,"scope":"email profile"}
HTTP 200
### 1b client_credentials, secret=${EXP_CLIENT_SECRET} (woertlich)
{"error":"unauthorized_client","error_description":"Invalid client or Invalid client credentials"}
HTTP 401
### 2a password alice/pw-aus-env
{"access_token":"eyJhbGciOiJSUzI1NiIsInR5cCIgOi…","expires_in":300,"refresh_expires_in":1800,"refresh_token":"eyJhbGciOiJIUzUxMiIsInR5cCIgOi…","token_type":"Bearer","not-before-policy":0,"session_state":"yQNT55d_diuNPe6rxqWN0Zq5","scope":"email profile"}
HTTP 200
### 2b password alice/${EXP_DEMO_PASSWORD} (woertlich)
{"error":"invalid_grant","error_description":"Invalid user credentials"}
HTTP 400
### 3a password bob/bob-vorgabe
{"access_token":"eyJhbGciOiJSUzI1NiIsInR5cCIgOi…","expires_in":300,"refresh_expires_in":1800,"refresh_token":"eyJhbGciOiJIUzUxMiIsInR5cCIgOi…","token_type":"Bearer","not-before-policy":0,"session_state":"2K_gZSG0lZrVKnhjaBBH8bhu","scope":"email profile"}
HTTP 200
### 3b password bob/${EXP_UNSET:bob-vorgabe} (woertlich)
{"error":"invalid_grant","error_description":"Invalid user credentials"}
HTTP 400
### 4 password carol (ohne email)/pw-aus-env
{"error":"invalid_grant","error_description":"Account is not fully set up"}
HTTP 400
```

Redirect-URI (Autorisierungsanfrage) und Gegenprobe mit nicht registriertem Port 9999:

```bash
C -s -i --max-time 10 'http://exp-kc:8080/auth/realms/chat/protocol/openid-connect/auth?client_id=web-gateway&response_type=code&scope=openid&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Flogin%2Foauth2%2Fcode%2Fkeycloak' > "$W/e1-login-response.txt"
C -s -i --max-time 10 'http://exp-kc:8080/auth/realms/chat/protocol/openid-connect/auth?client_id=web-gateway&response_type=code&scope=openid&redirect_uri=http%3A%2F%2Flocalhost%3A9999%2Flogin%2Foauth2%2Fcode%2Fkeycloak' > "$W/e1-login-neg.txt"
```

```
### localhost:8080 (registriert über ${EXP_PUBLIC_URL})
HTTP/1.1 200 OK
Bytes: 8643
id="kc-form-login" class="pf-v5-c-form pf-v5-u-w-100" onsubmit="login.disabled = true; return true;" action="http://localhost:8080/auth/realms/chat/login-actions/authenticate?session_code=QFr75R_S-7K-…
"Invalid parameter" kommt 0-mal vor
### Gegenprobe localhost:9999
HTTP/1.1 400 Bad Request
Invalid parameter: redirect_uri
"kc-form-login" kommt 0-mal vor
```

**Befund E1:** Keycloak 26.7.3 ersetzt `${VAR}` beim `--import-realm` in Client-Secret, Redirect-URI und Passwort-Credential durch die Umgebungsvariable (richtiges Secret → 200, wörtliches `${EXP_CLIENT_SECRET}` → 401 `unauthorized_client`; Login-Seite statt `Invalid parameter: redirect_uri`), und die Syntax `${VAR:vorgabe}` liefert bei nicht gesetzter Variable den Vorgabewert (bob/`bob-vorgabe` → 200). Zusatz: Ein Benutzer ohne `email` (carol, sonst gleich wie alice) bekommt beim Password-Grant 400 `invalid_grant` «Account is not fully set up» – im Realm-Import also jedem Benutzer eine E-Mail geben. Falsches Benutzerpasswort ergibt 400 (nicht 401). Startzeit: erste 200 nach ~32 s ab `docker run` (Keycloak selbst: Augmentation 9,2 s + «started in 17.330s»; Image lag lokal).

## E2 – Health-Endpunkt und Healthcheck ohne curl

Von aussen (Hilfscontainer im Netz `chat-net`):

```bash
C -s -i --max-time 10 http://exp-kc:9000/auth/health/ready; echo; echo "curl-exit=$?"
C -s -i --max-time 10 http://exp-kc:9000/health/ready; echo; echo "curl-exit=$?"
C -s -o /dev/null -w 'HTTP %{http_code}\n' --max-time 10 http://exp-kc:8080/auth/health/ready
C -s -w '\nHTTP %{http_code}\n' --max-time 10 http://exp-kc:9000/auth/health
```

(Ausgabe mit `tr -d '\r'`; die Zeilen `curl-exit=0` sind weggelassen, weil sie den Exit-Code des vorangehenden `echo` zeigten, nicht den von curl.)

```
### http://exp-kc:9000/auth/health/ready
HTTP/1.1 200 OK
content-type: application/json; charset=UTF-8
cache-control: no-store
content-length: 225

{
    "status": "UP",
    "checks": [
        {
            "name": "Graceful Shutdown",
            "status": "UP"
        },
        {
            "name": "Keycloak Initialized",
            "status": "UP"
        }
    ]
}
### http://exp-kc:9000/health/ready
HTTP/1.1 404 Not Found
content-type: text/html; charset=utf-8
content-length: 53

<html><body><h1>Resource not found</h1></body></html>
### Zusatz: http://exp-kc:8080/auth/health/ready (Hauptport)
HTTP 404
### Zusatz: http://exp-kc:9000/auth/health (alle Checks)
{ "status": "UP", … gleiche zwei Checks … }
HTTP 200
```

Im Keycloak-Container:

```bash
docker exec exp-kc bash -c 'command -v curl; echo "exit=$?"'
docker exec exp-kc bash -c 'command -v wget; echo "exit=$?"'
docker exec exp-kc bash -c 'command -v bash; echo "exit=$?"; bash --version | head -1'
docker exec exp-kc bash -c 'exec 3<>/dev/tcp/localhost/9000 && printf "GET /auth/health/ready HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && read -r status <&3 && [[ "$status" == *" 200 "* ]]'
echo "exit=$?"
docker exec exp-kc bash -c 'exec 3<>/dev/tcp/localhost/9000 && printf "GET /falsch/health/ready HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && read -r status <&3 && [[ "$status" == *" 200 "* ]]'
echo "exit=$?"
# Zusatz: Port ohne Listener, entspricht «Keycloak lauscht noch nicht»
docker exec exp-kc bash -c 'exec 3<>/dev/tcp/localhost/9001 && printf "GET /auth/health/ready HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && read -r status <&3 && [[ "$status" == *" 200 "* ]]'
echo "exit=$?"
```

```
### command -v curl / wget / bash
exit=1
exit=1
/usr/bin/bash
exit=0
GNU bash, version 5.1.8(1)-release (x86_64-redhat-linux-gnu)
### Healthcheck richtiger Pfad
exit=0
### Healthcheck falscher Pfad /falsch/health/ready
exit=1
### Zusatz: Port ohne Listener (9001) = wie "noch nicht gestartet"
bash: connect: Connection refused
bash: line 1: /dev/tcp/localhost/9001: Connection refused
exit=1
```

Rohantwort, um die erste Zeile wörtlich zu sehen:

```bash
docker exec exp-kc bash -c 'exec 3<>/dev/tcp/localhost/9000 && printf "GET /auth/health/ready HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && cat <&3' | cat -A
docker exec exp-kc bash -c 'exec 3<>/dev/tcp/localhost/9000 && printf "GET /falsch/health/ready HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && cat <&3' | cat -A
docker exec exp-kc bash -c 'exec 3<>/dev/tcp/localhost/9000 && printf "GET /auth/health/ready HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && read -r status <&3 && printf "%q\n" "$status"'
```

```
### Antwort richtiger Pfad (cat -A: ^M = \r, $ = Zeilenende)
HTTP/1.0 200 OK^M$
content-type: application/json; charset=UTF-8^M$
cache-control: no-store^M$
content-length: 225^M$
^M$
{$
    "status": "UP",$
…
### Antwort falscher Pfad
HTTP/1.0 404 Not Found^M$
content-type: text/html; charset=utf-8^M$
content-length: 53^M$
^M$
<html><body><h1>Resource not found</h1></body></html>
### Inhalt von $status nach read -r (richtiger Pfad)
$'HTTP/1.0 200 OK\r'
```

**Befund E2:** `health/ready` antwortet nur auf dem Management-Port 9000 und erbt dort den relativen Pfad: `http://exp-kc:9000/auth/health/ready` → 200 `{"status":"UP"}`, `http://exp-kc:9000/health/ready` → 404 (auf Port 8080 gibt es keinen Health-Endpunkt, 404). Im Image gibt es weder `curl` noch `wget`, aber `/usr/bin/bash` 5.1.8; der `/dev/tcp`-Befehl liefert Exit 0 bei bereit, Exit 1 bei falschem Pfad (404) und Exit 1 bei geschlossenem Port; die erste Antwortzeile lautet wörtlich `HTTP/1.0 200 OK\r` (mit Reason-Phrase, daher passt `*" 200 "*`).

## E3 – Issuer und Endpunkte

```bash
C -s --max-time 10 http://exp-kc:8080/auth/realms/chat/.well-known/openid-configuration > "$W/e3-discovery.json"
node -e 'const d=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));for (const k of ["issuer","authorization_endpoint","token_endpoint","userinfo_endpoint","jwks_uri","end_session_endpoint","introspection_endpoint"]) console.log(k+": "+d[k])' "$W/e3-discovery.json"
grep -c 'exp-kc' "$W/e3-discovery.json"
grep -oE 'https?://[^/"]+' "$W/e3-discovery.json" | sort | uniq -c
```

```
issuer: http://localhost:8080/auth/realms/chat
authorization_endpoint: http://localhost:8080/auth/realms/chat/protocol/openid-connect/auth
token_endpoint: http://localhost:8080/auth/realms/chat/protocol/openid-connect/token
userinfo_endpoint: http://localhost:8080/auth/realms/chat/protocol/openid-connect/userinfo
jwks_uri: http://localhost:8080/auth/realms/chat/protocol/openid-connect/certs
end_session_endpoint: http://localhost:8080/auth/realms/chat/protocol/openid-connect/logout
introspection_endpoint: http://localhost:8080/auth/realms/chat/protocol/openid-connect/token/introspect
0
     21 http://localhost:8080
```

Token holen (frisch, gleiche Aufrufe wie in E1) und Header/Payload mit Node dekodieren:

```bash
TOK=http://exp-kc:8080/auth/realms/chat/protocol/openid-connect/token
AT_ALICE=$(C -s --max-time 10 -X POST $TOK -d grant_type=password -d client_id=web-gateway --data-urlencode client_secret=geheim-aus-env -d username=alice --data-urlencode password=pw-aus-env | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
AT_SA=$(C -s --max-time 10 -X POST $TOK -d grant_type=client_credentials -d client_id=web-gateway --data-urlencode client_secret=geheim-aus-env | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
DEC='const t=process.argv[1];const h=JSON.parse(Buffer.from(t.split(".")[0],"base64url").toString());const p=JSON.parse(Buffer.from(t.split(".")[1],"base64url").toString());console.log("header: "+JSON.stringify(h));console.log("payload: "+JSON.stringify(p,null,1));console.log("=> iss="+p.iss+" | aud="+JSON.stringify(p.aud)+" | azp="+p.azp+" | typ="+p.typ+" | exp-iat="+(p.exp-p.iat)+" s")'
node -e "$DEC" "$AT_ALICE"
node -e "$DEC" "$AT_SA"
```

```
### Access-Token alice (Password-Grant), Laenge 1074
header: {"alg":"RS256","typ":"JWT","kid":"JFtdxiakSF-jpbvUq61cPnoK4Yhfj-eigNGeRaRCGsA"}
payload: {
 "exp": 1790933129,
 "iat": 1790932829,
 "jti": "onrtro:4f474355-d7f6-13b4-2f55-e237b8807b36",
 "iss": "http://localhost:8080/auth/realms/chat",
 "sub": "c76ac19a-3aaa-4a31-97f3-bbb370477279",
 "typ": "Bearer",
 "azp": "web-gateway",
 "sid": "SLtb9IyLmK4YH8dW8COM_eCK",
 "acr": "1",
 "allowed-origins": [
  "http://localhost:8080"
 ],
 "scope": "email profile",
 "email_verified": true,
 "name": "Alice Exp",
 "preferred_username": "alice",
 "given_name": "Alice",
 "family_name": "Exp",
 "email": "alice@example.org"
}
=> iss=http://localhost:8080/auth/realms/chat | aud=undefined | azp=web-gateway | typ=Bearer | exp-iat=300 s
### Access-Token Service-Account (Client-Credentials), Laenge 1315
header: {"alg":"RS256","typ":"JWT","kid":"JFtdxiakSF-jpbvUq61cPnoK4Yhfj-eigNGeRaRCGsA"}
payload: {
 "exp": 1790933130,
 "iat": 1790932830,
 "jti": "trrtcc:d3b29354-06e9-fa31-c82e-540bdf87c737",
 "iss": "http://localhost:8080/auth/realms/chat",
 "aud": "account",
 "sub": "42aa3074-48d1-47a4-8150-bd5ecd545044",
 "typ": "Bearer",
 "azp": "web-gateway",
 "acr": "1",
 "allowed-origins": [
  "http://localhost:8080"
 ],
 "realm_access": {
  "roles": [
   "offline_access",
   "default-roles-chat",
   "uma_authorization"
  ]
 },
 "resource_access": {
  "account": {
   "roles": [
    "manage-account",
    "manage-account-links",
    "view-profile"
   ]
  }
 },
 "scope": "email profile",
 "clientHost": "172.18.0.8",
 "email_verified": false,
 "preferred_username": "service-account-web-gateway",
 "clientAddress": "172.18.0.8",
 "client_id": "web-gateway"
}
=> iss=http://localhost:8080/auth/realms/chat | aud="account" | azp=web-gateway | typ=Bearer | exp-iat=300 s
```

**Befund E3:** Wie erwartet stehen wegen `KC_HOSTNAME=http://localhost:8080/auth` alle 21 URLs im Discovery-Dokument auf `http://localhost:8080/auth/realms/chat/...` (issuer, authorization, token, userinfo, jwks, end_session, introspection), obwohl über `exp-kc:8080` abgefragt wurde; auch das Token hat `iss=http://localhost:8080/auth/realms/chat`, `azp=web-gateway`, `typ=Bearer`, `exp-iat=300 s` (RS256). Nicht erwartet: Der Access-Token von alice (Password-Grant) hat **kein `aud`** und keine `realm_access`/`resource_access`-Rollen; nur der Service-Account-Token hat `aud="account"` und die Default-Rollen (vermutete Ursache: per Import angelegte Benutzer ohne `realmRoles` erhalten `default-roles-chat` nicht – Nachprüfung siehe E3b).

## E4 – Pfade der Login-Seite, Admin-Konsole, Realm master

Login-Seite = gespeicherte Antwort aus E1 (`$W/e1-login-response.txt`, Autorisierungsanfrage mit `redirect_uri=http://localhost:8080/login/oauth2/code/keycloak`).

```bash
F="$W/e1-login-response.txt"
grep -oE '(href|src|action)="[^"]*"' "$F"
grep -oE '(href|src|action)="[^"]*"' "$F" | sed -E 's/^[a-z]+="([^"]*)"$/\1/' | awk '
  /^\/auth\/resources\//                       {a++; next}
  /^\/auth\/realms\/chat\//                    {b++; next}
  /^http:\/\/localhost:8080\/auth\/realms\/chat\// {c++; next}
  {print "ANDERE: " $0; d++}
  END {printf "beginnt mit /auth/resources/: %d\nbeginnt mit /auth/realms/chat/: %d\nabsolut http://localhost:8080/auth/realms/chat/: %d\nandere: %d\n", a, b, c, d}'
# weitere Pfade ausserhalb von href/src/action (Importmap, import-Anweisungen, JS-Strings):
sed -E 's/(href|src|action)="[^"]*"//g' "$F" | grep -oE '"(/|https?://)[^"]*"' | sort | uniq -c
tr -d '\r' < "$F" | grep -nE 'importmap|rfc4648|authChecker|login-actions/restart|<script|</script>' | cut -c1-220
tr -d '\r' < "$F" | sed -n '1,/^$/p'
```

```
### alle href=/src=/action= (Reihenfolge wie im HTML)
href="/auth/resources/j6b6d/login/keycloak.v2/img/favicon.ico"
href="/auth/resources/j6b6d/common/keycloak/vendor/patternfly-v5/patternfly.min.css"
href="/auth/resources/j6b6d/common/keycloak/vendor/patternfly-v5/patternfly-addons.css"
href="/auth/resources/j6b6d/login/keycloak.v2/css/styles.css"
src="/auth/resources/j6b6d/login/keycloak.v2/js/passwordVisibility.js"
action="http://localhost:8080/auth/realms/chat/login-actions/authenticate?session_code=QFr75R_S-7K-b9UjZtwjHW15PeucVm-GUf3UMVb3ATc&amp;execution=74363516-f3c1-437d-ae76-61dbd80bdba3&amp;client_id=web-gateway&amp;tab_id=BNtUDHe5wxM&amp;client_data=eyJydSI6Imh0dHA6Ly9sb2NhbGhvc3Q6ODA4MC9sb2dpbi9vYXV0aDIvY29kZS9rZXljbG9hayIsInJ0IjoiY29kZSJ9"

### Einordnung
beginnt mit /auth/resources/: 5
beginnt mit /auth/realms/chat/: 0
absolut http://localhost:8080/auth/realms/chat/: 1
andere: 0

### weitere Pfade (ausserhalb href/src/action)
      1 "/auth/realms/chat/login-actions/restart?client_id=web-gateway&tab_id=BNtUDHe5wxM&client_data=eyJydSI6Imh0dHA6Ly9sb2NhbGhvc3Q6ODA4MC9sb2dpbi9vYXV0aDIvY29kZS9rZXljbG9hayIsInJ0IjoiY29kZSJ9&skip_logout=true"
      1 "/auth/resources/j6b6d/common/keycloak/vendor/rfc4648/rfc4648.js"
      2 "/auth/resources/j6b6d/login/keycloak.v2/js/authChecker.js"
### Kontext
30:    <script type="importmap">
33:                "rfc4648": "/auth/resources/j6b6d/common/keycloak/vendor/rfc4648/rfc4648.js"
54:    <script type="module" src="/auth/resources/j6b6d/login/keycloak.v2/js/passwordVisibility.js"></script>
56:        import { startSessionPolling } from "/auth/resources/j6b6d/login/keycloak.v2/js/authChecker.js";
59:            "/auth/realms/chat/login-actions/restart?client_id=web-gateway&tab_id=BNtUDHe5wxM&client_data=eyJydSI6Imh0dHA6Ly9sb2NhbGhvc3Q6ODA4MC9sb2dpbi9vYXV0aDIvY29kZS9rZXljbG9hayIsInJ0IjoiY29kZSJ9&skip_logout=true"
86:            import { checkAuthSession } from "/auth/resources/j6b6d/login/keycloak.v2/js/authChecker.js";
### Antwortkopf (Cookie-Werte gekürzt)
HTTP/1.1 200 OK
Set-Cookie: AUTH_SESSION_ID=OXlqNGJJ…;Version=1;Path=/auth/realms/chat/;HttpOnly;SameSite=Lax
Set-Cookie: KC_AUTH_SESSION_HASH="5cxezi…";Version=1;Path=/auth/realms/chat/;Max-Age=60;SameSite=Lax
Cache-Control: no-store, must-revalidate, max-age=0
Set-Cookie: KC_RESTART=eyJhbGci…;Version=1;Path=/auth/realms/chat/;HttpOnly;SameSite=Lax
content-length: 6847
Content-Language: en
Content-Security-Policy: frame-src 'self'; frame-ancestors 'self'; object-src 'none';
Content-Type: text/html;charset=utf-8
Referrer-Policy: no-referrer
Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Content-Type-Options: nosniff
X-Frame-Options: SAMEORIGIN
X-Robots-Tag: none
```

Admin-Konsole, Realm master, Wurzel:

```bash
for u in http://exp-kc:8080/auth/admin/ \
         http://exp-kc:8080/auth/realms/master/.well-known/openid-configuration \
         http://exp-kc:8080/auth/ \
         http://exp-kc:8080/auth \
         http://exp-kc:8080/ \
         http://exp-kc:8080/auth/admin/master/console/ ; do
  f="$W/e4-$(echo "$u" | sed -E 's#http://exp-kc:8080##; s#[^A-Za-z0-9]+#_#g').txt"
  C -s -i --max-time 10 "$u" > "$f"
  echo "### GET $u"
  tr -d '\r' < "$f" | sed -n '1p'
  tr -d '\r' < "$f" | grep -iE '^location:'
  echo "Body-Bytes: $(tr -d '\r' < "$f" | sed '1,/^$/d' | wc -c)  <title>: $(grep -oE '<title>[^<]*</title>' "$f" | head -1)"
done
node -e 'const d=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));console.log("issuer: "+d.issuer)' "$W/e4-master-discovery.json"
```

```
### GET http://exp-kc:8080/auth/admin/
HTTP/1.1 302 Found
Location: http://localhost:8080/auth/admin/master/console/
Body-Bytes: 0  <title>: 
### GET http://exp-kc:8080/auth/realms/master/.well-known/openid-configuration
HTTP/1.1 200 OK
Body-Bytes: 6670  <title>: 
### GET http://exp-kc:8080/auth/
HTTP/1.1 200 OK
Body-Bytes: 2401  <title>: <title>Welcome to Keycloak</title>
### GET http://exp-kc:8080/auth
HTTP/1.1 303 See Other
Location: http://exp-kc:8080/auth/
Body-Bytes: 0  <title>: 
### GET http://exp-kc:8080/
HTTP/1.1 302 Found
location: /auth
Body-Bytes: 21  <title>: 
### GET http://exp-kc:8080/auth/admin/master/console/
HTTP/1.1 200 OK
Body-Bytes: 3900  <title>: <title>Keycloak Administration Console</title>
issuer: http://localhost:8080/auth/realms/master
```

Inhalt `/auth/` (Willkommensseite, Auszug):

```
<title>Welcome to Keycloak</title>
<link rel="shortcut icon" href="resources/j6b6d/common/keycloak/img/favicon.ico">
<link href="resources/j6b6d/common/keycloak/vendor/patternfly-v5/patternfly.min.css" rel="stylesheet" />
…
<div class="pf-v5-c-background-image" style="--pf-v5-c-background-image--BackgroundImage: url(http://localhost:8080/auth/resources/j6b6d/welcome/keycloak/background.svg)"></div>
…
<h1 class="pf-v5-c-title pf-m-3xl">Local access required</h1>
<p class="pf-v5-c-login__main-header-desc">You will need local access to create the administrative user.</p>
…
<p>To create the administrative user, access the Administration Console over localhost, or use a <code>bootstrap-admin</code> command.</p>
```

Inhalt `/auth/admin/master/console/` (Auszug):

```
<title>Keycloak Administration Console</title>
<script type="module" src="/auth/resources/j6b6d/admin/keycloak.v2/assets/main-CB1rIpj1.js"></script>
…
<p id="loading-text">Loading the Administration Console</p>
…
<script id="environment" type="application/json">
  {
    "serverBaseUrl": "http://localhost:8080/auth",
    "adminBaseUrl": "http://localhost:8080/auth",
    "authUrl": "http://localhost:8080/auth",
    "authServerUrl": "http://localhost:8080/auth",
    "realm": "master",
    "clientId": "security-admin-console",
    "resourceUrl": "/auth/resources/j6b6d/admin/keycloak.v2",
    …
    "consoleBaseUrl": "/auth/admin/master/console/",
    "masterRealm": "master",
    "resourceVersion": "j6b6d"
  }
</script>
```

**Befund E4:** Die Login-Seite referenziert alle statischen Dateien (5× href/src, dazu Importmap und zwei `import`) unter `/auth/resources/j6b6d/…` (relativ zum Host); das Formular-`action` ist dagegen **absolut** `http://localhost:8080/auth/realms/chat/login-actions/authenticate?…` (aus `KC_HOSTNAME`), der Restart-Link im Skript relativ `/auth/realms/chat/login-actions/restart?…`; andere Präfixe gibt es nicht, und alle Cookies werden mit `Path=/auth/realms/chat/` gesetzt. Den Realm master gibt es auch ohne Bootstrap-Admin (`/auth/realms/master/.well-known/…` → 200, Log «Initializing master realm»); `/auth/admin/` → 302 nach `http://localhost:8080/auth/admin/master/console/`, die Konsole liefert 200 (React-Hülle «Loading the Administration Console», Client `security-admin-console`, Realm master), aber es gibt keinen Admin-Benutzer, und `/auth/` zeigt 200 «Local access required – You will need local access to create the administrative user» (Anfrage kam nicht von localhost). Nebenbei: `/auth` → 303 nach `http://exp-kc:8080/auth/` (Host aus der Anfrage, nicht aus KC_HOSTNAME), `/` → 302 `location: /auth`; die Willkommensseite nutzt relative Pfade `resources/j6b6d/…`.

## E5 – Die Nachricht auf `chat.delivery`

Zugangsdaten aus `.env` nur in Variablen geladen, nie ausgegeben:

```bash
ENVF="C:/Users/hamdi/Documents/M321/it3c-m321/.env"
val() { grep -E "^$1=" "$ENVF" | head -1 | cut -d= -f2- | tr -d '\r' | sed -E 's/^"(.*)"$/\1/'; }
RU=$(val RABBITMQ_USER); RP=$(val RABBITMQ_PASSWORD)
RA() { docker exec it3c-m321-rabbitmq-1 rabbitmqadmin -u "$RU" -p "$RP" "$@"; }
```

Zustand vorher (ohne Zugangsdaten, über `rabbitmqctl`):

```bash
docker exec it3c-m321-rabbitmq-1 rabbitmqctl list_vhosts -q
docker exec it3c-m321-rabbitmq-1 rabbitmqctl list_exchanges name type durable auto_delete internal arguments -q | grep -v '^amq\.'
docker exec it3c-m321-rabbitmq-1 rabbitmqctl list_bindings source_name source_kind destination_name destination_kind routing_key -q | awk -F'\t' '$1=="chat.delivery" || NR==1'
docker exec it3c-m321-rabbitmq-1 rabbitmqctl list_queues name durable auto_delete messages_ready consumers -q
docker exec it3c-m321-rabbitmq-1 sh -c 'command -v rabbitmqadmin; rabbitmqadmin --version 2>&1 | head -2'
```

```
### rabbitmqctl list_vhosts
name
/
### rabbitmqctl list_exchanges name type durable auto_delete internal arguments (ohne amq.*)
name	type	durable	auto_delete	internal	arguments
chat.delivery	fanout	true	false	false	[]
	direct	true	false	false	[]
### rabbitmqctl list_bindings source_name source_kind destination_name destination_kind routing_key (Quelle chat.delivery)
source_name	source_kind	destination_name	destination_kind	routing_key
### rabbitmqctl list_queues name durable auto_delete messages_ready consumers
name	durable	auto_delete	messages_ready	consumers
chat.dlq	true	false	0	0
chat.persist	true	false	0	2
### rabbitmqadmin vorhanden?
/usr/local/bin/rabbitmqadmin
rabbitmqadmin 3.13.7
```

Queue anlegen, binden, Exchange über rabbitmqadmin:

```bash
RA declare queue name=exp.delivery durable=false auto_delete=false
RA declare binding source=chat.delivery destination=exp.delivery destination_type=queue
docker exec it3c-m321-rabbitmq-1 rabbitmqctl list_bindings source_name source_kind destination_name destination_kind routing_key -q | awk -F'\t' '$1=="chat.delivery" || NR==1'
RA list exchanges name type durable auto_delete internal arguments | grep -E 'name|chat\.|^\+'
```

```
### declare queue / declare binding
queue declared
binding declared
### Bindings mit Quelle chat.delivery (rabbitmqctl)
source_name	source_kind	destination_name	destination_kind	routing_key
chat.delivery	exchange	exp.delivery	queue	
### rabbitmqadmin list exchanges (gekürzt auf chat.*)
+--------------------+---------+---------+-------------+----------+-----------+
|        name        |  type   | durable | auto_delete | internal | arguments |
+--------------------+---------+---------+-------------+----------+-----------+
| chat.delivery      | fanout  | True    | False       | False    |           |
+--------------------+---------+---------+-------------+----------+-----------+
```

Senden (Befehl aus dem Auftrag, ergänzt um `--max-time 10`) und Warten mit Obergrenze:

```bash
docker run --rm --network chat-net curlimages/curl -s -i --max-time 10 -X POST http://chat-service:8080/messages -H 'Content-Type: application/json' -d '{"roomId":"00000000-0000-0000-0000-000000000001","senderId":"exp-sub","senderName":"Exp Sender","content":"Grüezi Exp"}'
for i in $(seq 1 20); do
  n=$(docker exec it3c-m321-rabbitmq-1 rabbitmqctl list_queues name messages_ready -q | awk -F'\t' '$1=="exp.delivery"{print $2}')
  echo "Versuch $i: messages_ready=$n"
  [ "${n:-0}" -ge 1 ] && break
  sleep 1
done
```

```
### POST /messages
HTTP/1.1 202 
Content-Type: application/json
Transfer-Encoding: chunked
Date: Fri, 02 Oct 2026 09:24:42 GMT

{"id":"4540758d-7829-4471-954b-ebcda55e389b","sentAt":"2026-10-02T09:24:42.222080578Z"}
### warten auf exp.delivery
Versuch 1: messages_ready=1
```

(Statuszeile wörtlich `HTTP/1.1 202 ` – ohne Reason-Phrase.)

Nachricht holen – erst zerstörungsfrei lesbar, dann endgültig mit Base64, um die Bytes exakt zu haben:

```bash
RA -f pretty_json get queue=exp.delivery count=1 ackmode=reject_requeue_true > "$W/e5-get-pretty.json"
RA -f raw_json get queue=exp.delivery count=1 ackmode=ack_requeue_false encoding=base64 > "$W/e5-get-base64.json"
node -e 'const m=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"))[0];const b=Buffer.from(m.payload,"base64");console.log(b.toString("utf8"));console.log("Laenge: "+b.length+" Bytes; Bytes von \"Gr..ezi\": "+b.subarray(b.indexOf("Gr"),b.indexOf("Gr")+5).toString("hex"))' "$W/e5-get-base64.json"
```

```
[
  {
    "exchange": "chat.delivery",
    "message_count": 0,
    "payload": "{\"id\":\"4540758d-7829-4471-954b-ebcda55e389b\",\"roomId\":\"00000000-0000-0000-0000-000000000001\",\"senderId\":\"exp-sub\",\"senderName\":\"Exp Sender\",\"content\":\"Grüezi Exp\",\"sentAt\":\"2026-10-02T09:24:42.222080578Z\"}",
    "payload_bytes": 206,
    "payload_encoding": "string",
    "properties": {
      "content_encoding": "UTF-8",
      "content_type": "application/json",
      "delivery_mode": 2,
      "headers": {
        "__TypeId__": "ch.benedict.m321.chatservice.dto.ChatMessage"
      },
      "priority": 0
    },
    "redelivered": false,
    "routing_key": ""
  }
]

[{"payload_bytes":206,"redelivered":true,"exchange":"chat.delivery","routing_key":"","message_count":0,"properties":{"priority":0,"delivery_mode":2,"headers":{"__TypeId__":"ch.benedict.m321.chatservice.dto.ChatMessage"},"content_encoding":"UTF-8","content_type":"application/json"},"payload":"eyJpZCI6IjQ1NDA3NThkLTc4MjktNDQ3MS05NTRiLWViY2RhNTVlMzg5YiIsInJvb21JZCI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMSIsInNlbmRlcklkIjoiZXhwLXN1YiIsInNlbmRlck5hbWUiOiJFeHAgU2VuZGVyIiwiY29udGVudCI6Ikdyw7xlemkgRXhwIiwic2VudEF0IjoiMjAyNi0xMC0wMlQwOToyNDo0Mi4yMjIwODA1NzhaIn0=","payload_encoding":"base64"}]

### Payload dekodiert (Bytes aus base64)
{"id":"4540758d-7829-4471-954b-ebcda55e389b","roomId":"00000000-0000-0000-0000-000000000001","senderId":"exp-sub","senderName":"Exp Sender","content":"Grüezi Exp","sentAt":"2026-10-02T09:24:42.222080578Z"}
Laenge: 206 Bytes; Bytes von "Gr..ezi": 4772c3bc65
```

Body wörtlich (206 Bytes, UTF-8, «ü» = `c3 bc`):

```json
{"id":"4540758d-7829-4471-954b-ebcda55e389b","roomId":"00000000-0000-0000-0000-000000000001","senderId":"exp-sub","senderName":"Exp Sender","content":"Grüezi Exp","sentAt":"2026-10-02T09:24:42.222080578Z"}
```

Kontrolle batch-writer, dann Aufräumen:

```bash
docker exec it3c-m321-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select id, room_id, sender_id, sender_name, content, sent_at from message where sender_id = '"'"'exp-sub'"'"'"'
RA delete queue name=exp.delivery
docker exec it3c-m321-rabbitmq-1 rabbitmqctl list_queues name messages_ready consumers -q
docker exec it3c-m321-rabbitmq-1 rabbitmqctl list_bindings source_name destination_name -q | awk -F'\t' '$1=="chat.delivery" || NR==1'
```

```
### batch-writer: Zeile in message
                  id                  |               room_id                | sender_id | sender_name |  content   |            sent_at            
--------------------------------------+--------------------------------------+-----------+-------------+------------+-------------------------------
 4540758d-7829-4471-954b-ebcda55e389b | 00000000-0000-0000-0000-000000000001 | exp-sub   | Exp Sender  | Grüezi Exp | 2026-10-02 09:24:42.222081+00
(1 row)

### delete queue exp.delivery
queue deleted
name	messages_ready	consumers
chat.dlq	0	0
chat.persist	0	2
source_name	destination_name
```

**Befund E5:** `chat.delivery` ist ein Fanout-Exchange (`durable=true`, `auto_delete=false`, `internal=false`, keine Argumente) im vhost `/`, an den vor dem Test **keine einzige Queue gebunden** war (Nachrichten darauf gingen bisher verloren); `POST /messages` antwortet `202` mit `{"id":…,"sentAt":…}`, und auf dem Exchange kommt die `ChatMessage` als JSON mit `content_type=application/json`, `content_encoding=UTF-8`, `delivery_mode=2` (persistent), `priority=0`, `headers.__TypeId__=ch.benedict.m321.chatservice.dto.ChatMessage`, `routing_key=""` an (Felder `id, roomId, senderId, senderName, content, sentAt`, `sentAt` mit Nanosekunden). Der batch-writer hat sie als id `4540758d-7829-4471-954b-ebcda55e389b` gespeichert (`sent_at` dort auf Mikrosekunden gerundet: `09:24:42.222081`); Queue `exp.delivery` ist gelöscht, `chat.delivery` hat wieder keine Bindings.

## E3b (Zusatz) – Warum hat alice kein `aud`?

Zweite Realm-Datei `$W/realm-exp2.json`: gleicher Client, alice unverändert, neu `dave` mit zusätzlich `"realmRoles": ["default-roles-chat"]` (sonst gleich wie alice). `exp-kc` entfernt, `exp-kc2` mit denselben Umgebungsvariablen gestartet.

```bash
docker rm -f exp-kc
T0=$(date +%s)
docker run -d --name exp-kc2 --network chat-net \
  --mount type=bind,source="$W/realm-exp2.json",target=/opt/keycloak/data/import/realm-chat.json,readonly \
  -e EXP_CLIENT_SECRET=geheim-aus-env \
  -e EXP_PUBLIC_URL=http://localhost:8080 \
  -e EXP_DEMO_PASSWORD=pw-aus-env \
  -e KC_HTTP_RELATIVE_PATH=/auth \
  -e KC_HOSTNAME=http://localhost:8080/auth \
  -e KC_HEALTH_ENABLED=true \
  -e KC_PROXY_HEADERS=xforwarded \
  quay.io/keycloak/keycloak:26.7.3 start-dev --import-realm
for i in $(seq 1 60); do
  code=$(docker run --rm --network chat-net curlimages/curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://exp-kc2:8080/auth/realms/chat/.well-known/openid-configuration)
  [ "$code" = "200" ] && { echo "Versuch $i nach $(( $(date +%s) - T0 )) s: HTTP 200"; break; }
  if [ "$(docker inspect -f '{{.State.Running}}' exp-kc2)" != "true" ]; then echo "Container gestoppt"; break; fi
  sleep 2
done
docker logs exp-kc2 2>&1 | grep -E "ERROR|imported|Import finished|started in" | cut -c1-250
TOK=http://exp-kc2:8080/auth/realms/chat/protocol/openid-connect/token
SHOW='const p=JSON.parse(Buffer.from(process.argv[1].split(".")[1],"base64url").toString());console.log("aud="+JSON.stringify(p.aud)+" | realm_access="+JSON.stringify(p.realm_access)+" | resource_access="+JSON.stringify(p.resource_access)+" | scope="+p.scope)'
for u in alice dave; do
  AT=$(C -s --max-time 10 -X POST $TOK -d grant_type=password -d client_id=web-gateway --data-urlencode client_secret=geheim-aus-env -d username=$u --data-urlencode password=pw-aus-env | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
  echo "### $u (Token-Laenge ${#AT})"
  node -e "$SHOW" "$AT"
done
```

```
exp-kc
0a783ebc024b33b9735fc20eb527c8b82a865aae12769adc7944db1a6fc44ade
Versuch 9 nach 31 s: HTTP 200
2026-10-02 09:26:34,417 INFO  [org.keycloak.exportimport.util.ImportUtils] (main) Realm 'chat' imported
2026-10-02 09:26:34,417 INFO  [org.keycloak.services] (main) KC-SERVICES0032: Import finished successfully
2026-10-02 09:26:34,897 INFO  [io.quarkus] (main) Keycloak 26.7.3 on JVM (powered by Quarkus 3.33.3.1) started in 16.829s. Listening on: http://0.0.0.0:8080. Management interface listening on http://0.0.0.0:9000.
### alice (Token-Laenge 1074)
aud=undefined | realm_access=undefined | resource_access=undefined | scope=profile email
### dave (Token-Laenge 1346)
aud="account" | realm_access={"roles":["default-roles-chat","offline_access","uma_authorization"]} | resource_access={"account":{"roles":["manage-account","manage-account-links","view-profile"]}} | scope=profile email
```

**Befund E3b:** Bestätigt – per `--import-realm` angelegte Benutzer bekommen die Default-Rolle `default-roles-chat` **nicht** automatisch (der Service-Account-Benutzer schon); erst mit `"realmRoles": ["default-roles-chat"]` enthält der Access-Token `aud="account"`, `realm_access` und `resource_access`. Der Verweis auf `default-roles-chat` funktioniert, obwohl die Rolle nicht in der Datei definiert ist (Keycloak legt sie beim Import an); ein zweiter Start dauerte wieder ~31 s bis zur ersten 200.

## Aufräumen

```bash
docker rm -f exp-kc2
docker ps -a --filter name=exp- --format '{{.Names}} {{.Status}}'
echo "Anzahl: $(docker ps -a --filter name=exp- -q | wc -l)"
docker exec it3c-m321-rabbitmq-1 rabbitmqctl list_queues name messages_ready consumers -q
docker ps --filter name=it3c-m321 --format '{{.Names}} {{.Status}}'
```

```
exp-kc2
Anzahl: 0
name	messages_ready	consumers
chat.dlq	0	0
chat.persist	0	2
it3c-m321-batch-writer-2 Up 2 hours
it3c-m321-batch-writer-1 Up 2 hours
it3c-m321-chat-service-1 Up 2 hours
it3c-m321-postgres-1 Up 2 hours (healthy)
it3c-m321-rabbitmq-1 Up 2 hours (healthy)
```

Alle `exp-*`-Container entfernt (`exp-kc` vor E3b, `exp-kc2` hier; die Hilfscontainer liefen mit `--rm`), Queue `exp.delivery` in E5 gelöscht, Stack läuft unverändert. Im Arbeitsordner liegen ausserdem die Rohdateien (`exp-kc.log`, `e1-*.txt/log`, `e2-*.log`, `e3-discovery.json`, `e4-*.txt`, `e5-get-*.json` usw.).

---

## E6 – Eigene Header beim WebSocket-Handshake mit `java.net.http.WebSocket` (nachgetragen)

Frage: Kann ein Java-Programm (Abnahmewerkzeug `scripts/WebSocketProbe.java`, später der Desktop-Client)
beim Handshake `Cookie`, `Origin` und `Authorization` mitschicken? Der Browser-WebSocket und der
WebSocket von Node können keine eigenen Header setzen.

Umgebung: OpenJDK 21.0.11 (Temurin), Windows 11, Git Bash. Beide Programme liegen in `e6/` und
laufen ohne Build als Single-File-Programm.

**E6a – `HeaderEcho.java`:** startet einen Mini-HTTP-Server auf `127.0.0.1` (freier Port), der die
Header jedes Handshakes ausgibt und mit `403` antwortet, und baut dagegen einen WebSocket mit drei
eigenen Headern sowie eine Gegenprobe mit `Sec-WebSocket-Key`.

```
$ java e6/HeaderEcho.java
### Cookie, Origin, Authorization
  Server sah: Origin = [http://evil.example]
  Server sah: Cookie = [JSESSIONID=abc]
  Server sah: Connection = [Upgrade]
  Server sah: Host = [127.0.0.1:49725]
  Server sah: Sec-websocket-version = [13]
  Server sah: Upgrade = [websocket]
  Server sah: User-agent = [Java-http-client/21.0.11]
  Server sah: Authorization = [Bearer xyz]
  Server sah: Sec-websocket-key = [wM+2h99CyBVh1w+1bWj9Ag==]
  Client: CompletionException: java.net.http.WebSocketHandshakeException
### Sec-WebSocket-Key (Gegenprobe)
  Client: CompletionException: java.lang.IllegalArgumentException: Illegal header: Sec-WebSocket-Key
```

(Gleiche Ausgabe in `e6/header-echo.log`. Die `WebSocketHandshakeException` ist erwartet: der
Mini-Server antwortet absichtlich mit `403`.)

**E6b – `HeaderCheck.java`:** ruft nur `WebSocket.Builder.header(name, "x")` auf, ohne Verbindung
(am 02.10.2026 nachträglich ausgeführt):

```
$ java e6/HeaderCheck.java
Cookie: angenommen
Origin: angenommen
Authorization: angenommen
Host: angenommen
Sec-WebSocket-Key: angenommen
```

*Befund:* Der JDK-WebSocket schickt `Cookie`, `Origin` und `Authorization` beim Handshake wirklich mit
(E6a, der Server sah alle drei). Einen `Sec-WebSocket-*`-Header lehnt das JDK ab, aber erst beim Aufbau
der Verbindung (`buildAsync` → `IllegalArgumentException: Illegal header`), nicht schon bei
`header(...)` (E6b).
