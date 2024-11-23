# Usa una imagen base con JDK para ejecutar Java
FROM eclipse-temurin:21-jdk

# Establece el directorio de trabajo dentro del contenedor
WORKDIR /app

# Copia el archivo JAR generado por Maven al contenedor
COPY target/gameapp-0.0.1-SNAPSHOT.jar app.jar

# Expone el puerto en el que la aplicación escuchará (Render define la variable PORT)
EXPOSE 8080

# Comando para ejecutar la aplicación
ENTRYPOINT ["java", "-jar", "app.jar"]
