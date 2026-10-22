package com.search.controller;

import com.search.model.IndexRequest;
import com.search.service.InvertedIndex;
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
    private String selfUrl;

    // Guarda las URLs completas (ej. "https://nodo1.onrender.com" o "http://localhost:5001")
    private final Set<String> activeClusterUrls = new ConcurrentSkipListSet<>();

    public SearchController(InvertedIndex localIndex) {
        this.localIndex = localIndex;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); // 5s para tolerar latencia en la nube
        factory.setReadTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWithExistingCluster() {
        this.selfUrl = System.getProperty("self.url", "http://localhost:5001").replaceAll("/$", "");
        activeClusterUrls.add(this.selfUrl);

        String existingPeersProp = System.getProperty("initial.peers", "");
        if (!existingPeersProp.isBlank()) {
            for (String peer : existingPeersProp.split(",")) {
                String cleanPeer = peer.trim().replaceAll("/$", "");
                if (cleanPeer.isEmpty() || cleanPeer.equals(this.selfUrl)) continue;

                activeClusterUrls.add(cleanPeer);
                try {
                    Set<String> peerKnownNodes = restClient.post()
                            .uri(cleanPeer + "/api/peers/register?url={url}", this.selfUrl)
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});
                    if (peerKnownNodes != null) {
                        activeClusterUrls.addAll(peerKnownNodes);
                    }
                } catch (Exception ignored) {}
            }
        }
        System.out.println("=== Nodo activo en: " + selfUrl + " | Clúster: " + activeClusterUrls + " ===");
    }

    @PostMapping("/peers/register")
    public Set<String> registerPeer(@RequestParam("url") String newUrl) {
        String cleanUrl = newUrl.trim().replaceAll("/$", "");
        activeClusterUrls.add(cleanUrl);
        System.out.println("[+] Nuevo nodo registrado en el clúster: " + cleanUrl);
        return activeClusterUrls;
    }

    @GetMapping("/peers")
    public Set<String> getClusterPeers() {
        return activeClusterUrls;
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

        for (String peerUrl : activeClusterUrls) {
            if (peerUrl.equals(this.selfUrl)) continue;

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    String url = peerUrl + "/api/search/internal?q={query}";
                    Map<String, Map<String, Integer>> peerResults = restClient.get()
                            .uri(url, query)
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});

                    if (peerResults != null) {
                        mergeMaps(peerResults, consolidatedMap);
                    }
                } catch (Exception e) {
                    System.err.println("[!] Par inalcanzable: " + peerUrl);
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