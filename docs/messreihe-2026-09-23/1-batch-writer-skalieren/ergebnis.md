## Messreihe

- Datum: 2026-09-23 10:03 UTC
- Rechner: 4 CPU-Kerne, 15 GB Arbeitsspeicher, AMD EPYC 9V74 80-Core Processor
- chat-service-Instanzen: 1
- Java-Optionen des load-generator: keine

### Durchsatz: 100000 Nachrichten/Minute für 60 s

| batch-writer | chat-service | angenommen | Ist-Rate (pro Minute) | Fehler | grösste Queue-Tiefe | Queue leer nach Lastende | gespeichert | Verteilung auf chat-service-Instanzen |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 1 | 99960 | 83232 | 0 | 490 | höchstens 1 s | 99960 | 7319c167833d: 99960 |
| 2 | 1 | 99960 | 92844 | 0 | 898 | höchstens 1 s | 99960 | 7319c167833d: 99960 |
| 3 | 1 | 99960 | 93392 | 0 | 1365 | höchstens 1 s | 99960 | 7319c167833d: 99960 |

### Stau abbauen: 120 s Last ohne batch-writer, danach N batch-writer

| batch-writer | Stau (Nachrichten) | Abbau-Dauer | Abbau-Rate (pro Sekunde) | Abbau-Rate (pro Minute) | gespeichert |
|---:|---:|---:|---:|---:|---:|
| 1 | 199920 | 11.2 s | 17762 | 1065720 | 199920 |
| 2 | 199920 | 9.3 s | 21343 | 1280580 | 199920 |
| 3 | 199920 | 11.7 s | 17034 | 1022040 | 199920 |
