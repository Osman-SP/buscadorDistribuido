// 1. Registrar el Service Worker
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js')
      .then(registration => console.log('Service Worker registrado con éxito:', registration.scope))
      .catch(error => console.log('Fallo al registrar el Service Worker:', error));
  });
}

// 2. Lógica para consumir tu API actual
async function realizarBusqueda() {
  const query = document.getElementById('searchInput').value;
  const resultsDiv = document.getElementById('results');
  resultsDiv.innerHTML = 'Buscando...';

  if (!query.trim()) {
      resultsDiv.innerHTML = '';
      return;
  }

  try {
    // Llama al clusterSearch mapeado en SearchController.java
    const response = await fetch(`/api/search?q=${encodeURIComponent(query)}`);
    const data = await response.json();
    
    resultsDiv.innerHTML = '';
    
    // Renderizar el Map<String, Map<String, Integer>> devuelto por clusterSearch
    for (const [term, docs] of Object.entries(data)) {
        let html = `<h3>Resultados para '${term}':</h3><ul>`;
        if (Object.keys(docs).length === 0) {
            html += `<li>Sin coincidencias en ningún servidor del clúster</li>`;
        } else {
            for (const [doc, count] of Object.entries(docs)) {
                html += `<li>Documento: ${doc} | Ocurrencias: ${count}</li>`;
            }
        }
        html += `</ul>`;
        resultsDiv.innerHTML += html;
    }
  } catch (error) {
    resultsDiv.innerHTML = '<p>Error de red o clúster inalcanzable. Revisa tu conexión.</p>';
  }
}