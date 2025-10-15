# ================= STAGE 1: Build =================
# Usa una imagen con el JDK de Java 17 para compilar el proyecto
FROM eclipse-temurin:17-jdk-alpine as builder

# Establece el directorio de trabajo dentro del contenedor
WORKDIR /app

# Copia los archivos del wrapper de Maven
COPY .mvn/ .mvn
COPY mvnw pom.xml ./

# Otorga permisos de ejecución al wrapper de Maven
RUN chmod +x ./mvnw

# Descarga las dependencias (esto se cachea si pom.xml no cambia)
RUN ./mvnw dependency:go-offline

# Copia el código fuente
COPY src ./src

# Compila el proyecto y empaquétalo en un JAR
RUN ./mvnw package -DskipTests


# ================= STAGE 2: Runtime =================
# Usa una imagen más ligera solo con el JRE para ejecutar la aplicación
FROM eclipse-temurin:17-jre-alpine

# Establece el directorio de trabajo
WORKDIR /app

# Expone el puerto en el que corre la aplicación
EXPOSE 8080

# Copia el JAR compilado desde la etapa 'builder'
COPY --from=builder /app/target/convertirpdftohtm-*.jar app.jar

# Comando para ejecutar la aplicación al iniciar el contenedor
ENTRYPOINT ["java", "-jar", "app.jar"]
