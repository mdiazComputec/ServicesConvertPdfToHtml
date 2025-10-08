# Convertir PDF a HTML (Java + Docker) con OCR opcional

Servicio REST en **Java (Spring Boot)** que convierte **PDF → HTML** usando:

- **pdf2htmlEX** (dentro de Docker) para conservar el _look & feel_ del PDF.
- **OCRmyPDF** (dentro de Docker, opcional) cuando el PDF es un **escaneo** y no tiene texto seleccionable.

La salida es un **ZIP** con `output.html` y sus assets (CSS, imágenes, fuentes).

---

## ¿Por qué este servicio?

- **Fidelidad visual** alta con pdf2htmlEX.
- **Edición posterior**: si el PDF es escaneado, se hace **OCR** para obtener texto **editable**.
- **Sin instalar binarios** en tu host: los conversores corren **en contenedores**.
- **Fácil de integrar** en pipelines: un **solo endpoint HTTP**.

---

## Arquitectura

1. **(Opcional) OCR** con `OCRmyPDF` → genera `ocr.pdf` con texto (cuando el PDF original es escaneado).
2. **pdf2htmlEX** sobre `input.pdf` u `ocr.pdf` → produce `output.html` + assets → se comprime y se devuelve como **ZIP**.

---

## Requisitos

- **Java 17** y **Maven 3.8+**  
- **Docker** (Docker Desktop en Windows / Docker Engine en Linux/Mac)  
- Permisos para ejecutar `docker run`

---

## Configuración

Archivo: `src/main/resources/application.properties`

```properties
spring.application.name=convertirpdftohtm
server.port=8080
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=50MB

# Binario docker (generalmente 'docker')
converter.docker.bin=docker

# Imágenes Docker (recomendadas)
converter.docker.image.pdf2htmlex=pdf2htmlex/pdf2htmlex:0.18.8.rc1-master-20200630-Ubuntu-focal-x86_64
converter.docker.image.ocr=jbarlow83/ocrmypdf-alpine:latest

# Parámetros por defecto
converter.pdf2htmlex.zoom=1.3      # 1.0–2.0 (más = más nítido y más pesado)
converter.ocr.enabled=true          # correr OCR por defecto
converter.ocr.lang=spa+eng          # idiomas de OCR (Tesseract)
converter.ocr.force=true            # true: forzar OCR aunque haya algo de texto
```

> También puedes sobreescribir estas propiedades con variables de entorno o `-Dprop=valor`.

---

## Compilar y ejecutar (host)

```bash
mvn clean package -DskipTests
java -jar target/convertirpdftohtm-*.jar
```

La app abre en `http://localhost:8080`

**Pre-pull** de imágenes (recomendado):

```bash
docker pull jbarlow83/ocrmypdf-alpine:latest
docker pull pdf2htmlex/pdf2htmlex:0.18.8.rc1-master-20200630-Ubuntu-focal-x86_64
```

---

## Ejecutar con Docker Compose (opcional)

Incluimos un `Dockerfile` y un `docker-compose.yml` que:
- construyen una imagen con **Java 17 + docker CLI**,  
- montan el **socket de Docker** del host en el contenedor para que el servicio pueda lanzar `ocrmypdf` y `pdf2htmlEX`.

> **Requisito:** Docker Desktop en modo **Linux containers** (Windows) o Docker Engine en Linux/macOS.

```bash
# 1) Compilar el JAR
mvn clean package -DskipTests

# 2) Levantar con Compose
docker compose up --build
# o: docker-compose up --build
```

La app quedará accesible en `http://localhost:8080`.

---

## Endpoints

### Health

```
GET /convert/health
```
Respuesta: `OK`

### Self-check (Docker e imágenes)

```
GET /convert/selfcheck
```
Devuelve un JSON con:
- versión de Docker,
- si las imágenes configuradas están presentes localmente (o sugerencia de `docker pull`).

### Convertir PDF → HTML (ZIP)

```
POST /convert/pdf-to-html
Content-Type: multipart/form-data
Campos:
- file: (archivo .pdf) [obligatorio]
- ocr:  true|false     [opcional]  (por defecto: según properties)
- lang: ej. "spa+eng"  [opcional]  (por defecto: según properties)
- zoom: ej. 1.2        [opcional]  (por defecto: según properties)
```

**Respuesta**: `application/zip` con `output.html` y recursos.

---

## Ejemplos (curl)

### Windows (CMD) — usar **form-data** (recomendado)
```bat
curl.exe -F "file=@C:\ruta\reporte.pdf" ^
  -F "ocr=true" -F "lang=spa+eng" -F "zoom=1.2" ^
  http://localhost:8080/convert/pdf-to-html -o C:\ruta\html_export.zip
```

### Windows (CMD) — con query (hay que escapar)
```bat
curl.exe -F "file=@C:\ruta\reporte.pdf" ^
  "http://localhost:8080/convert/pdf-to-html?ocr=true^&lang=spa%2Beng^&zoom=1.2" ^
  -o C:\ruta\html_export.zip
```

### PowerShell
```powershell
curl.exe -F "file=@`"C:\ruta\reporte.pdf`"" `
  -F "ocr=true" -F "lang=spa+eng" -F "zoom=1.2" `
  http://localhost:8080/convert/pdf-to-html -o "C:\ruta\html_export.zip"
```

### Linux / macOS
```bash
curl -F "file=@/ruta/reporte.pdf" \
     -F "ocr=true" -F "lang=spa+eng" -F "zoom=1.2" \
     http://localhost:8080/convert/pdf-to-html -o html_export.zip
```

También puedes abrir `test.html` (incluido) en tu navegador y subir el PDF desde allí.

---

## Solución de problemas

- **`pull access denied` para OCR** → usa **`jbarlow83/ocrmypdf-alpine`** (o `jbarlow83/ocrmypdf`). Haz:
  ```bash
  docker pull jbarlow83/ocrmypdf-alpine:latest
  ```

- **`docker run terminó con código 125`** → imagen no encontrada o parámetros inválidos. Revisa `/convert/selfcheck` y las propiedades.

- **HTML sale “como imagen”** → el PDF era un escaneo → usa `ocr=true` (OCRmyPDF).

- **Windows + CURL con query** → `&` separa comandos y `+` se interpreta como espacio. Usa **form-data** o escapa `&` como `^&` y `+` como `%2B`.

- **Rendimiento / tamaño** → baja `zoom` (1.0–2.0). Menor zoom = más liviano.

---

## Seguridad y límites

- Límites de tamaño: `spring.servlet.multipart.max-*` en properties.
- Archivos se procesan en **carpetas temporales** y se limpian al finalizar.
- Si será público, considera:
  - autenticación,
  - antivirus (ClamAV sidecar),
  - límites de tiempo/tamaño estrictos.

---

## Créditos

- [pdf2htmlEX] – conversor PDF→HTML de alta fidelidad.  
- [OCRmyPDF] – OCR (Tesseract) para PDFs escaneados.  
- Spring Boot – marco para el servicio REST.

---

## Licencia

Elige la licencia de tu preferencia (ej. MIT) y crea un archivo `LICENSE`.
