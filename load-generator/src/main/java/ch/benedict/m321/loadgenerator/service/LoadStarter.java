package ch.benedict.m321.loadgenerator.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startet den Lastversuch, sobald Spring fertig hochgefahren ist.
 *
 * Ein ApplicationRunner wird von Spring genau einmal nach dem Start
 * aufgerufen. Ist er fertig und läuft sonst nichts mehr, beendet sich das
 * Programm — und damit der Container.
 */
@Component
@RequiredArgsConstructor
public class LoadStarter implements ApplicationRunner {

    private final LoadRunner loadRunner;

    @Override
    public void run(ApplicationArguments arguments) throws InterruptedException {
        loadRunner.run();
    }
}
