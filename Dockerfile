# Etapa 1: Compilación del proyecto
FROM eclipse-temurin:21-jdk as builder

WORKDIR /app

# Copia solo los archivos necesarios para instalar dependencias y compilar
COPY .mvn/ .mvn
COPY mvnw pom.xml ./

# Da permisos al wrapper
RUN chmod +x mvnw

# Descarga dependencias antes de copiar el código completo (mejora el cache)
RUN ./mvnw dependency:go-offline

# Luego copia el código fuente completo
COPY src ./src
COPY src/main/resources/application.properties ./src/main/resources/application.properties

# Ejecuta el build sin pruebas
RUN ./mvnw clean package -DskipTests

# Etapa 2: Imagen liviana para ejecutar
FROM eclipse-temurin:21-jdk

WORKDIR /app

# Copia solo el .jar generado
COPY --from=builder /app/target/gameapp-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]