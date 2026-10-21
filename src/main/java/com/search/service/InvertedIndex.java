package com.search.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InvertedIndex {
    // Es final para asegurar que la referencia en memoria no se reasigne
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> index = new ConcurrentHashMap<>();

    public void addTerm(String word, String documentName) {
        // Convierte la palabra a minúsculas y elimina acentos/símbolos especiales dejando solo alfanuméricos
        String cleanWord = word.toLowerCase().replaceAll("[^a-záéíóúüñ0-9]", "");
        
        if (cleanWord.isBlank()) return;

        // computeIfAbsent: si el término no está en el mapa, crea atómicamente un nuevo ConcurrentHashMap para él
        index.computeIfAbsent(cleanWord, k -> new ConcurrentHashMap<>())
             // si el documento ya existe en el submapa, suma 1 al conteo actual; si no existe, lo inicializa en 1
             .merge(documentName, 1, Integer::sum);
    }

    public Map<String, Integer> search(String word) {
        // Normaliza el término de búsqueda quitando espacios sobrantes y convirtiendo a minúsculas
        String term = word.toLowerCase().trim();
        
        // Obtiene la referencia al submapa de documentos de esa palabra
        Map<String, Integer> result = index.get(term);
        
        return result != null ? result : Collections.emptyMap();
    }

    // exportar a un archivo de texto
    public void saveToFile(File file) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (Map.Entry<String, ConcurrentHashMap<String, Integer>> entry : index.entrySet()) {
                StringBuilder line = new StringBuilder(entry.getKey()).append(":");

                entry.getValue().forEach((doc, count) -> line.append(doc).append("=").append(count).append(","));
                
                if (line.charAt(line.length() - 1) == ',') {
                    line.deleteCharAt(line.length() - 1);
                }
                writer.write(line.toString());
                writer.newLine();
            }
        } 
    }

    public static InvertedIndex loadFromFile(File file) throws IOException {
        InvertedIndex loaded = new InvertedIndex();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.contains(":")) continue;
                String[] parts = line.split(":", 2);
                String word = parts[0];
                String[] docs = parts[1].split(",");
                // ProcesaR cada par "documento=frecuencia"
                for (String docEntry : docs) {
                    String[] kv = docEntry.split("=");

                    if (kv.length == 2) {
                        String docName = kv[0];
                        int count = Integer.parseInt(kv[1]);
                        
                        loaded.index.computeIfAbsent(word, k -> new ConcurrentHashMap<>())
                                    .put(docName, count);
                    }
                }
            }
        } 
        return loaded;
    }
}
