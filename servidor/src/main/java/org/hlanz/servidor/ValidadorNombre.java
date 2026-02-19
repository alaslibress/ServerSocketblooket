package org.hlanz.servidor;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Validador de nombres de usuario.
 * Reglas:
 * - Longitud maxima: 10 caracteres
 * - Solo caracteres UTF-8 validos
 * - Se prohíben nombres con solo caracteres ASCII art / especiales
 * - Se convierten a minusculas para comparar
 * - Lista negra de nombres prohibidos
 * - No se permiten nombres duplicados
 */
public class ValidadorNombre {

    private static final int LONGITUD_MAXIMA = 10;

    // Lista de nombres prohibidos (en minusculas)
    private static final Set<String> NOMBRES_PROHIBIDOS = new HashSet<>(Arrays.asList(
            "nigger",
            "niger",
            "nijer",
            "pedro sanchez",
            "pedrosanchez",
            "admin",
            "servidor",
            "server",
            "root"
    ));

    // Conjunto de nombres ya registrados en la partida
    private final Set<String> nombresRegistrados;

    public ValidadorNombre() {
        this.nombresRegistrados = new HashSet<>();
    }

    /**
     * Valida un nombre de usuario y devuelve un mensaje de error o null si es valido.
     */
    public synchronized String validar(String nombre) {
        if (nombre == null || nombre.trim().isEmpty()) {
            return "ERROR: El nombre no puede estar vacio.";
        }

        String nombreLimpio = nombre.trim();

        // Verificar longitud
        if (nombreLimpio.length() > LONGITUD_MAXIMA) {
            return "ERROR: El nombre no puede tener mas de " + LONGITUD_MAXIMA + " caracteres.";
        }

        // Verificar que sea UTF-8 valido
        CharsetEncoder encoder = Charset.forName("UTF-8").newEncoder();
        if (!encoder.canEncode(nombreLimpio)) {
            return "ERROR: El nombre contiene caracteres no validos (solo UTF-8).";
        }

        // Prohibir nombres compuestos solo por caracteres ASCII art/especiales
        // (solo simbolos, sin letras ni numeros)
        if (esAsciiArt(nombreLimpio)) {
            return "ERROR: El nombre no puede estar compuesto solo por simbolos ASCII.";
        }

        // Comprobar lista negra (en minusculas)
        String nombreMinuscula = nombreLimpio.toLowerCase();
        if (NOMBRES_PROHIBIDOS.contains(nombreMinuscula)) {
            return "ERROR: Ese nombre no esta permitido.";
        }

        // Comprobar que contenga al menos una letra
        boolean tieneLetra = false;
        for (char c : nombreLimpio.toCharArray()) {
            if (Character.isLetter(c)) {
                tieneLetra = true;
                break;
            }
        }
        if (!tieneLetra) {
            return "ERROR: El nombre debe contener al menos una letra.";
        }

        // Comprobar duplicados
        if (nombresRegistrados.contains(nombreMinuscula)) {
            return "ERROR: Ese nombre ya esta en uso.";
        }

        return null; // nombre valido
    }

    /**
     * Registra un nombre como ocupado.
     */
    public synchronized void registrar(String nombre) {
        nombresRegistrados.add(nombre.trim().toLowerCase());
    }

    /**
     * Elimina un nombre del registro (cuando un jugador se desconecta).
     */
    public synchronized void eliminar(String nombre) {
        nombresRegistrados.remove(nombre.trim().toLowerCase());
    }

    /**
     * Genera un nombre automatico basado en "juan".
     * Si "juan" ya esta en uso, prueba "juan2", "juan3", etc.
     */
    public synchronized String generarNombreAutomatico() {
        String base = "juan";
        if (!nombresRegistrados.contains(base)) {
            return base;
        }
        int sufijo = 2;
        while (nombresRegistrados.contains(base + sufijo)) {
            sufijo++;
        }
        return base + sufijo;
    }

    /**
     * Comprueba si un texto esta compuesto solo por simbolos ASCII art.
     */
    private boolean esAsciiArt(String texto) {
        for (char c : texto.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return true;
    }
}