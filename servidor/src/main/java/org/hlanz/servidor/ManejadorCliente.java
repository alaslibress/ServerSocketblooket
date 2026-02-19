package org.hlanz.servidor;

import javax.net.ssl.SSLSocket;
import java.io.*;

/**
 * Maneja la conexion individual con cada cliente.
 * Procesa peticiones HTTP sobre SSL/TLS.
 *
 * Rutas HTTP soportadas:
 *   POST /registro       -> Registrar nombre de jugador
 *   GET  /pregunta        -> Obtener la pregunta actual
 *   POST /respuesta       -> Enviar respuesta (1 char: A, B, C, D)
 *   GET  /ranking         -> Obtener ranking actual
 *   GET  /estado          -> Obtener estado del juego
 *   GET  /esperando       -> Polling en sala de espera
 */
public class ManejadorCliente implements Runnable {

    private SSLSocket socket;
    private ServidorJuego servidor;
    private Jugador jugador;
    private boolean conectado;

    // Streams
    private InputStream entrada;
    private OutputStream salida;

    public ManejadorCliente(SSLSocket socket, ServidorJuego servidor) {
        this.socket = socket;
        this.servidor = servidor;
        this.conectado = true;
        this.jugador = null;
    }

    @Override
    public void run() {
        try {
            socket.startHandshake();
            entrada = socket.getInputStream();
            salida = socket.getOutputStream();

            System.out.println("[SERVIDOR] Nueva conexion desde: " + socket.getInetAddress());

            // Auto-registrar inmediatamente al conectarse.
            // Si el cliente luego envia POST /registro con nombre, se ignorara
            // porque ya estara registrado.
            autoRegistrar();

            while (conectado && !socket.isClosed()) {
                try {
                    HttpUtil.HttpRequest peticion = HttpUtil.parsearPeticion(entrada);
                    if (peticion == null) {
                        conectado = false;
                        break;
                    }

                    String respuestaHttp = procesarPeticion(peticion);
                    enviarRespuesta(respuestaHttp);
                } catch (IOException e) {
                    // El cliente se desconecto
                    conectado = false;
                }
            }
        } catch (IOException e) {
            System.err.println("[SERVIDOR] Error con cliente: " + e.getMessage());
        } finally {
            desconectar();
        }
    }

    /**
     * Auto-registra al cliente con nombre "juan" si aun no tiene jugador asignado.
     * Permite que clientes externos se conecten sin hacer POST /registro.
     */
    private void autoRegistrar() {
        String nombre = servidor.getValidadorNombre().generarNombreAutomatico();
        jugador = new Jugador(nombre);
        servidor.getValidadorNombre().registrar(nombre);
        servidor.registrarJugador(jugador);
        System.out.println("[SERVIDOR] Jugador auto-registrado: " + jugador.getNombre());
    }

    /**
     * Procesa una peticion HTTP y devuelve la respuesta.
     */
    private String procesarPeticion(HttpUtil.HttpRequest peticion) {
        String ruta = peticion.getRuta();
        String metodo = peticion.getMetodo();

        switch (ruta) {
            case "/registro":
                if ("POST".equals(metodo)) {
                    return procesarRegistro(peticion.getCuerpo().trim());
                }
                return HttpUtil.construirRespuesta400("Metodo no permitido. Use POST.");

            case "/pregunta":
                if ("GET".equals(metodo)) {
                    return procesarObtenerPregunta();
                }
                return HttpUtil.construirRespuesta400("Metodo no permitido. Use GET.");

            case "/respuesta":
                if ("POST".equals(metodo)) {
                    return procesarRespuesta(peticion.getCuerpo().trim());
                }
                return HttpUtil.construirRespuesta400("Metodo no permitido. Use POST.");

            case "/ranking":
                if ("GET".equals(metodo)) {
                    return procesarObtenerRanking();
                }
                return HttpUtil.construirRespuesta400("Metodo no permitido. Use GET.");

            case "/estado":
                if ("GET".equals(metodo)) {
                    return procesarObtenerEstado();
                }
                return HttpUtil.construirRespuesta400("Metodo no permitido. Use GET.");

            case "/esperando":
                if ("GET".equals(metodo)) {
                    return procesarEsperando();
                }
                return HttpUtil.construirRespuesta400("Metodo no permitido. Use GET.");

            case "/next":
                if ("POST".equals(metodo)) {
                    return procesarNext();
                }
                return HttpUtil.construirRespuesta400("Metodo no permitido. Use POST.");

            default:
                return HttpUtil.construirRespuesta404("Ruta no encontrada: " + ruta);
        }
    }

    /**
     * POST /registro - Registra el nombre del jugador.
     */
    private String procesarRegistro(String nombre) {
        if (jugador != null) {
            return HttpUtil.construirRespuesta400("Ya estas registrado como: " + jugador.getNombre());
        }

        // Si el nombre llega vacio, asignar uno automatico
        if (nombre == null || nombre.trim().isEmpty()) {
            autoRegistrar();
            return HttpUtil.construirRespuesta200("REGISTRO_OK:" + jugador.getNombre());
        }

        String error = servidor.getValidadorNombre().validar(nombre);
        if (error != null) {
            return HttpUtil.construirRespuesta403(error);
        }

        // Registrar jugador
        jugador = new Jugador(nombre.trim());
        servidor.getValidadorNombre().registrar(nombre.trim());
        servidor.registrarJugador(jugador);

        System.out.println("[SERVIDOR] Jugador registrado: " + jugador.getNombre());
        return HttpUtil.construirRespuesta200("REGISTRO_OK:" + jugador.getNombre());
    }

