package org.hlanz.servidor;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Utilidades para parsear y construir peticiones/respuestas HTTP manuales
 * sobre sockets SSL/TLS.
 *
 * Estructura de peticion HTTP:
 *   METODO /ruta HTTP/1.1\r\n
 *   Header: valor\r\n
 *   \r\n
 *   cuerpo
 *
 * Estructura de respuesta HTTP:
 *   HTTP/1.1 CODIGO MENSAJE\r\n
 *   Header: valor\r\n
 *   \r\n
 *   cuerpo
 */
public class HttpUtil {

    // ===================== PETICION HTTP =====================

    public static class HttpRequest {
        private String metodo;
        private String ruta;
        private Map<String, String> headers;
        private String cuerpo;

        public HttpRequest(String metodo, String ruta, Map<String, String> headers, String cuerpo) {
            this.metodo = metodo;
            this.ruta = ruta;
            this.headers = headers;
            this.cuerpo = cuerpo;
        }

        public String getMetodo() { return metodo; }
        public String getRuta() { return ruta; }
        public Map<String, String> getHeaders() { return headers; }
        public String getCuerpo() { return cuerpo; }

        public String getHeader(String nombre) {
            return headers.getOrDefault(nombre.toLowerCase(), "");
        }
    }

    // ===================== RESPUESTA HTTP =====================

    public static class HttpResponse {
        private int codigo;
        private String mensaje;
        private Map<String, String> headers;
        private String cuerpo;

        public HttpResponse(int codigo, String mensaje, String cuerpo) {
            this.codigo = codigo;
            this.mensaje = mensaje;
            this.headers = new HashMap<>();
            this.cuerpo = cuerpo;
        }

        public int getCodigo() { return codigo; }
        public String getMensaje() { return mensaje; }
        public String getCuerpo() { return cuerpo; }

        public void addHeader(String nombre, String valor) {
            headers.put(nombre, valor);
        }
    }

    // ===================== PARSEAR PETICION =====================

    /**
     * Lee y parsea una peticion HTTP desde un InputStream.
     */
    public static HttpRequest parsearPeticion(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));

        // Leer linea de peticion: METODO /ruta HTTP/1.1
        String lineaPeticion = reader.readLine();
        if (lineaPeticion == null || lineaPeticion.isEmpty()) {
            return null;
        }

        String[] partesPeticion = lineaPeticion.split(" ");
        if (partesPeticion.length < 2) {
            return null;
        }

        String metodo = partesPeticion[0].toUpperCase();
        String ruta = partesPeticion[1];

        // Leer headers
        Map<String, String> headers = new HashMap<>();
        String linea;
        while ((linea = reader.readLine()) != null && !linea.isEmpty()) {
            int separador = linea.indexOf(':');
            if (separador > 0) {
                String nombre = linea.substring(0, separador).trim().toLowerCase();
                String valor = linea.substring(separador + 1).trim();
                headers.put(nombre, valor);
            }
        }

        // Leer cuerpo si hay Content-Length
        String cuerpo = "";
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            int longitud = Integer.parseInt(contentLength.trim());
            char[] buffer = new char[longitud];
            int leidos = 0;
            while (leidos < longitud) {
                int resultado = reader.read(buffer, leidos, longitud - leidos);
                if (resultado == -1) break;
                leidos += resultado;
            }
            cuerpo = new String(buffer, 0, leidos);
        }

        return new HttpRequest(metodo, ruta, headers, cuerpo);
    }

    // ===================== CONSTRUIR RESPUESTA =====================

    /**
     * Construye una respuesta HTTP 200 OK con cuerpo de texto.
     */
    public static String construirRespuesta200(String cuerpo) {
        return construirRespuesta(200, "OK", cuerpo);
    }

    /**
     * Construye una respuesta HTTP 400 Bad Request.
     */
    public static String construirRespuesta400(String cuerpo) {
        return construirRespuesta(400, "Bad Request", cuerpo);
    }

    /**
     * Construye una respuesta HTTP 403 Forbidden.
     */
    public static String construirRespuesta403(String cuerpo) {
        return construirRespuesta(403, "Forbidden", cuerpo);
    }

    /**
     * Construye una respuesta HTTP 404 Not Found.
     */
    public static String construirRespuesta404(String cuerpo) {
        return construirRespuesta(404, "Not Found", cuerpo);
    }

    /**
     * Construye una respuesta HTTP generica.
     */
    public static String construirRespuesta(int codigo, String mensaje, String cuerpo) {
        byte[] cuerpoBytes;
        try {
            cuerpoBytes = cuerpo.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            cuerpoBytes = cuerpo.getBytes();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(codigo).append(" ").append(mensaje).append("\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Length: ").append(cuerpoBytes.length).append("\r\n");
        sb.append("Connection: keep-alive\r\n");
        sb.append("\r\n");
        sb.append(cuerpo);
        return sb.toString();
    }

    // ===================== CONSTRUIR PETICION (para el cliente) =====================

    /**
     * Construye una peticion HTTP GET.
     */
    public static String construirPeticionGET(String ruta) {
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(ruta).append(" HTTP/1.1\r\n");
        sb.append("Host: localhost\r\n");
        sb.append("Connection: keep-alive\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    /**
     * Construye una peticion HTTP POST con cuerpo.
     */
    public static String construirPeticionPOST(String ruta, String cuerpo) {
        byte[] cuerpoBytes;
        try {
            cuerpoBytes = cuerpo.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            cuerpoBytes = cuerpo.getBytes();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("POST ").append(ruta).append(" HTTP/1.1\r\n");
        sb.append("Host: localhost\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Length: ").append(cuerpoBytes.length).append("\r\n");
        sb.append("Connection: keep-alive\r\n");
        sb.append("\r\n");
        sb.append(cuerpo);
        return sb.toString();
    }

    /**
     * Parsea una respuesta HTTP desde un InputStream (para el cliente).
     */
    public static HttpResponse parsearRespuesta(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));

        // Leer linea de estado: HTTP/1.1 200 OK
        String lineaEstado = reader.readLine();
        if (lineaEstado == null || lineaEstado.isEmpty()) {
            return null;
        }

        String[] partesEstado = lineaEstado.split(" ", 3);
        if (partesEstado.length < 2) {
            return null;
        }

        int codigo = Integer.parseInt(partesEstado[1]);
        String mensaje = partesEstado.length > 2 ? partesEstado[2] : "";

        // Leer headers
        Map<String, String> headers = new HashMap<>();
        String linea;
        while ((linea = reader.readLine()) != null && !linea.isEmpty()) {
            int separador = linea.indexOf(':');
            if (separador > 0) {
                String nombre = linea.substring(0, separador).trim().toLowerCase();
                String valor = linea.substring(separador + 1).trim();
                headers.put(nombre, valor);
            }
        }

        // Leer cuerpo
        String cuerpo = "";
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            int longitud = Integer.parseInt(contentLength.trim());
            char[] buffer = new char[longitud];
            int leidos = 0;
            while (leidos < longitud) {
                int resultado = reader.read(buffer, leidos, longitud - leidos);
                if (resultado == -1) break;
                leidos += resultado;
            }
            cuerpo = new String(buffer, 0, leidos);
        }

        HttpResponse response = new HttpResponse(codigo, mensaje, cuerpo);
        return response;
    }
}