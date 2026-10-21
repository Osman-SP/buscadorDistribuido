package com.search.controller;

import com.search.model.IndexRequest;
import com.search.service.InvertedIndex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final InvertedIndex localIndex;
    private final RestClient restClient;

    @Value("${server.port}")
    private int currentPort;

    // Lista dinámica y thread-safe de todos los puertos activos en el clúster
    private final Set<Integer> activeClusterPorts = new ConcurrentSkipListSet<>();

    public SearchController(InvertedIndex localIndex) {
        this.localIndex = localIndex;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1500);
        factory.setReadTimeout(1500);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    // Se ejecuta automáticamente en cuanto el servidor termina de encender
    @EventListener(ApplicationReadyEvent.class)
    public void registerWithExistingCluster() {
        activeClusterPorts.add(currentPort);
        String existingPeersProp = System.getProperty("initial.peers", "");
        
        if (!existingPeersProp.isBlank()) {
            for (String pStr : existingPeersProp.split(",")) {
                int peerPort = Integer.parseInt(pStr);
                activeClusterPorts.add(peerPort);
                try {
                    // Le avisa al nodo existente que este nuevo servidor se acaba de unir
                    List<Integer> peerKnownNodes = restClient.post()
                            .uri("http://localhost:" + peerPort + "/api/peers/register?port=" + currentPort)
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
                    if (peerKnownNodes != null) {
                        activeClusterPorts.addAll(peerKnownNodes);
                    }
                } catch (Exception ignored) {}
            }
        }
        System.out.println("=== Nodo " + currentPort + " sincronizado. Clúster actual: " + activeClusterPorts + " ===");
    }

    // Endpoint para que nuevos servidores se anuncien y sincronicen la lista de nodos
    @PostMapping("/peers/register")
    public Set<Integer> registerPeer(@RequestParam("port") int newPort) {
        activeClusterPorts.add(newPort);
        System.out.println("[+] Nuevo nodo detectado en el clúster: Puerto " + newPort + " | Total activos: " + activeClusterPorts);
        return activeClusterPorts;
    }

    // Endpoint para que el cliente consulte todos los servidores disponibles
    @GetMapping("/peers")
    public Set<Integer> getClusterPeers() {
        return activeClusterPorts;
    }

    @PostMapping("/index")
    public ResponseEntity<String> indexDocument(@RequestBody IndexRequest request) {
        if (request.docName() == null || request.text() == null) {
            return ResponseEntity.badRequest().body("ERROR Formato invalido");
        }
        for (String token : request.text().split("\\s+")) {
            localIndex.addTerm(token, request.docName());
        }
        return ResponseEntity.ok("OK");
    }

    @GetMapping("/search/internal")
    public Map<String, Map<String, Integer>> internalSearch(@RequestParam("q") String query) {
        Map<String, Map<String, Integer>> localResults = new ConcurrentHashMap<>();
        if (query == null || query.isBlank()) return localResults;

        for (String term : query.trim().split("\\s+")) {
            localResults.put(term, new HashMap<>(localIndex.search(term)));
        }
        return localResults;
    }

    @GetMapping("/search")
    public Map<String, Map<String, Integer>> clusterSearch(@RequestParam("q") String query) {
        Map<String, Map<String, Integer>> consolidatedMap = new ConcurrentHashMap<>();
        if (query == null || query.isBlank()) return consolidatedMap;

        mergeMaps(internalSearch(query), consolidatedMap);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int peerPort : activeClusterPorts) {
            if (peerPort == currentPort) continue;

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    String url = "http://localhost:" + peerPort + "/api/search/internal?q={query}";
                    Map<String, Map<String, Integer>> peerResults = restClient.get()
                            .uri(url, query)
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});

                    if (peerResults != null) {
                        mergeMaps(peerResults, consolidatedMap);
                    }
                } catch (Exception e) {
                    System.err.println("[!] Par inalcanzable en puerto " + peerPort + ". Removiendo de la lista activa.");
                    activeClusterPorts.remove(peerPort); // Auto-limpieza si un nodo se apaga
                }
            });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return consolidatedMap;
    }

    private void mergeMaps(Map<String, Map<String, Integer>> source, Map<String, Map<String, Integer>> target) {
        source.forEach((term, docMap) -> {
            Map<String, Integer> targetDocMap = target.computeIfAbsent(term, k -> new ConcurrentHashMap<>());
            docMap.forEach((doc, count) -> targetDocMap.merge(doc, count, Integer::sum));
        });
    }
}