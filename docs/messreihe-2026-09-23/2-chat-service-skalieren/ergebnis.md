## Messreihe

- Datum: 2026-09-23 10:14 UTC
- Rechner: 4 CPU-Kerne, 15 GB Arbeitsspeicher, AMD EPYC 9V74 80-Core Processor
- chat-service-Instanzen: 3
- Java-Optionen des load-generator: keine

### Durchsatz: 100000 Nachrichten/Minute für 60 s

| batch-writer | chat-service | angenommen | Ist-Rate (pro Minute) | Fehler | grösste Queue-Tiefe | Queue leer nach Lastende | gespeichert | Verteilung auf chat-service-Instanzen |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 3 | 99960 | 85892 | 0 | 542 | höchstens 0 s | 99960 | 0de705f904c0: 54470, 7319c167833d: 45490 |
