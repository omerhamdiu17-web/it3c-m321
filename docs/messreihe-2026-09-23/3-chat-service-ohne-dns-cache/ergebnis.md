## Messreihe

- Datum: 2026-09-23 10:15 UTC
- Rechner: 4 CPU-Kerne, 15 GB Arbeitsspeicher, AMD EPYC 9V74 80-Core Processor
- chat-service-Instanzen: 3
- Java-Optionen des load-generator: -Dnetworkaddress.cache.ttl=0

### Durchsatz: 100000 Nachrichten/Minute für 60 s

| batch-writer | chat-service | angenommen | Ist-Rate (pro Minute) | Fehler | grösste Queue-Tiefe | Queue leer nach Lastende | gespeichert | Verteilung auf chat-service-Instanzen |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 3 | 99951 | 84205 | 9 | 506 | höchstens 0 s | 99951 | 0de705f904c0: 44982, 8a14b0a52a78: 54969 |
