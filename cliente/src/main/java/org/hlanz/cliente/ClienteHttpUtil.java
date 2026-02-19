package org.hlanz.cliente;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Utilidades HTTP para el cliente.
 * Construye peticiones HTTP y parsea respuestas HTTP sobre SSL/TLS sockets.
 */
public class ClienteHttpUtil {

    /**
     * Respuesta HTTP parseada.
     */
    public static class HttpResponse {
        private int codigo;
        private String mensaje;
        private String cuerpo;

        public HttpResponse(int codigo, String mensaje, String cuerpo) {
            this.codigo = codigo;
            this.mensaje = mensaje;
            this.cuerpo = cuerpo;
        }

        public int getCodigo() { return codigo; }
        public String getMensaje() { return mensaje; }
        public String getCuerpo() { return cuerpo; }

        public boolean esExitosa() {
            return codigo >= 200 && codigo < 300;
        }
    }

    /**
     * Construye una peticion HTTP GET.
     */
    public static String construirGET(String ruta, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(ruta).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        sb.append("Connection: keep-alive\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    /**
     * Construye una peticion HTTP POST con cuerpo de texto.
     */
    public static String construirPOST(String ruta, String host, String cuerpo) {
        byte[] cuerpoBytes;
        try {
            cuerpoBytes = cuerpo.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            cuerpoBytes = cuerpo.getBytes();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("POST ").append(ruta).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Length: ").append(cuerpoBytes.length).append("\r\n");
        sb.append("Connection: keep-alive\r\n");
        sb.append("\r\n");
        sb.append(cuerpo);
        return sb.toString();
    }

    /**
     * Parsea una respuesta HTTP desde un BufferedReader.
     */
    public static HttpResponse parsearRespuesta(BufferedReader reader) throws IOException {
        // Leer linea de estado: HTTP/1.1 200 OK
        String lineaEstado = reader.readLine();
        if (lineaEstado == null || lineaEstado.isEmpty()) {
            return null;
        }

        String[] partesEstado = lineaEstado.split(" ", 3);
        if (partesEstado.length < 2) {
            return null;
        }

        int codigo;
        try {
            codigo = Integer.parseInt(partesEstado[1]);
        } catch (NumberFormatException e) {
            return null;
        }
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

        return new HttpResponse(codigo, mensaje, cuerpo);
    }
}