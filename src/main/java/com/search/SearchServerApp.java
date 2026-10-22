package com.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.*;

@SpringBootApplication
public class SearchServerApp {
    private static final int BASE_PORT = 5001;

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SearchServerApp.class);
        
        // Render inyecta automáticamente la variable de entorno PORT
        String cloudPort = System.getenv("PORT");
        String selfUrl = System.getenv("SELF_URL"); // Ej: https://buscador-nodo1.onrender.com
        String peersEnv = System.getenv("INITIAL_PEERS"); // URLs separadas por coma

        if (cloudPort != null) {
            // MODO NUBE (Render)
            app.setDefaultProperties(Collections.singletonMap("server.port", cloudPort));
            System.setProperty("self.url", selfUrl != null ? selfUrl : "http://localhost:" + cloudPort);
            System.setProperty("initial.peers", peersEnv != null ? peersEnv : "");
        } else {
            // MODO LOCAL (NetBeans)
            int candidatePort = BASE_PORT;
            List<String> detectedPeers = new ArrayList<>();

            while (true) {
                try (ServerSocket test = new ServerSocket(candidatePort)) {
                    break;
                } catch (IOException e) {
                    detectedPeers.add("http://localhost:" + candidatePort);
                    candidatePort++;
                }
            }
            app.setDefaultProperties(Collections.singletonMap("server.port", String.valueOf(candidatePort)));
            System.setProperty("self.url", "http://localhost:" + candidatePort);
            System.setProperty("initial.peers", String.join(",", detectedPeers));
        }

        app.run(args);
    }
}