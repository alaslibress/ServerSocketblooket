package org.hlanz.cliente;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class ClienteKahoot {
    private enum WireMode { UNKNOWN, HTTP, JSON, LEGACY }

    private static final String HOST = "34.196.113.94";
    private static final int PUERTO = 8080;

    private Socket socket;
    private PrintWriter salida;
    private BufferedReader entrada;
    private final Scanner scanner;
    private volatile boolean conectado = true;
    private WireMode wireMode = WireMode.UNKNOWN;

    public ClienteKahoot() {
        scanner = new Scanner(System.in);
    }

    public void iniciar() {
        try {
            socket = crearSocketSSL(HOST, PUERTO);
            entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            salida = new PrintWriter(socket.getOutputStream(), true);

            System.out.println("Conectado al servidor Kahoot");
            while (conectado) {
                IncomingMessage msg = leerEntrada();
                if (msg == null) {
                    break;
                }
                if (msg.mode == WireMode.LEGACY) {
                    procesarLegacy(msg.body);
                } else {
                    procesarJson(msg.body);
                }
            }
        } catch (Exception e) {
            System.err.println("Error de conexion: " + e.getMessage());
        } finally {
            cerrarConexion();
        }
    }

    private SSLSocket crearSocketSSL(String host, int puerto) throws Exception {
        // Aceptar cualquier certificado del servidor.
        TrustManager[] trustManagers = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException { }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new java.security.SecureRandom());
        SSLSocketFactory socketFactory = context.getSocketFactory();
        return (SSLSocket) socketFactory.createSocket(host, puerto);
    }

    private void procesarLegacy(String line) {
        if (line == null) {
            return;
        }
        String token = line.trim();
        if (token.equalsIgnoreCase("NOMBRE")) {
            String nombre = leerNombre();
            enviarNombre(nombre);
            return;
        }
        if (token.equalsIgnoreCase("RESPONDE")) {
            String respuesta = leerRespuesta();
            if (respuesta == null) {
                enviarExit();
                conectado = false;
                return;
            }
            enviarRespuesta(respuesta);
            return;
        }
        System.out.println(line);
    }

    private void procesarJson(String json) {
        String type = extraerJsonString(json, "type");
        if (type == null) {
            // Si no hay type, intentamos deducir por forma.
            if (extraerJsonString(json, "question") != null && !extraerJsonArray(json, "choices").isEmpty()) {
                type = "QUESTION";
            } else if (!extraerJsonArray(json, "results").isEmpty()) {
                type = "RANKING";
            } else if (extraerJsonString(json, "prompt") != null) {
                type = "ASK_NAME";
            } else if (extraerJsonString(json, "message") != null) {
                type = "INFO";
            }
        }
        if (type == null) {
            System.out.println("Mensaje no reconocido: " + json);
            return;
        }
        type = type.trim().toUpperCase(Locale.ROOT);

        switch (type) {
            case "WELCOME", "INFO", "RESULT", "ERROR" -> {
                String msg = extraerJsonString(json, "message");
                if (msg != null) {
                    System.out.println(msg);
                }
            }
            case "ASK_NAME" -> {
                String prompt = extraerJsonString(json, "prompt");
                System.out.println(prompt == null ? "Escribe tu nombre:" : prompt);
                enviarNombre(leerNombre());
            }
            case "QUESTION" -> {
                mostrarPregunta(json);
                String respuesta = leerRespuesta();
                if (respuesta == null) {
                    enviarExit();
                    conectado = false;
                    return;
                }
                enviarRespuesta(respuesta);
            }
            case "RANKING" -> {
                System.out.println();
                List<String> lineas = extraerJsonArray(json, "results");
                for (String linea : lineas) {
                    System.out.println(linea);
                }
            }
            case "FIN", "FINISHED" -> {
                String msg = extraerJsonString(json, "message");
                if (msg != null) {
                    System.out.println(msg);
                }
                conectado = false;
            }
            default -> System.out.println("Tipo no soportado: " + type);
        }
    }

    private void mostrarPregunta(String json) {
        String numero = extraerJsonNumber(json, "questionIndex");
        String pregunta = extraerJsonString(json, "question");
        List<String> opciones = extraerJsonArray(json, "choices");
        String limite = extraerJsonNumber(json, "timeLimitSeconds");
        System.out.println("PREGUNTA " + (numero == null ? "?" : numero) + ": " + pregunta);
        for (int i = 0; i < opciones.size(); i++) {
            char letra = (char) ('A' + i);
            System.out.println(letra + ") " + opciones.get(i));
        }
        if (limite != null) {
            System.out.println("Tiempo limite: " + limite + "s");
        }
    }

    private void enviarNombre(String nombre) {
        if (wireMode == WireMode.HTTP) {
            enviarHttp("POST /player/name HTTP/1.1", "{\"playerName\":\"" + escapeJson(nombre) + "\"}");
            return;
        }
        if (wireMode == WireMode.JSON) {
            salida.println("{\"type\":\"NAME\",\"playerName\":\"" + escapeJson(nombre) + "\"}");
            return;
        }
        salida.println(nombre);
    }

    private void enviarRespuesta(String respuesta) {
        if (wireMode == WireMode.HTTP) {
            enviarHttp("POST /quiz/answer HTTP/1.1", "{\"answer\":\"" + escapeJson(respuesta) + "\"}");
            return;
        }
        if (wireMode == WireMode.JSON) {
            salida.println("{\"type\":\"ANSWER\",\"answer\":\"" + escapeJson(respuesta) + "\"}");
            return;
        }
        salida.println(respuesta);
    }

    private void enviarExit() {
        if (wireMode == WireMode.HTTP) {
            enviarHttp("POST /player/exit HTTP/1.1", "{}");
            return;
        }
        if (wireMode == WireMode.JSON) {
            salida.println("{\"type\":\"EXIT\"}");
            return;
        }
        salida.println("/salir");
    }

    private IncomingMessage leerEntrada() throws IOException {
        String first = leerLineaNoVacia();
        if (first == null) {
            return null;
        }
        if (first.startsWith("POST ") || first.startsWith("GET ")) {
            wireMode = WireMode.HTTP;
            return leerHttp(first);
        }
        if (first.startsWith("{")) {
            if (wireMode == WireMode.UNKNOWN) {
                wireMode = WireMode.JSON;
            }
            return new IncomingMessage(wireMode, first);
        }
        if (wireMode == WireMode.UNKNOWN) {
            wireMode = WireMode.LEGACY;
        }
        return new IncomingMessage(WireMode.LEGACY, first);
    }

    private IncomingMessage leerHttp(String startLine) throws IOException {
        int contentLength = 0;
        String line;
        while ((line = entrada.readLine()) != null) {
            if (line.isEmpty()) {
                break;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                contentLength = parseIntSeguro(line.substring("content-length:".length()).trim());
            }
        }
        char[] bodyChars = new char[Math.max(0, contentLength)];
        int read = 0;
        while (read < contentLength) {
            int n = entrada.read(bodyChars, read, contentLength - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        entrada.mark(2);
        int c1 = entrada.read();
        int c2 = entrada.read();
        if (!(c1 == '\r' && c2 == '\n') && !(c1 == '\n')) {
            entrada.reset();
        }
        return new IncomingMessage(WireMode.HTTP, new String(bodyChars, 0, read));
    }

    private String leerLineaNoVacia() throws IOException {
        String line;
        while ((line = entrada.readLine()) != null) {
            if (!line.trim().isEmpty()) {
                return line;
            }
        }
        return null;
    }

    private void enviarHttp(String startLine, String body) {
        String payload = body == null ? "" : body;
        salida.print(startLine + "\r\n");
        salida.print("Content-Type: application/json\r\n");
        salida.print("Content-Length: " + payload.length() + "\r\n");
        salida.print("\r\n");
        salida.print(payload);
        salida.print("\r\n");
        salida.flush();
    }

    private void cerrarConexion() {
        try {
            conectado = false;
            scanner.close();
            if (salida != null) {
                salida.close();
            }
            if (entrada != null) {
                entrada.close();
            }
            if (socket != null) {
                socket.close();
            }
            System.out.println("Desconectado del servidor");
        } catch (IOException e) {
            System.err.println("Error cerrando conexion: " + e.getMessage());
        }
    }

    private String leerNombre() {
        while (true) {
            System.out.print("Nombre: ");
            String nombre = scanner.nextLine();
            if (nombre != null) {
                nombre = nombre.trim();
                if (!nombre.isEmpty()) {
                    return nombre;
                }
            }
            System.out.println("Nombre invalido.");
        }
    }

    private String leerRespuesta() {
        while (conectado) {
            System.out.print("Respuesta (A/B/C/D o /salir): ");
            String entradaUsuario = scanner.nextLine();
            if (entradaUsuario.equalsIgnoreCase("/salir")) {
                return null;
            }
            if (!entradaUsuario.trim().isEmpty()) {
                return entradaUsuario.trim();
            }
        }
        return null;
    }

    private int parseIntSeguro(String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String extraerJsonString(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String patron = "\"" + key + "\"";
        int p = json.indexOf(patron);
        if (p < 0) {
            return null;
        }
        int dosPuntos = json.indexOf(':', p + patron.length());
        if (dosPuntos < 0) {
            return null;
        }
        int inicio = json.indexOf('"', dosPuntos + 1);
        if (inicio < 0) {
            return null;
        }
        int fin = inicio + 1;
        boolean escape = false;
        while (fin < json.length()) {
            char c = json.charAt(fin);
            if (c == '"' && !escape) {
                break;
            }
            if (c == '\\' && !escape) {
                escape = true;
            } else {
                escape = false;
            }
            fin++;
        }
        if (fin >= json.length()) {
            return null;
        }
        return unescapeJson(json.substring(inicio + 1, fin));
    }

    private String extraerJsonNumber(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String patron = "\"" + key + "\"";
        int p = json.indexOf(patron);
        if (p < 0) {
            return null;
        }
        int dosPuntos = json.indexOf(':', p + patron.length());
        if (dosPuntos < 0) {
            return null;
        }
        int i = dosPuntos + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        int inicio = i;
        while (i < json.length() && Character.isDigit(json.charAt(i))) {
            i++;
        }
        if (inicio == i) {
            return null;
        }
        return json.substring(inicio, i);
    }

    private List<String> extraerJsonArray(String json, String key) {
        List<String> valores = new ArrayList<>();
        if (json == null || key == null) {
            return valores;
        }
        String patron = "\"" + key + "\"";
        int p = json.indexOf(patron);
        if (p < 0) {
            return valores;
        }
        int dosPuntos = json.indexOf(':', p + patron.length());
        int inicio = json.indexOf('[', dosPuntos + 1);
        int fin = json.indexOf(']', inicio + 1);
        if (dosPuntos < 0 || inicio < 0 || fin < 0) {
            return valores;
        }
        String contenido = json.substring(inicio + 1, fin).trim();
        if (contenido.isEmpty()) {
            return valores;
        }
        boolean enCadena = false;
        boolean escape = false;
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < contenido.length(); i++) {
            char c = contenido.charAt(i);
            if (c == '"' && !escape) {
                enCadena = !enCadena;
                if (!enCadena) {
                    valores.add(unescapeJson(actual.toString()));
                    actual.setLength(0);
                }
                continue;
            }
            if (enCadena) {
                if (c == '\\' && !escape) {
                    escape = true;
                } else {
                    actual.append(c);
                    escape = false;
                }
            }
        }
        return valores;
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private String unescapeJson(String input) {
        if (input == null) {
            return null;
        }
        return input
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static class IncomingMessage {
        private final WireMode mode;
        private final String body;

        private IncomingMessage(WireMode mode, String body) {
            this.mode = mode;
            this.body = body;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== CLIENTE KAHOOT ===");
        ClienteKahoot cliente = new ClienteKahoot();
        cliente.iniciar();
    }
}
