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
    private static final int BASE_PORT = 5001;
    private static final int MAX_SCAN_RANGE = 20;

    private final RestClient restClient = RestClient.create();
    // Lista thread-safe para que múltiples hilos consulten y actualicen los nodos vivos
    private final List<Integer> knownServerPorts = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private int activeCoordinatorPort = -1;

    public void run() {
        if (!discoverCluster()) {
            System.err.println("No se encontró ningún servidor activo (inicia al menos uno desde SearchServerApp).");
            return;
        }

        indexCorpusFilesDistributed();

        Scanner scanner = new Scanner(System.in);
        System.out.println("\n=== Modo de Búsqueda Web (Clúster Distribuido) ===");
        while (true) {
            System.out.print("\nIngrese término(s) a buscar (o 'salir'): ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("salir")) break;
            if (input.isBlank()) continue;

            Map<String, Map<String, Integer>> results = searchWithFailover(input);
            if (results == null) {
                System.err.println("Error crítico: Todos los servidores del clúster se han desconectado.");
                break;
            }
            displayResults(results);
        }
        scanner.close();
    }

    // Descubre todos los nodos activos en el clúster
    private synchronized boolean discoverCluster() {
        if (knownServerPorts.isEmpty()) {
            for (int p = BASE_PORT; p < BASE_PORT + MAX_SCAN_RANGE; p++) {
                try {
                    Set<Integer> clusterPeers = restClient.get()
                            .uri("http://localhost:" + p + "/api/peers")
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
                    if (clusterPeers != null && !clusterPeers.isEmpty()) {
                        knownServerPorts.addAll(clusterPeers);
                        System.out.println("[i] Clúster descubierto con " + knownServerPorts.size() + " nodos: " + knownServerPorts);
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }

        // Verifica y sincroniza con el primer nodo que responda
        for (Integer port : new ArrayList<>(knownServerPorts)) {
            try {
                Set<Integer> updatedPeers = restClient.get()
                        .uri("http://localhost:" + port + "/api/peers")
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                if (updatedPeers != null) {
                    for (int up : updatedPeers) {
                        if (!knownServerPorts.contains(up)) knownServerPorts.add(up);
                    }
                }
                activeCoordinatorPort = port;
                return true;
            } catch (Exception e) {
                System.out.println("[!] Servidor en puerto " + port + " inactivo. Descartando...");
                knownServerPorts.remove(port);
            }
        }
        return false;
    }

    // Selecciona el siguiente servidor en turno (Round-Robin) para repartir la carga de RAM y CPU
    private int getNextBalancedPort() {
        if (knownServerPorts.isEmpty() && !discoverCluster()) {
            return -1;
        }
        int index = Math.abs(roundRobinCounter.getAndIncrement() % knownServerPorts.size());
        return knownServerPorts.get(index);
    }

    // Envía el documento al nodo asignado; si ese nodo falla, hace failover a otro nodo vivo
    private void sendIndexBalancedWithFailover(IndexRequest payload) {
        while (!knownServerPorts.isEmpty() || discoverCluster()) {
            int targetPort = getNextBalancedPort();
            if (targetPort == -1) return;

            try {
                restClient.post()
                        .uri("http://localhost:" + targetPort + "/api/index")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                System.out.println("  -> [" + payload.docName() + "] indexado en el nodo :" + targetPort);
                return;
            } catch (Exception e) {
                System.err.println("[!] Fallo al indexar en puerto " + targetPort + ". Rebalanceando a otro servidor...");
                knownServerPorts.remove(Integer.valueOf(targetPort));
            }
        }
    }

    private Map<String, Map<String, Integer>> searchWithFailover(String query) {
        while (!knownServerPorts.isEmpty() || discoverCluster()) {
            // También podemos rotar qué servidor actúa como coordinador en cada búsqueda
            int coordinatorPort = getNextBalancedPort();
            if (coordinatorPort == -1) return null;

            try {
                System.out.println("[i] Consultando a través del nodo coordinador :" + coordinatorPort);
                return restClient.get()
                        .uri("http://localhost:" + coordinatorPort + "/api/search?q={q}", query)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
            } catch (Exception e) {
                System.err.println("[!] Nodo coordinador " + coordinatorPort + " caído. Aplicando failover...");
                knownServerPorts.remove(Integer.valueOf(coordinatorPort));
            }
        }
        return null;
    }

    // Reparte e indexa los archivos en paralelo entre todos los servidores del clúster
    private void indexCorpusFilesDistributed() {
        File folder = new File("corpus");
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".txt"));
        if (files == null || files.length == 0) {
            System.out.println("[Aviso] No se encontraron archivos .txt en la carpeta 'corpus'.");
            return;
        }

        System.out.println("Distribuido e indexando " + files.length + " archivos entre " + knownServerPorts.size() + " servidores...");
        long startTime = System.currentTimeMillis();
        List<Thread> workers = new ArrayList<>();

        for (File file : files) {
            Thread t = new Thread(() -> {
                try {
                    // Lee el archivo completo de una sola vez para hacer 1 petición HTTP por archivo
                    String content = Files.readString(file.toPath());
                    if (!content.isBlank()) {
                        sendIndexBalancedWithFailover(new IndexRequest(file.getName(), content));
                    }
                } catch (IOException e) {
                    System.err.println("Error leyendo " + file.getName() + ": " + e.getMessage());
                }
            });
            workers.add(t);
            t.start();
        }

        for (Thread t : workers) {
            try { t.join(); } catch (InterruptedException ignored) {}
        }
        System.out.println("Indexación distribuida completada en " + (System.currentTimeMillis() - startTime) + " ms.");
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