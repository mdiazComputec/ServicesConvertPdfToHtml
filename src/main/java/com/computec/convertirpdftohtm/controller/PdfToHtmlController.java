package com.computec.convertirpdftohtm.controller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.computec.convertirpdftohtm.service.Pdf2HtmlExService;

@RestController
@RequestMapping("/convert")
public class PdfToHtmlController {
	private final Pdf2HtmlExService service;

    public PdfToHtmlController(Pdf2HtmlExService service) {
        this.service = service;
    }

    @Value("${converter.docker.image:pdf2htmlex/pdf2htmlex:0.18.8.rc1-master-20200630-Ubuntu-focal-x86_64}")
    private String dockerImage;

    @Value("${converter.docker.bin:docker}")
    private String dockerBin;

    /**
     * Sube un PDF y devuelve un ZIP (output.html + assets) generado por pdf2htmlEX dentro de Docker.
     * Param opcional "zoom" (1.0–2.0); si no lo envías, usa el valor por defecto del servicio.
     */
    @PostMapping(value = "/pdf-to-html", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> pdfToHtml(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "zoom", required = false) Float zoom,
            @RequestParam(value = "ocr", required = false) Boolean ocr,           // <- nuevo
            @RequestParam(value = "lang", required = false) String lang           // <- nuevo
    ) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes adjuntar un archivo en el campo 'file'.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Solo se acepta .pdf");
        }

        byte[] zip = service.convertViaDocker(file.getBytes(), zoom, ocr, lang);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"html_export.zip\"")
                .body(zip);
    }

    /** Healthcheck simple. */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    /**
     * Autotest: verifica que Docker esté accesible y que la imagen esté disponible localmente.
     * Útil para diagnosticar "no encuentra docker" o "no está la imagen".
     */
    @GetMapping("/selfcheck")
    public ResponseEntity<Map<String, Object>> selfcheck() {
        Map<String, Object> out = new HashMap<>();
        out.put("dockerImage", dockerImage);

        // docker --version
        try {
            Process p = new ProcessBuilder(dockerBin, "--version").redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            int code = p.waitFor();
            out.put("dockerAvailable", code == 0);
            out.put("dockerVersion", sb.toString().trim());
        } catch (Exception e) {
            out.put("dockerAvailable", false);
            out.put("dockerVersion", e.getMessage());
        }

        // docker image inspect <image>
        try {
            Process p = new ProcessBuilder(dockerBin, "image", "inspect", dockerImage)
                    .redirectErrorStream(true).start();
            int code = p.waitFor();
            out.put("imagePresentLocally", code == 0);
            if (code != 0) {
                out.put("hint", "Ejecuta: docker pull " + dockerImage);
            }
        } catch (Exception e) {
            out.put("imagePresentLocally", false);
            out.put("imageCheckError", e.getMessage());
        }

        return ResponseEntity.ok(out);
    }
}