package org.hlanz.servidor;

/**
 * Representa a un jugador conectado al servidor.
 * Almacena su nombre, puntuacion y estado de respuesta.
 */
public class Jugador implements Comparable<Jugador> {

    private String nombre;
    private int puntuacion;
    private volatile char respuestaActual;   // A, B, C, D o '\0' si no ha respondido
    private volatile long tiempoRespuesta;    // timestamp en ms de cuando respondio
    private volatile boolean haRespondido;

    public Jugador(String nombre) {
        this.nombre = nombre;
        this.puntuacion = 0;
        resetearRespuesta();
    }

    public String getNombre() {
        return nombre;
    }

    public int getPuntuacion() {
        return puntuacion;
    }

    public void sumarPuntos(int puntos) {
        this.puntuacion += puntos;
    }

    public char getRespuestaActual() {
        return respuestaActual;
    }

    public long getTiempoRespuesta() {
        return tiempoRespuesta;
    }

    public boolean haRespondido() {
        return haRespondido;
    }

    /**
     * Registra la respuesta del jugador. Solo se acepta la primera respuesta.
     */
    public synchronized boolean responder(char respuesta) {
        if (haRespondido) {
            return false; // ya respondio
        }
        this.respuestaActual = Character.toUpperCase(respuesta);
        this.tiempoRespuesta = System.currentTimeMillis();
        this.haRespondido = true;
        return true;
    }

    /**
     * Resetea el estado de respuesta para la siguiente pregunta.
     */
    public void resetearRespuesta() {
        this.respuestaActual = '\0';
        this.tiempoRespuesta = 0;
        this.haRespondido = false;
    }

    @Override
    public int compareTo(Jugador otro) {
        // Orden descendente por puntuacion
        return Integer.compare(otro.puntuacion, this.puntuacion);
    }

    @Override
    public String toString() {
        return nombre + " - " + puntuacion + " pts";
    }
}