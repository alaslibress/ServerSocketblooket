package org.hlanz.servidor;

import javax.net.ssl.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor principal del juego tipo Blooket/Kahoot.
 *
 * Flujo del servidor:
 * 1. Configuracion inicial (tiempo, cantidad de preguntas, origen CSV)
 * 2. Carga de preguntas desde CSV (local o FTP)
 * 3. Apertura de socket SSL/TLS en puerto 8080
 * 4. Sala de espera: los clientes se conectan y registran nombre
 * 5. El admin indica que esta listo para empezar
 * 6. Bucle de preguntas: envia pregunta -> espera tiempo -> calcula puntos
 * 7. Al terminar, muestra ranking final
 *
 * Peticiones HTTP soportadas (procesadas por ManejadorCliente):
 *   POST /registro       -> Registrar nombre de jugador
 *   GET  /pregunta        -> Obtener la pregunta actual
 *   POST /respuesta       -> Enviar respuesta (1 char: A, B, C, D)
 *   GET  /ranking         -> Obtener ranking actual
 *   GET  /estado          -> Obtener estado del juego (ESPERANDO/EN_CURSO/TERMINADA)
 *   GET  /esperando       -> Polling en sala de espera
 */
public class ServidorJuego {

    // Configuracion
    private static final int PUERTO = 8080;
    private int tiempoRespuesta = 0;        // segundos para responder (se configura al inicio)
    private int cantidadPreguntas = 0;       // cuantas preguntas lanzar
    private List<Pregunta> todasLasPreguntas;
    private List<Pregunta> preguntasSeleccionadas;

    // Estado del juego
    private volatile boolean partidaIniciada = false;
    private volatile boolean partidaTerminada = false;
    private volatile Pregunta preguntaActual = null;
    private volatile int numPreguntaActual = 0;

    // Jugadores
    private final CopyOnWriteArrayList<Jugador> jugadores = new CopyOnWriteArrayList<>();
    private final ValidadorNombre validadorNombre = new ValidadorNombre();

    // Control de respuestas por ronda
    private final AtomicInteger respuestasRecibidas = new AtomicInteger(0);
    private final List<Jugador> ordenRespuestas = Collections.synchronizedList(new ArrayList<>());

    // Control de NEXT
    private volatile boolean nextSolicitado = false;

    // SSL
    private SSLServerSocket serverSocket;

    public static void main(String[] args) {
        ServidorJuego servidor = new ServidorJuego();
        servidor.iniciar();
    }

    public void iniciar() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("========================================");
        System.out.println("   SERVIDOR BLOOKET - CONFIGURACION");
        System.out.println("========================================");
        System.out.println();

        // === CONFIGURAR ORIGEN DE PREGUNTAS ===
        System.out.println("Origen de las preguntas:");
        System.out.println("  1) Archivo CSV local");
        System.out.println("  2) Servidor FTP remoto");
        System.out.print("Seleccione opcion (1/2): ");
        String opcionOrigen = scanner.nextLine().trim();

