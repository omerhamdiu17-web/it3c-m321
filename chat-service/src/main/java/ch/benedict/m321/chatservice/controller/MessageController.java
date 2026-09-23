package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import ch.benedict.m321.chatservice.service.MessageService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die interne REST-Schnittstelle des chat-service.
 *
 * Erreichbar ist sie nur aus dem Docker-Netz — das web-gateway und der
 * load-generator rufen sie auf. Deshalb prüft dieser Dienst kein Token:
 * das hat das Gateway bereits getan.
 */
@RestController
public class MessageController {

    /**
     * In diesem Header steht, welche Instanz geantwortet hat. Der
     * load-generator zählt damit, ob sich die Last bei "--scale
     * chat-service=3" wirklich verteilt (PLANUNG.md, offener Punkt 8).
     */
    public static final String INSTANCE_HEADER = "X-Chat-Service-Instance";

    private final MessageService messageService;
    private final String instanceName;

    /**
     * Den Konstruktor schreiben wir hier von Hand, weil der Name der Instanz
     * aus der Umgebung kommt (@Value). Docker setzt HOSTNAME für jeden
     * Container auf dessen Kennung; ausserhalb von Docker steht "local".
     */
    public MessageController(MessageService messageService,
                             @Value("${HOSTNAME:local}") String instanceName) {
        this.messageService = messageService;
        this.instanceName = instanceName;
    }

    /**
     * Nimmt eine Nachricht entgegen.
     *
     * Antwort ist 202 und nicht 201, weil die Nachricht angenommen, aber
     * noch nirgends gespeichert ist. Der Statuscode sagt genau das aus,
     * was das System tut.
     */
    @PostMapping("/messages")
    public ResponseEntity<AcceptedResponse> send(@Valid @RequestBody SendMessageRequest request) {
        AcceptedResponse response = messageService.accept(request);
        return ResponseEntity.accepted()
                .header(INSTANCE_HEADER, instanceName)
                .body(response);
    }
}
