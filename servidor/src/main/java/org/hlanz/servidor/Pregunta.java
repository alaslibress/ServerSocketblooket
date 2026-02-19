package org.hlanz.servidor;

/**
 * Modelo que representa una pregunta del juego con sus 4 opciones y respuesta correcta.
 */
public class Pregunta {

    private String enunciado;
    private String opcionA;
    private String opcionB;
    private String opcionC;
    private String opcionD;
    private char respuestaCorrecta; // A, B, C o D

    public Pregunta(String enunciado, String opcionA, String opcionB, String opcionC, String opcionD, char respuestaCorrecta) {
        this.enunciado = enunciado;
        this.opcionA = opcionA;
        this.opcionB = opcionB;
        this.opcionC = opcionC;
        this.opcionD = opcionD;
        this.respuestaCorrecta = Character.toUpperCase(respuestaCorrecta);
    }

    public String getEnunciado() {
        return enunciado;
    }

    public String getOpcionA() {
        return opcionA;
    }

    public String getOpcionB() {
        return opcionB;
    }

    public String getOpcionC() {
        return opcionC;
    }

    public String getOpcionD() {
        return opcionD;
    }

    public char getRespuestaCorrecta() {
        return respuestaCorrecta;
    }

    /**
     * Formatea la pregunta para enviarla al cliente via HTTP.
     */
    public String toHttpBody() {
        StringBuilder sb = new StringBuilder();
        sb.append("PREGUNTA: ").append(enunciado).append("\n");
        sb.append(opcionA).append("\n");
        sb.append(opcionB).append("\n");
        sb.append(opcionC).append("\n");
        sb.append(opcionD).append("\n");
        return sb.toString();
    }

    @Override
    public String toString() {
        return enunciado + " [" + respuestaCorrecta + "]";
    }
}