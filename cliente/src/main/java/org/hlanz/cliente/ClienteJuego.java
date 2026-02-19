package org.hlanz.cliente;

import javax.net.ssl.*;
import java.io.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Scanner;

/**
 * Cliente del juego tipo Blooket/Kahoot.
 *
 * Flujo del cliente:
 * 1. Conectarse al servidor por SSL/TLS
 * 2. Registrar nombre de usuario
 * 3. Esperar en sala de espera hasta que la partida inicie
 * 4. Bucle de juego: recibir pregunta -> responder (A/B/C/D) -> ver resultado
 * 5. Al terminar, ver ranking final
 *
 * Peticiones HTTP que realiza:
 *   POST /registro       -> Enviar nombre
 *   GET  /esperando       -> Polling en sala de espera
 *   GET  /estado          -> Consultar estado del juego
 *   GET  /pregunta        -> Obtener pregunta actual
 *   POST /respuesta       -> Enviar respuesta (1 char)
 *   GET  /ranking         -> Obtener ranking
 */
public class ClienteJuego {

    private String host;
    private int puerto;
    private SSLSocket socket;
    private BufferedReader entrada;
    private OutputStream salida;
    private Scanner scanner;
    private String nombreJugador;
    private volatile boolean conectado = false;

    public ClienteJuego(String host, int puerto) {
        this.host = host;
        this.puerto = puerto;
        this.scanner = new Scanner(System.in);
    }

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("   CLIENTE BLOOKET");
        System.out.println("========================================");
        System.out.println();

        Scanner sc = new Scanner(System.in);

        System.out.print("Direccion del servidor (Enter para localhost): ");
        String host = sc.nextLine().trim();
        if (host.isEmpty()) {
            host = "localhost";
        }

        System.out.print("Puerto del servidor (Enter para 8080): ");
        String puertoStr = sc.nextLine().trim();
        int puerto = 8080;
        if (!puertoStr.isEmpty()) {
            try {
                puerto = Integer.parseInt(puertoStr);
            } catch (NumberFormatException e) {
                System.out.println("Puerto invalido. Usando 8080.");
            }
        }

