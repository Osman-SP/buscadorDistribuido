package com.search.client;

import com.search.model.IndexRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class RestClientApp {
    private static final List<String> CLOUD_SEED_URLS = Arrays.asList(
        "https://buscador-nodo-1.onrender.com"
    );

    private final RestClient restClient = RestClient.create();
    private final List<String> knownServerUrls = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public void run() {
        if (!discoverCluster()) {
            System.err.println("No se encontró ningún servidor activo.");
            return;
        }

        indexCorpusFilesDistributed();

        Scanner scanner = new Scanner(System.in);
        System.out.println("\n=== Modo de Búsqueda Web (Clúster en Nube / Local) ===");
        while (true) {
            System.out.print("\nIngrese término(s) a buscar (o 'salir'): ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("salir")) break;
            if (input.isBlank()) continue;

            Map<String, Map<String, Integer>> results = searchWithFailover(input);
            if (results == null) {
                System.err.println("Error crítico: Todos los servidores del clúster están inactivos.");
                break;
            }
            displayResults(results);
        }
        scanner.close();
    }

    private synchronized boolean discoverCluster() {
        if (knownServerUrls.isEmpty()) {
            // Si pusiste URLs de Render las usa; si no, escanea puertos locales 5001..5010
            List<String> candidates = new ArrayList<>(CLOUD_SEED_URLS);
            if (candidates.isEmpty()) {
                for (int p = 5001; p <= 5010; p++) {
                    candidates.add("http://localhost:" + p);
                }
            }

            for (String candidateUrl : candidates) {
                try {
                    Set<String> clusterPeers = restClient.get()
                            .uri(candidateUrl + "/api/peers")
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
                    if (clusterPeers != null && !clusterPeers.isEmpty()) {
                        for (String peer : clusterPeers) {
                            if (!knownServerUrls.contains(peer)) knownServerUrls.add(peer);
                        }
                        System.out.println("[i] Clúster descubierto (" + knownServerUrls.size() + " nodos): " + knownServerUrls);
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }

        for (String url : new ArrayList<>(knownServerUrls)) {
            try {
                Set<String> updatedPeers = restClient.get()
                        .uri(url + "/api/peers")
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                if (updatedPeers != null) {
                    for (String up : updatedPeers) {
                        if (!knownServerUrls.contains(up)) knownServerUrls.add(up);
                    }
                }
                return true;
            } catch (Exception e) {
                System.out.println("[!] Servidor inactivo: " + url + ". Descartando...");
                knownServerUrls.remove(url);
            }
        }
        return false;
    }

    private String getNextBalancedUrl() {
        if (knownServerUrls.isEmpty() && !discoverCluster()) return null;
        int index = Math.abs(roundRobinCounter.getAndIncrement() % knownServerUrls.size());
        return knownServerUrls.get(index);
    }

    private void sendIndexBalancedWithFailover(IndexRequest payload) {
        while (!knownServerUrls.isEmpty() || discoverCluster()) {
            String targetUrl = getNextBalancedUrl();
            if (targetUrl == null) return;

            try {
                restClient.post()
                        .uri(targetUrl + "/api/index")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                System.out.println("  -> [" + payload.docName() + "] indexado en " + targetUrl);
                return;
            } catch (Exception e) {
                System.err.println("[!] Fallo al indexar en " + targetUrl + ". Rebalanceando...");
                knownServerUrls.remove(targetUrl);
            }
        }
    }

    private Map<String, Map<String, Integer>> searchWithFailover(String query) {
        while (!knownServerUrls.isEmpty() || discoverCluster()) {
            String coordinatorUrl = getNextBalancedUrl();
            if (coordinatorUrl == null) return null;

            try {
                System.out.println("[i] Consultando al nodo coordinador: " + coordinatorUrl);
                return restClient.get()
                        .uri(coordinatorUrl + "/api/search?q={q}", query)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
            } catch (Exception e) {
                System.err.println("[!] Nodo coordinador caído (" + coordinatorUrl + "). Aplicando failover...");
                knownServerUrls.remove(coordinatorUrl);
            }
        }
        return null;
    }

    private void indexCorpusFilesDistributed() {
        File folder = new File("corpus");
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".txt"));
        if (files == null || files.length == 0) return;

        System.out.println("Distribuyendo e indexando " + files.length + " archivos en la nube...");
        long startTime = System.currentTimeMillis();
        List<Thread> workers = new ArrayList<>();

        for (File file : files) {
            Thread t = new Thread(() -> {
                try {
                    String content = Files.readString(file.toPath());
                    if (!content.isBlank()) {
                        sendIndexBalancedWithFailover(new IndexRequest(file.getName(), content));
                    }
                } catch (IOException e) {
                    System.err.println("Error leyendo " + file.getName());
                }
            });
            workers.add(t);
            t.start();
        }

        for (Thread t : workers) {
            try { t.join(); } catch (InterruptedException ignored) {}
        }
        System.out.println("Indexación completada en " + (System.currentTimeMillis() - startTime) + " ms.");
    }

    private void displayResults(Map<String, Map<String, Integer>> results) {
        results.forEach((term, docs) -> {
            System.out.println("\nResultados consolidados para '" + term + "':");
            if (docs == null || docs.isEmpty()) {
                System.out.println("  (Sin coincidencias en ningún servidor del clúster)");
            } else {
                docs.forEach((doc, count) ->
                        System.out.println("  -> Documento: " + doc + " | Ocurrencias: " + count)
                );
            }
        });
    }

    public static void main(String[] args) {
        new RestClientApp().run();
    }
}