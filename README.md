Motor de Búsqueda Distribuido con Servicios Web REST
Este proyecto implementa un motor de búsqueda distribuido basado en un Índice Invertido en memoria, migrado desde una arquitectura de Sockets TCP hacia Servicios Web RESTful desarrollados en Java con Spring Boot. El sistema soporta un clúster escalable con autodescubrimiento de nodos, balanceo de carga Round-Robin, búsqueda paralela Scatter-Gather y tolerancia a fallos (failover).   
JAVA

🚀 Características Principales
Arquitectura RESTful: Comunicación mediante protocolo HTTP/HTTPS utilizando intercambio de datos en formato JSON.

Clúster Dinámico y Auto-descubrible: Los nodos se registran automáticamente al iniciar mediante el endpoint /api/peers.

Balanceo de Carga (Round-Robin): El cliente distribuye equitativamente los archivos .txt del corpus entre todos los nodos activos del clúster.

Búsqueda Distribuida (Scatter-Gather): Un nodo coordinador dispersa la consulta a los nodos pares y consolida los resultados de frecuencia de manera asíncrona.   
JAVA

Tolerancia a Fallos (Failover): Si un nodo del clúster o el coordinador cae, el sistema redirige automáticamente la petición a los servidores disponibles.   
JAVA

Despliegue en la Nube: Contenedorizado con Docker y desplegado en servidores públicos mediante Render.   
PNG

📂 Estructura del Proyecto
Plaintext
buscadorDistribuido/
├── corpus/                 # Archivos de texto plano (.txt) a indexar
├── Dockerfile              # Configuración para despliegue en contenedores (Render/Docker)
├── pom.xml                 # Dependencias y configuración de Maven para Spring Boot
└── src/
    └── main/
        └── java/
            └── com/
                └── search/
                    ├── SearchServerApp.java      # Arranque de los nodos servidores
                    ├── client/
                    │   └── RestClientApp.java    # Cliente con balanceo y failover
                    ├── controller/
                    │   └── SearchController.java # Endpoints REST (/index, /search, /peers)
                    ├── model/
                    │   └── IndexRequest.java     # DTO para las solicitudes HTTP
                    └── service/
                        └── InvertedIndex.java    # Lógica del índice invertido concurrente
🛠️ Tecnologías Utilizadas
Java 17

Spring Boot 3.2.5 (Spring Web, Tomcat embebido, RestClient)

Maven (Gestión de dependencias y empaquetado)

Docker
  
PNG

Git y GitHub

Render (Hosting en la nube)   

🌐 Enlaces del Clúster en la Nube (Render)
Nodo 1: [https://buscador-nodo-1.onrender.com]

Nodo 2: [https://buscador-nodo-2.onrender.com]

💻 Instrucciones de Ejecución
1. Ejecución Local (con NetBeans / IntelliJ / VS Code)
Clona el repositorio:

git clone https://github.com/Osman-SP/buscadorDistribuido.git
Asegúrate de tener una carpeta llamada corpus en la raíz con archivos .txt.

Compila y descarga dependencias con Maven:

mvn clean install
Levantar los Nodos: Ejecuta la clase SearchServerApp.java tantas veces como desees. Automáticamente detectarán puertos libres secuenciales y se sincronizarán.   

Ejecutar el Cliente: Configura las URLs en RestClientApp.java y ejecuta la clase para indexar y buscar términos en consola.   

2. Variables de Entorno para la Nube (Render / Docker)
Si despliegas este proyecto en un servicio de hosting, configura las siguientes variables de entorno:

SELF_URL: La URL pública asignada al nodo (ej. [https://tu-nodo.onrender.com](https://tu-nodo.onrender.com)).   

INITIAL_PEERS: URLs de los nodos pares separadas por coma para el autodescubrimiento inicial.

Desarrollado como parte de la evolución de arquitecturas de sistemas distribuidos.