        ClienteJuego cliente = new ClienteJuego(host, puerto);
        cliente.iniciar();
    }

    public void iniciar() {
        try {
            // Conectar por SSL/TLS
            conectarSSL();
            conectado = true;

            System.out.println("[CLIENTE] Conectado al servidor " + host + ":" + puerto);
            System.out.println();

            // === FASE 1: REGISTRO ===
            registrarse();

            // === FASE 2: SALA DE ESPERA ===
            esperarInicio();

            // === FASE 3: JUEGO ===
            jugar();

            // === FASE 4: RANKING FINAL ===
            mostrarRankingFinal();

        } catch (Exception e) {
            System.err.println("[CLIENTE] Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cerrarConexion();
        }
    }

    /**
     * Establece conexion SSL/TLS con el servidor.
     * Acepta certificados autofirmados para desarrollo.
     */
    private void conectarSSL() throws Exception {
        // TrustManager que acepta todos los certificados (para desarrollo con cert autofirmado)
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAll, new SecureRandom());

        SSLSocketFactory factory = sslContext.getSocketFactory();
        socket = (SSLSocket) factory.createSocket(host, puerto);
        socket.startHandshake();

        entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        salida = socket.getOutputStream();
    }

    /**
     * Fase de registro: pide nombre y lo envia al servidor.
     */
    private void registrarse() throws IOException {
        while (true) {
            System.out.print("Introduce tu nombre de jugador (max 10 caracteres): ");
            String nombre = scanner.nextLine().trim();

            if (nombre.isEmpty()) {
                System.out.println("El nombre no puede estar vacio.");
                continue;
            }

            if (nombre.length() > 10) {
                System.out.println("El nombre no puede tener mas de 10 caracteres.");
                continue;
            }

            // Enviar peticion de registro
            String peticion = ClienteHttpUtil.construirPOST("/registro", host, nombre);
            enviarPeticion(peticion);

            // Leer y descartar la respuesta del servidor para mantener el flujo HTTP sincronizado
            ClienteHttpUtil.parsearRespuesta(entrada);

            // Entrar directamente sin importar la respuesta del servidor
            nombreJugador = nombre;
            System.out.println("[CLIENTE] Entrando como: " + nombreJugador);
            System.out.println();
            break;
        }
    }

    /**
     * Fase de espera: hace polling al servidor hasta que la partida inicie.
     */
    private void esperarInicio() throws IOException {
        System.out.println("[CLIENTE] Esperando a que el administrador inicie la partida...");
        System.out.println();

        String ultimaLista = "";

        while (conectado) {
            try {
                Thread.sleep(2000); // polling cada 2 segundos
            } catch (InterruptedException e) {
                break;
            }

            String peticion = ClienteHttpUtil.construirGET("/esperando", host);
            enviarPeticion(peticion);

            ClienteHttpUtil.HttpResponse respuesta = ClienteHttpUtil.parsearRespuesta(entrada);
            if (respuesta == null) continue;

            String cuerpo = respuesta.getCuerpo();

            if (cuerpo.startsWith("ESTADO:INICIADA")) {
                System.out.println();
                System.out.println("========================================");
                System.out.println("   LA PARTIDA HA COMENZADO!");
                System.out.println("========================================");
                System.out.println();
                break;
            }

            if (cuerpo.startsWith("ESPERANDO:")) {
                String listaJugadores = cuerpo.substring("ESPERANDO:".length());
                if (!listaJugadores.equals(ultimaLista)) {
                    ultimaLista = listaJugadores;
                    String[] jugadores = listaJugadores.split(",");
                    System.out.println("[SALA DE ESPERA] Jugadores conectados (" + jugadores.length + "):");
                    for (String j : jugadores) {
                        System.out.println("   -> " + j.trim());
                    }
                }
            }
        }
    }

    /**
     * Fase de juego: recibe preguntas y envia respuestas.
     */
    private void jugar() throws IOException {
        int ultimaPreguntaVista = 0;

        while (conectado) {
            // Consultar estado
            String petEstado = ClienteHttpUtil.construirGET("/estado", host);
            enviarPeticion(petEstado);

            ClienteHttpUtil.HttpResponse respEstado = ClienteHttpUtil.parsearRespuesta(entrada);
            if (respEstado == null) continue;

            if (respEstado.getCuerpo().contains("TERMINADA")) {
                System.out.println();
                System.out.println("[CLIENTE] La partida ha terminado!");
                break;
            }

            // Obtener pregunta actual
            String petPregunta = ClienteHttpUtil.construirGET("/pregunta", host);
            enviarPeticion(petPregunta);

            ClienteHttpUtil.HttpResponse respPregunta = ClienteHttpUtil.parsearRespuesta(entrada);
            if (respPregunta == null) continue;

            String cuerpoPregunta = respPregunta.getCuerpo();

            if (cuerpoPregunta.contains("TERMINADA")) {
                System.out.println();
                System.out.println("[CLIENTE] La partida ha terminado!");
                break;
            }

            if (cuerpoPregunta.startsWith("ESTADO:SIN_PREGUNTA")) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                continue;
            }

            if (cuerpoPregunta.startsWith("PREGUNTA:")) {
                // Parsear numero de pregunta
                String[] lineas = cuerpoPregunta.split("\n");
                String infoPregunta = lineas[0]; // PREGUNTA:X/Y
                String numStr = infoPregunta.replace("PREGUNTA:", "").split("/")[0];
                int numPregunta = Integer.parseInt(numStr);

                if (numPregunta > ultimaPreguntaVista) {
                    ultimaPreguntaVista = numPregunta;

                    // Mostrar la pregunta
                    System.out.println("========================================");
                    for (int i = 0; i < lineas.length; i++) {
                        if (i == 0) {
                            System.out.println("  " + lineas[i]);
                        } else {
                            System.out.println("  " + lineas[i]);
                        }
                    }
                    System.out.println("========================================");
                    System.out.println();

                    // Pedir respuesta
                    boolean respuestaEnviada = false;
                    while (!respuestaEnviada) {
                        System.out.print("Tu respuesta (A/B/C/D): ");

                        // Leer con timeout usando un hilo
                        String input = leerConTimeout();

                        if (input == null) {
                            // Tiempo agotado en el cliente
                            System.out.println("[CLIENTE] No respondiste a tiempo.");
                            respuestaEnviada = true;
                            continue;
                        }

                        input = input.trim().toUpperCase();

                        if (input.isEmpty()) {
                            continue;
                        }

                        // Validar que sea solo A, B, C o D
                        if (input.length() != 1 || "ABCD".indexOf(input.charAt(0)) == -1) {
                            System.out.println("Respuesta invalida. Debe ser A, B, C o D.");
                            continue;
                        }

                        char respuestaChar = input.charAt(0);

                        // Enviar respuesta al servidor
                        String petRespuesta = ClienteHttpUtil.construirPOST(
                                "/respuesta", host, String.valueOf(respuestaChar));
                        enviarPeticion(petRespuesta);

                        ClienteHttpUtil.HttpResponse respRespuesta =
                                ClienteHttpUtil.parsearRespuesta(entrada);

                        if (respRespuesta != null) {
                            if (respRespuesta.esExitosa()) {
                                System.out.println("[CLIENTE] Respuesta enviada: " + respuestaChar);
                                respuestaEnviada = true;
                            } else {
                                System.out.println("[SERVIDOR] " + respRespuesta.getCuerpo());
                                if (respRespuesta.getCuerpo().contains("Ya has respondido")) {
                                    respuestaEnviada = true;
                                }
                            }
                        }
                    }

                    System.out.println("[CLIENTE] Esperando siguiente pregunta...");
                    System.out.println();
                }
            }

            // Espera entre consultas
            try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
        }
    }

    /**
     * Lee entrada del usuario. Retorna null si no hay input disponible.
     */
    private String leerConTimeout() {
        // Para simplificar, usamos lectura directa
        // En un entorno real, se podria usar un hilo con timeout
        if (scanner.hasNextLine()) {
            return scanner.nextLine();
        }
        return null;
    }

    /**
     * Muestra el ranking final.
     */
    private void mostrarRankingFinal() throws IOException {
        String petRanking = ClienteHttpUtil.construirGET("/ranking", host);
        enviarPeticion(petRanking);

        ClienteHttpUtil.HttpResponse respRanking = ClienteHttpUtil.parsearRespuesta(entrada);
        if (respRanking != null && respRanking.esExitosa()) {
            System.out.println();
            System.out.println(respRanking.getCuerpo());
        }

        System.out.println();
        System.out.println("Gracias por jugar, " + nombreJugador + "!");
        System.out.println("Pulsa Enter para salir.");
        scanner.nextLine();
    }

    /**
     * Envia una peticion HTTP por el socket SSL.
     */
    private void enviarPeticion(String peticionHttp) throws IOException {
        salida.write(peticionHttp.getBytes("UTF-8"));
        salida.flush();
    }

    /**
     * Cierra la conexion con el servidor.
     */
    private void cerrarConexion() {
        conectado = false;
        try {
            if (scanner != null) scanner.close();
            if (entrada != null) entrada.close();
            if (salida != null) salida.close();
            if (socket != null && !socket.isClosed()) socket.close();
            System.out.println("[CLIENTE] Desconectado del servidor.");
        } catch (IOException e) {
            // Ignorar
        }
    }
}