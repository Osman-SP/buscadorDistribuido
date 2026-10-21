package com.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@SpringBootApplication
public class SearchServerApp {
    private static final int BASE_PORT = 5001;

    public static void main(String[] args) {
        int candidatePort = BASE_PORT;
        List<Integer> detectedPeers = new ArrayList<>();

        // Busca hacia arriba (5001, 5002, 5003... N) hasta hallar el primer puerto libre
        while (true) {
            try (ServerSocket test = new ServerSocket(candidatePort)) {
                break; // Encontró un puerto libre, sale del ciclo
            } catch (IOException e) {
                // Está ocupado por otro nodo, lo guarda como par activo y prueba el siguiente
                detectedPeers.add(candidatePort);
                candidatePort++;
            }
        }

        // Pasa la lista de nodos previos para que SearchController se registre con ellos al arrancar
        String peersCsv = detectedPeers.stream().map(String::valueOf).collect(Collectors.joining(","));
        System.setProperty("initial.peers", peersCsv);

        SpringApplication app = new SpringApplication(SearchServerApp.class);
        app.setDefaultProperties(Collections.singletonMap("server.port", String.valueOf(candidatePort)));
        app.run(args);
    }
}