        try {
            if ("2".equals(opcionOrigen)) {
                // Cargar desde FTP
                System.out.print("Host FTP (ej: 80.225.190.216): ");
                String ftpHost = scanner.nextLine().trim();
                System.out.print("Puerto FTP (21): ");
                String ftpPuertoStr = scanner.nextLine().trim();
                int ftpPuerto = ftpPuertoStr.isEmpty() ? 21 : Integer.parseInt(ftpPuertoStr);
                System.out.print("Usuario FTP: ");
                String ftpUser = scanner.nextLine().trim();
                System.out.print("Password FTP: ");
                String ftpPass = scanner.nextLine().trim();
                System.out.print("Ruta del archivo CSV en FTP (ej: /preguntas.csv): ");
                String ftpRuta = scanner.nextLine().trim();

                todasLasPreguntas = CargadorPreguntas.cargarDesdeFTP(ftpHost, ftpPuerto, ftpUser, ftpPass, ftpRuta);
            } else {
                // Cargar desde local
                System.out.print("Ruta del archivo CSV (Enter para usar por defecto): ");
                String rutaCsv = scanner.nextLine().trim();
                if (rutaCsv.isEmpty()) {
                    // Intentar cargar desde resources
                    rutaCsv = "preguntas.csv";
                    // Buscar en classpath o directorio actual
                    File f = new File(rutaCsv);
                    if (!f.exists()) {
                        // Buscar en src/main/resources
                        f = new File("src/main/resources/preguntas.csv");
                        if (f.exists()) {
                            rutaCsv = f.getAbsolutePath();
                        }
                    }
                }
                todasLasPreguntas = CargadorPreguntas.cargarDesdeLocal(rutaCsv);
            }
        } catch (IOException e) {
            System.err.println("Error al cargar preguntas: " + e.getMessage());
            System.err.println("Intentando cargar desde archivo local por defecto...");
            try {
                todasLasPreguntas = CargadorPreguntas.cargarDesdeLocal("preguntas.csv");
            } catch (IOException ex) {
                System.err.println("Error fatal: no se pudieron cargar las preguntas.");
                return;
            }
        }

        if (todasLasPreguntas.isEmpty()) {
            System.err.println("No se cargaron preguntas. Saliendo.");
            return;
        }

        System.out.println();
        System.out.println("Se cargaron " + todasLasPreguntas.size() + " preguntas en total.");
        System.out.println();

        // === CONFIGURAR TIEMPO ===
        System.out.print("Tiempo de respuesta por pregunta (segundos): ");
        try {
            tiempoRespuesta = Integer.parseInt(scanner.nextLine().trim());
            if (tiempoRespuesta <= 0) {
                System.out.println("Tiempo invalido. Se usaran 30 segundos por defecto.");
                tiempoRespuesta = 30;
            }
        } catch (NumberFormatException e) {
            System.out.println("Entrada invalida. Se usaran 30 segundos por defecto.");
            tiempoRespuesta = 30;
        }

        // === CONFIGURAR CANTIDAD DE PREGUNTAS ===
        System.out.print("Cuantas preguntas quieres lanzar (max " + todasLasPreguntas.size() + "): ");
        try {
            cantidadPreguntas = Integer.parseInt(scanner.nextLine().trim());
            if (cantidadPreguntas <= 0 || cantidadPreguntas > todasLasPreguntas.size()) {
                System.out.println("Cantidad invalida. Se usaran todas las preguntas.");
                cantidadPreguntas = todasLasPreguntas.size();
            }
        } catch (NumberFormatException e) {
            System.out.println("Entrada invalida. Se usaran todas las preguntas.");
            cantidadPreguntas = todasLasPreguntas.size();
        }

        // Seleccionar preguntas aleatorias
        preguntasSeleccionadas = seleccionarPreguntasAleatorias(cantidadPreguntas);

        System.out.println();
        System.out.println("========================================");
        System.out.println("   CONFIGURACION COMPLETADA");
        System.out.println("   Tiempo por pregunta: " + tiempoRespuesta + "s");
        System.out.println("   Preguntas a realizar: " + cantidadPreguntas);
        System.out.println("========================================");
        System.out.println();

