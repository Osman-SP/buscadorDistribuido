const CACHE_NAME = 'buscador-pwa-v1';
const urlsToCache = [
  '/',
  '/index.html',
  '/app.js',
  '/manifest.json'
];

// Instalación: Guardar archivos estáticos en caché
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(urlsToCache))
  );
});

// Interceptar peticiones
self.addEventListener('fetch', event => {
  const requestUrl = new URL(event.request.url);

  // Estrategia Network First para las consultas a tu SearchController
  if (requestUrl.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(event.request)
        .catch(() => caches.match(event.request)) // Si no hay internet, intenta buscar en caché si se buscó antes
    );
  } else {
    // Estrategia Cache First para HTML, JS, CSS
    event.respondWith(
      caches.match(event.request).then(response => {
        return response || fetch(event.request);
      })
    );
  }
});