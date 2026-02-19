package org.hlanz.servidor;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Carga las preguntas desde un archivo CSV.
 * Soporta carga local y desde servidor FTP.
 * Formato CSV: pregunta;opcionA;opcionB;opcionC;opcionD;respuesta
 */
public class CargadorPreguntas {

    private static final String SEPARADOR = ";";

    /**
     * Carga preguntas desde un archivo CSV local.
     */
    public static List<Pregunta> cargarDesdeLocal(String rutaArchivo) throws IOException {
        List<Pregunta> preguntas = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(rutaArchivo), "UTF-8"))) {
            String linea;
            boolean primeraLinea = true;
            while ((linea = br.readLine()) != null) {
                if (primeraLinea) {
                    primeraLinea = false; // saltar cabecera
                    continue;
                }
                Pregunta p = parsearLinea(linea);
                if (p != null) {
                    preguntas.add(p);
                }
            }
        }
        System.out.println("[CSV] Cargadas " + preguntas.size() + " preguntas desde archivo local.");
        return preguntas;
    }

    /**
     * Carga preguntas desde un servidor FTP.
     * Realiza conexion FTP manual por sockets (sin libreria externa).
     *
     * @param host     Direccion del servidor FTP
     * @param puerto   Puerto del servidor FTP (por defecto 21)
     * @param usuario  Usuario FTP
     * @param password Contrasena FTP
     * @param rutaRemota Ruta del archivo CSV en el servidor FTP
     */
    public static List<Pregunta> cargarDesdeFTP(String host, int puerto, String usuario,
                                                String password, String rutaRemota) throws IOException {
        List<Pregunta> preguntas = new ArrayList<>();

        System.out.println("[FTP] Conectando a " + host + ":" + puerto + "...");

        // Conexion de control FTP
        Socket controlSocket = new Socket(host, puerto);
        BufferedReader controlIn = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
        PrintWriter controlOut = new PrintWriter(controlSocket.getOutputStream(), true);

        // Leer mensaje de bienvenida
        String respuesta = controlIn.readLine();
        System.out.println("[FTP] " + respuesta);

        // Autenticacion
        controlOut.println("USER " + usuario);
        respuesta = controlIn.readLine();
        System.out.println("[FTP] " + respuesta);

        controlOut.println("PASS " + password);
        respuesta = controlIn.readLine();
        System.out.println("[FTP] " + respuesta);
        if (!respuesta.startsWith("230")) {
            throw new IOException("Error de autenticacion FTP: " + respuesta);
        }

        // Modo binario
        controlOut.println("TYPE I");
        respuesta = controlIn.readLine();
        System.out.println("[FTP] " + respuesta);

        // Modo pasivo para obtener puerto de datos
        controlOut.println("PASV");
        respuesta = controlIn.readLine();
        System.out.println("[FTP] " + respuesta);

        // Parsear respuesta PASV: 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
        int inicio = respuesta.indexOf('(');
        int fin = respuesta.indexOf(')');
        if (inicio == -1 || fin == -1) {
            throw new IOException("Respuesta PASV inesperada: " + respuesta);
        }
        String[] partes = respuesta.substring(inicio + 1, fin).split(",");
        String dataHost = partes[0] + "." + partes[1] + "." + partes[2] + "." + partes[3];
        int dataPort = Integer.parseInt(partes[4].trim()) * 256 + Integer.parseInt(partes[5].trim());

        // Conexion de datos
        Socket dataSocket = new Socket(dataHost, dataPort);

        // Solicitar archivo
        controlOut.println("RETR " + rutaRemota);
        respuesta = controlIn.readLine();
        System.out.println("[FTP] " + respuesta);
        if (!respuesta.startsWith("150") && !respuesta.startsWith("125")) {
            dataSocket.close();
            throw new IOException("Error al recuperar archivo FTP: " + respuesta);
        }

        // Leer datos del archivo
        BufferedReader dataIn = new BufferedReader(new InputStreamReader(dataSocket.getInputStream(), "UTF-8"));
        String linea;
        boolean primeraLinea = true;
        while ((linea = dataIn.readLine()) != null) {
            if (primeraLinea) {
                primeraLinea = false; // saltar cabecera
                continue;
            }
            Pregunta p = parsearLinea(linea);
            if (p != null) {
                preguntas.add(p);
            }
        }

        dataIn.close();
        dataSocket.close();

        // Leer respuesta de finalizacion de transferencia
        respuesta = controlIn.readLine();
        System.out.println("[FTP] " + respuesta);

        // Cerrar sesion FTP
        controlOut.println("QUIT");
        respuesta = controlIn.readLine();
        System.out.println("[FTP] " + respuesta);

        controlIn.close();
        controlOut.close();
        controlSocket.close();

        System.out.println("[FTP] Cargadas " + preguntas.size() + " preguntas desde servidor FTP.");
        return preguntas;
    }

    /**
     * Parsea una linea CSV y devuelve una Pregunta.
     */
    private static Pregunta parsearLinea(String linea) {
        if (linea == null || linea.trim().isEmpty()) {
            return null;
        }
        String[] campos = linea.split(SEPARADOR);
        if (campos.length < 6) {
            System.err.println("[CSV] Linea con formato incorrecto (se esperan 6 campos): " + linea);
            return null;
        }
        try {
            String enunciado = campos[0].trim();
            String opA = campos[1].trim();
            String opB = campos[2].trim();
            String opC = campos[3].trim();
            String opD = campos[4].trim();
            char respuesta = campos[5].trim().toUpperCase().charAt(0);

            if (respuesta != 'A' && respuesta != 'B' && respuesta != 'C' && respuesta != 'D') {
                System.err.println("[CSV] Respuesta invalida (debe ser A/B/C/D): " + campos[5]);
                return null;
            }

            return new Pregunta(enunciado, opA, opB, opC, opD, respuesta);
        } catch (Exception e) {
            System.err.println("[CSV] Error parseando linea: " + linea + " -> " + e.getMessage());
            return null;
        }
    }
}