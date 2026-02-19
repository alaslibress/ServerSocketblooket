# Dockerfile para el Servidor Blooket
# Usa OpenJDK 17 sobre Alpine Linux para imagen ligera

FROM openjdk:17-jdk-slim

LABEL maintainer="hlanz"
LABEL description="Servidor Blooket - Juego de preguntas tipo Kahoot"

# Directorio de trabajo
WORKDIR /app

# Copiar codigo fuente del servidor
COPY servidor/src/main/java/org/hlanz/servidor/*.java ./src/org/hlanz/servidor/

# Copiar CSV de preguntas
COPY servidor/src/main/resources/preguntas.csv ./preguntas.csv

# Compilar
RUN mkdir -p out && \
    javac -d out src/org/hlanz/servidor/*.java

# Exponer puerto SSL
EXPOSE 8080

# Variables de entorno configurables
ENV JAVA_OPTS="-Djava.net.preferIPv4Stack=true"

# Ejecutar el servidor
# Nota: al ser interactivo, se debe ejecutar con docker run -it
CMD ["java", "-cp", "out", "org.hlanz.servidor.ServidorJuego"]