        // === INICIAR SERVIDOR SSL ===
        try {
            iniciarServidorSSL();
        } catch (Exception e) {
            System.err.println("Error al iniciar servidor SSL: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // === SALA DE ESPERA ===
        System.out.println("[SERVIDOR] Servidor escuchando en puerto " + PUERTO + " (SSL/TLS)");
        System.out.println("[SERVIDOR] Esperando conexiones de jugadores...");
        System.out.println("[SERVIDOR] Escribe 'INICIAR' cuando todos los jugadores esten conectados.");
        System.out.println();

        // Hilo para aceptar conexiones
        Thread aceptador = new Thread(() -> aceptarConexiones());
        aceptador.setDaemon(true);
        aceptador.start();

        // Hilo para mostrar jugadores conectados periodicamente
        Thread monitor = new Thread(() -> {
            while (!partidaIniciada) {
                try {
                    Thread.sleep(5000);
                    if (!partidaIniciada && !jugadores.isEmpty()) {
                        System.out.println("[SALA DE ESPERA] Jugadores conectados (" + jugadores.size() + "):");
                        for (Jugador j : jugadores) {
                            System.out.println("   -> " + j.getNombre());
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();

        // Esperar a que el admin escriba INICIAR
        while (true) {
            String comando = scanner.nextLine().trim();
            if ("INICIAR".equalsIgnoreCase(comando)) {
                if (jugadores.isEmpty()) {
                    System.out.println("[SERVIDOR] No hay jugadores conectados. Espera a que se conecten.");
                } else {
                    break;
                }
            } else if ("NEXT".equalsIgnoreCase(comando)) {
                nextSolicitado = true;
                System.out.println("[SERVIDOR] Se forzara el paso a la siguiente pregunta.");
            } else {
                System.out.println("[SERVIDOR] Comando no reconocido. Escribe 'INICIAR' para comenzar.");
            }
        }

        // === INICIAR PARTIDA ===
        System.out.println();
        System.out.println("========================================");
        System.out.println("   PARTIDA INICIADA");
        System.out.println("   Jugadores: " + jugadores.size());
        System.out.println("========================================");
        System.out.println();

        partidaIniciada = true;

        // Hilo para escuchar NEXT durante la partida
        Thread hiloNext = new Thread(() -> {
            while (!partidaTerminada) {
                String cmd = scanner.nextLine().trim();
                if ("NEXT".equalsIgnoreCase(cmd)) {
                    nextSolicitado = true;
                    System.out.println("[SERVIDOR] NEXT: Pasando a la siguiente pregunta...");
                }
            }
        });
        hiloNext.setDaemon(true);
        hiloNext.start();

        // === BUCLE DE PREGUNTAS ===
        for (int i = 0; i < preguntasSeleccionadas.size(); i++) {
            Pregunta pregunta = preguntasSeleccionadas.get(i);
            numPreguntaActual = i + 1;
            preguntaActual = pregunta;
            nextSolicitado = false;

            // Resetear respuestas de todos los jugadores
            respuestasRecibidas.set(0);
            ordenRespuestas.clear();
            for (Jugador j : jugadores) {
                j.resetearRespuesta();
            }

            System.out.println("----------------------------------------");
            System.out.println("PREGUNTA " + numPreguntaActual + "/" + cantidadPreguntas);
            System.out.println(pregunta.getEnunciado());
            System.out.println(pregunta.getOpcionA());
            System.out.println(pregunta.getOpcionB());
            System.out.println(pregunta.getOpcionC());
            System.out.println(pregunta.getOpcionD());
            System.out.println("Respuesta correcta: " + pregunta.getRespuestaCorrecta());
            System.out.println("Esperando " + tiempoRespuesta + " segundos...");
            System.out.println("(Escribe NEXT para pasar a la siguiente)");
            System.out.println();

            // Esperar tiempo o hasta que todos respondan o NEXT
            long inicio = System.currentTimeMillis();
            long tiempoLimiteMs = tiempoRespuesta * 1000L;

            while (true) {
                long transcurrido = System.currentTimeMillis() - inicio;

                // Condiciones de salida
                if (transcurrido >= tiempoLimiteMs) {
                    System.out.println("[SERVIDOR] Tiempo agotado!");
                    break;
                }
                if (respuestasRecibidas.get() >= jugadores.size()) {
                    System.out.println("[SERVIDOR] Todos los jugadores han respondido!");
                    break;
                }
                if (nextSolicitado) {
                    System.out.println("[SERVIDOR] NEXT activado por el administrador.");
                    break;
                }

                try {
                    Thread.sleep(200); // polling cada 200ms
                } catch (InterruptedException e) {
                    break;
                }
            }

            // Calcular puntuaciones de esta ronda
            calcularPuntuacionesRonda(pregunta);

            // Mostrar resultados de la ronda
            System.out.println();
            System.out.println("--- Resultados de la ronda ---");
            mostrarResultadosRonda(pregunta);
            System.out.println();

            // Pausa entre preguntas
            if (i < preguntasSeleccionadas.size() - 1) {
                try {
                    Thread.sleep(3000); // 3 segundos entre preguntas
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        // === FIN DE LA PARTIDA ===
        partidaTerminada = true;
        preguntaActual = null;

        System.out.println();
        System.out.println("========================================");
        System.out.println("   PARTIDA TERMINADA");
        System.out.println("========================================");
        System.out.println();
        System.out.println(obtenerRanking());

        // Mantener servidor abierto para que los clientes puedan consultar ranking
        System.out.println("[SERVIDOR] Los clientes pueden consultar el ranking final.");
        System.out.println("[SERVIDOR] Pulsa Enter para cerrar el servidor.");
        scanner.nextLine();
        cerrarServidor();
    }

    /**
     * Inicializa el servidor SSL/TLS con un keystore autogenerado.
     */
    private void iniciarServidorSSL() throws Exception {
        // Generar keystore si no existe
        File keystoreFile = new File("servidor_keystore.jks");
        String keystorePassword = "blooket2024";

        if (!keystoreFile.exists()) {
            System.out.println("[SSL] Generando certificado SSL autofirmado...");
            ProcessBuilder pb = new ProcessBuilder(
                    "keytool", "-genkeypair",
                    "-alias", "servidor",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-validity", "365",
                    "-keystore", "servidor_keystore.jks",
                    "-storepass", keystorePassword,
                    "-keypass", keystorePassword,
                    "-dname", "CN=BlooketServer,OU=Game,O=HLANZ,L=Local,ST=Local,C=ES"
            );
            pb.inheritIO();
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new Exception("Error generando keystore (exit code: " + exitCode + ")");
            }
            System.out.println("[SSL] Certificado generado correctamente.");
        }

        // Cargar keystore
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(keystoreFile)) {
            keyStore.load(fis, keystorePassword.toCharArray());
        }

        // Configurar KeyManager
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword.toCharArray());

        // Configurar TrustManager (aceptar todos para desarrollo)
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        // Crear SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        // Crear SSLServerSocket
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        serverSocket = (SSLServerSocket) factory.createServerSocket(PUERTO);
        serverSocket.setNeedClientAuth(false);

        System.out.println("[SSL] Servidor SSL/TLS configurado en puerto " + PUERTO);
    }

    /**
     * Acepta conexiones de clientes en un bucle.
     */
    private void aceptarConexiones() {
        while (!serverSocket.isClosed()) {
            try {
                SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                ManejadorCliente manejador = new ManejadorCliente(clientSocket, this);
                Thread hiloCliente = new Thread(manejador);
                hiloCliente.setDaemon(true);
                hiloCliente.start();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.err.println("[SERVIDOR] Error aceptando conexion: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Selecciona preguntas aleatorias de la lista total.
     */
    private List<Pregunta> seleccionarPreguntasAleatorias(int cantidad) {
        List<Pregunta> copia = new ArrayList<>(todasLasPreguntas);
        Collections.shuffle(copia);
        return copia.subList(0, Math.min(cantidad, copia.size()));
    }

    /**
     * Calcula las puntuaciones de una ronda basandose en las respuestas.
     * - Respuesta correcta: 10 puntos
     * - Primero en responder correctamente: 15 puntos (en vez de 10)
     * - Ultimo en responder correctamente: 8 puntos (en vez de 10)
     * - No respondio o fallo: 0 puntos
     */
    private void calcularPuntuacionesRonda(Pregunta pregunta) {
        char correcta = pregunta.getRespuestaCorrecta();

        // Filtrar jugadores que respondieron correctamente, ordenados por tiempo
        List<Jugador> correctos = new ArrayList<>();
        synchronized (ordenRespuestas) {
            for (Jugador j : ordenRespuestas) {
                if (j.getRespuestaActual() == correcta) {
                    correctos.add(j);
                }
            }
        }

        if (correctos.isEmpty()) {
            return; // nadie acerto
        }

        for (int i = 0; i < correctos.size(); i++) {
            Jugador j = correctos.get(i);
            if (correctos.size() == 1) {
                // Solo uno acerto, se lleva los 15 del primero
                j.sumarPuntos(15);
            } else if (i == 0) {
                // Primero en responder correctamente
                j.sumarPuntos(15);
            } else if (i == correctos.size() - 1) {
                // Ultimo en responder correctamente
                j.sumarPuntos(8);
            } else {
                // Resto: puntuacion normal
                j.sumarPuntos(10);
            }
        }
    }

    /**
     * Muestra los resultados de una ronda en la terminal del servidor.
     */
    private void mostrarResultadosRonda(Pregunta pregunta) {
        char correcta = pregunta.getRespuestaCorrecta();
        System.out.println("Respuesta correcta: " + correcta);

        for (Jugador j : jugadores) {
            String estado;
            if (!j.haRespondido()) {
                estado = "No respondio -> 0 pts";
            } else if (j.getRespuestaActual() != correcta) {
                estado = "Respondio " + j.getRespuestaActual() + " (INCORRECTO) -> 0 pts";
            } else {
                estado = "Respondio " + j.getRespuestaActual() + " (CORRECTO) -> " + j.getPuntuacion() + " pts total";
            }
            System.out.println("   " + j.getNombre() + ": " + estado);
        }
    }

    /**
     * Obtiene el ranking formateado.
     */
    public String obtenerRanking() {
        List<Jugador> ranking = new ArrayList<>(jugadores);
        Collections.sort(ranking); // orden descendente por puntuacion

        StringBuilder sb = new StringBuilder();
        sb.append("========== RANKING ==========\n");
        for (int i = 0; i < ranking.size(); i++) {
            Jugador j = ranking.get(i);
            sb.append(String.format("  #%d  %-10s  %d pts\n", i + 1, j.getNombre(), j.getPuntuacion()));
        }
        sb.append("=============================\n");
        return sb.toString();
    }

    /**
     * Obtiene la lista de jugadores conectados (nombres separados por coma).
     */
    public String obtenerListaJugadores() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < jugadores.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(jugadores.get(i).getNombre());
        }
        return sb.toString();
    }

    // ===================== METODOS DE ACCESO =====================

    public void registrarJugador(Jugador jugador) {
        jugadores.add(jugador);
    }

    public void registrarRespuesta(Jugador jugador) {
        respuestasRecibidas.incrementAndGet();
        synchronized (ordenRespuestas) {
            ordenRespuestas.add(jugador);
        }
    }

    public ValidadorNombre getValidadorNombre() {
        return validadorNombre;
    }

    public boolean isPartidaIniciada() {
        return partidaIniciada;
    }

    public boolean isPartidaTerminada() {
        return partidaTerminada;
    }

    public Pregunta getPreguntaActual() {
        return preguntaActual;
    }

    public int getNumPreguntaActual() {
        return numPreguntaActual;
    }

    public int getTotalPreguntas() {
        return cantidadPreguntas;
    }

    /**
     * Cierra el servidor.
     */
    private void cerrarServidor() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            System.out.println("[SERVIDOR] Servidor cerrado.");
        } catch (IOException e) {
            System.err.println("[SERVIDOR] Error al cerrar: " + e.getMessage());
        }
    }
}