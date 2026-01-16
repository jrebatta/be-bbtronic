# Endpoint de Sincronización

## GET /api/game-sessions/{sessionCode}/sync

Este endpoint proporciona toda la información necesaria para sincronizar el estado del cliente con el servidor en una sola llamada.

### Descripción

El endpoint de sincronización devuelve un snapshot completo del estado actual de una sesión de juego, incluyendo información sobre usuarios, el juego activo, y el estado detallado del juego.

### Ruta

```
GET /api/game-sessions/{sessionCode}/sync
```

### Parámetros

| Parámetro | Tipo | Ubicación | Descripción |
|-----------|------|-----------|-------------|
| sessionCode | String | Path | Código de 4 dígitos de la sesión |

### Respuesta Exitosa

**Código:** 200 OK

**Ejemplo de Respuesta:**

```json
{
  "sessionCode": "ABC123",
  "creator": "usuario1",
  "users": [
    {
      "username": "usuario1",
      "ready": false,
      "connected": true,
      "sessionToken": "token1"
    },
    {
      "username": "usuario2",
      "ready": true,
      "connected": true,
      "sessionToken": "token2"
    }
  ],
  "currentGame": "preguntas-directas",
  "gameState": {
    "status": "IN_PROGRESS",
    "roundId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "phase": "SHOWING_QUESTIONS"
  },
  "timestamp": 1705401234567
}
```

### Campos de la Respuesta

#### Nivel Principal

| Campo | Tipo | Descripción |
|-------|------|-------------|
| sessionCode | String | Código de la sesión |
| creator | String | Username del creador de la sesión |
| users | Array\<SyncUserDTO\> | Lista completa de usuarios en la sesión |
| currentGame | String \| null | Nombre del juego actual o null si están en lobby |
| gameState | GameStateDTO \| null | Estado del juego si hay uno activo, null si no |
| timestamp | Long | Marca de tiempo del servidor (milliseconds) |

#### SyncUserDTO

| Campo | Tipo | Descripción |
|-------|------|-------------|
| username | String | Nombre del usuario |
| ready | Boolean | Si el usuario está listo para el juego |
| connected | Boolean | Si el WebSocket del usuario está activo |
| sessionToken | String | Token único de sesión del usuario |

#### GameStateDTO

| Campo | Tipo | Descripción |
|-------|------|-------------|
| status | String | Estado del juego (WAITING_QUESTIONS, IN_PROGRESS, COMPLETED) |
| roundId | String \| null | ID de la ronda actual (para Preguntas Directas) |
| phase | String | Fase actual del juego (WAITING_QUESTIONS, SHOWING_QUESTIONS, COMPLETED) |

### Valores de currentGame

- `null` - En lobby, sin juego activo
- `"preguntas-directas"` - Juego de Preguntas Directas
- `"yo-nunca-nunca"` - Juego de Yo Nunca Nunca
- `"preguntas-incomodas"` - Juego de Preguntas Incómodas
- `"quien-es-mas-probable"` - Juego de Quién Es Más Probable
- `"cultura-pendeja"` - Juego de Cultura Pendeja

### Valores de status (GameState)

- `WAITING_QUESTIONS` - Esperando que los usuarios envíen preguntas
- `IN_PROGRESS` - Juego en progreso, mostrando preguntas
- `COMPLETED` - Ronda/juego completado

### Valores de phase (GameState)

- `WAITING_QUESTIONS` - Fase de espera de preguntas
- `SHOWING_QUESTIONS` - Mostrando preguntas a los usuarios
- `COMPLETED` - Fase completada
- `UNKNOWN` - Estado desconocido

### Respuestas de Error

#### Sesión no encontrada

**Código:** 404 Not Found

```json
{
  "error": "Sesión no encontrada: Session not found: ABC123"
}
```

#### Error del servidor

**Código:** 500 Internal Server Error

```json
{
  "error": "Error al obtener sincronización: [mensaje de error]"
}
```

### Ventajas del Endpoint

✅ **Un solo endpoint para toda la sincronización** - No necesitas hacer múltiples llamadas API

✅ **Más eficiente** - Reduce el tráfico de red y mejora el rendimiento

✅ **Información de estado de conexión** - Incluye información sobre conexiones WebSocket

✅ **Timestamp para cache/validación** - Permite detectar datos obsoletos en el cliente

✅ **Estado completo del juego** - Incluye toda la información relevante del juego activo

### Ejemplo de Uso (Frontend)

```javascript
async function syncSession(sessionCode) {
  try {
    const response = await fetch(`/api/game-sessions/${sessionCode}/sync`);
    
    if (!response.ok) {
      throw new Error('Failed to sync session');
    }
    
    const data = await response.json();
    
    // Actualizar estado del cliente con los datos recibidos
    updateSessionState(data);
    
    return data;
  } catch (error) {
    console.error('Error syncing session:', error);
    throw error;
  }
}

// Uso con polling
setInterval(() => {
  syncSession(currentSessionCode);
}, 5000); // Sincronizar cada 5 segundos
```

### Notas Importantes

1. **Uso con WebSocket**: Este endpoint complementa, no reemplaza, las actualizaciones en tiempo real vía WebSocket. Úsalo para:
   - Sincronización inicial al cargar la página
   - Recuperación después de una desconexión
   - Validación periódica del estado

2. **Campo connected**: Actualmente siempre retorna `true`. En una implementación futura, puede rastrear el estado real de conexión WebSocket de cada usuario.

3. **Timestamp**: Usa este campo para implementar lógica de caché en el cliente y detectar respuestas obsoletas.

4. **gameState**: Este campo solo está presente cuando `currentGame` no es null. Si la sesión está en lobby, `gameState` será `null`.

### Casos de Uso

- **Página de Lobby**: Sincronizar lista de usuarios y su estado ready
- **Durante el Juego**: Verificar el estado actual del juego y la fase
- **Recuperación de Desconexión**: Volver a sincronizar después de perder la conexión WebSocket
- **Validación**: Asegurar que el cliente está en sync con el servidor