    /**
     * GET /pregunta - Obtiene la pregunta actual.
     */
    private String procesarObtenerPregunta() {
        if (jugador == null) {
            return HttpUtil.construirRespuesta403("Debes registrarte primero.");
        }

        if (!servidor.isPartidaIniciada()) {
            return HttpUtil.construirRespuesta200("ESTADO:ESPERANDO");
        }

        if (servidor.isPartidaTerminada()) {
            return HttpUtil.construirRespuesta200("ESTADO:TERMINADA");
        }

        Pregunta preguntaActual = servidor.getPreguntaActual();
        if (preguntaActual == null) {
            return HttpUtil.construirRespuesta200("ESTADO:SIN_PREGUNTA");
        }

        int numActual = servidor.getNumPreguntaActual();
        int totalPreguntas = servidor.getTotalPreguntas();
        String cuerpo = "PREGUNTA:" + numActual + "/" + totalPreguntas + "\n" + preguntaActual.toHttpBody();
        return HttpUtil.construirRespuesta200(cuerpo);
    }

    /**
     * POST /respuesta - Envia la respuesta del jugador (1 char: A, B, C, D).
     */
    private String procesarRespuesta(String respuestaTexto) {
        if (jugador == null) {
            return HttpUtil.construirRespuesta403("Debes registrarte primero.");
        }

        if (!servidor.isPartidaIniciada() || servidor.isPartidaTerminada()) {
            return HttpUtil.construirRespuesta400("La partida no esta en curso.");
        }

        if (respuestaTexto.isEmpty()) {
            return HttpUtil.construirRespuesta400("Respuesta vacia.");
        }

        // Convertir a mayuscula
        char respuesta = Character.toUpperCase(respuestaTexto.charAt(0));

        // Validar que sea A, B, C o D
        if (respuesta != 'A' && respuesta != 'B' && respuesta != 'C' && respuesta != 'D') {
            return HttpUtil.construirRespuesta400("Respuesta invalida. Debe ser A, B, C o D.");
        }

        // Intentar registrar respuesta
        boolean registrada = jugador.responder(respuesta);
        if (!registrada) {
            return HttpUtil.construirRespuesta400("Ya has respondido a esta pregunta.");
        }

        servidor.registrarRespuesta(jugador);
        System.out.println("[SERVIDOR] " + jugador.getNombre() + " respondio: " + respuesta);
        return HttpUtil.construirRespuesta200("RESPUESTA_OK:" + respuesta);
    }

    /**
     * GET /ranking - Obtiene el ranking actual.
     */
    private String procesarObtenerRanking() {
        if (jugador == null) {
            return HttpUtil.construirRespuesta403("Debes registrarte primero.");
        }
        return HttpUtil.construirRespuesta200(servidor.obtenerRanking());
    }

    /**
     * GET /estado - Obtiene el estado actual del juego.
     */
    private String procesarObtenerEstado() {
        if (jugador == null) {
            return HttpUtil.construirRespuesta403("Debes registrarte primero.");
        }

        if (servidor.isPartidaTerminada()) {
            return HttpUtil.construirRespuesta200("ESTADO:TERMINADA");
        } else if (servidor.isPartidaIniciada()) {
            return HttpUtil.construirRespuesta200("ESTADO:EN_CURSO");
        } else {
            return HttpUtil.construirRespuesta200("ESTADO:ESPERANDO");
        }
    }

    /**
     * GET /esperando - Polling en sala de espera. Devuelve lista de jugadores conectados.
     */
    private String procesarEsperando() {
        if (jugador == null) {
            return HttpUtil.construirRespuesta403("Debes registrarte primero.");
        }

        if (servidor.isPartidaIniciada()) {
            return HttpUtil.construirRespuesta200("ESTADO:INICIADA");
        }

        return HttpUtil.construirRespuesta200("ESPERANDO:" + servidor.obtenerListaJugadores());
    }

    /**
     * POST /next - El servidor administrador puede forzar pasar a la siguiente pregunta.
     */
    private String procesarNext() {
        return HttpUtil.construirRespuesta200("NEXT_OK");
    }

    /**
     * Envia una respuesta HTTP por el socket.
     */
    private void enviarRespuesta(String respuestaHttp) throws IOException {
        salida.write(respuestaHttp.getBytes("UTF-8"));
        salida.flush();
    }

    /**
     * Desconecta al cliente y limpia recursos.
     */
    private void desconectar() {
        conectado = false;
        if (jugador != null) {
            System.out.println("[SERVIDOR] Jugador desconectado: " + jugador.getNombre());
            servidor.getValidadorNombre().eliminar(jugador.getNombre());
        }
        try {
            if (entrada != null) entrada.close();
            if (salida != null) salida.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            // Ignorar errores al cerrar
        }
    }
}