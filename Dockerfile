# Usa una imagen base con Maven para compilar y ejecutar la aplicación
FROM eclipse-temurin:17-jdk as builder

# Establece el directorio de trabajo para la compilación
WORKDIR /app

# Copia los archivos del proyecto al contenedor
COPY . .

# Ejecuta el comando Maven para construir el JAR
RUN ./mvnw clean package -DskipTests

# Usa una imagen más ligera para ejecutar la aplicación
FROM eclipse-temurin:17-jdk

# Establece el directorio de trabajo para la ejecución
WORKDIR /app

# Copia el JAR generado desde la etapa anterior
COPY --from=builder /app/target/gameapp-0.0.1-SNAPSHOT.jar app.jar

# Expone el puerto en el que la aplicación escuchará
EXPOSE 8080

# Comando para ejecutar la aplicación
ENTRYPOINT ["java", "-jar", "app.jar"]
