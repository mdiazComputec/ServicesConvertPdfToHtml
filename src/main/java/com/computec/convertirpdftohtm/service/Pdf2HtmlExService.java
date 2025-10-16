package com.computec.convertirpdftohtm.service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class Pdf2HtmlExService {

	@Value("${converter.docker.bin}")
    private String dockerBin;

	@Value("${converter.docker.image.ocr}")
	private String ocrImage;
    
	@Value("${converter.docker.image.pdf2htmlex}")
	private String pdf2htmlImage;

    @Value("${converter.pdf2htmlex.zoom}")
    private String zoom;

    @Value("${converter.ocr.enabled}")
    private boolean ocrEnabled;

    @Value("${converter.ocr.lang}")
    private String ocrLang;

    @Value("${converter.ocr.force}")
    private boolean ocrForce;

    /** Versión extendida: permite pasar zoom/ocr/lang por request */
    public byte[] convertViaDocker(byte[] pdfBytes, Float zoomOverride, Boolean ocrOverride, String langOverride) throws Exception {
        Path work = Files.createTempDirectory("p2h-");
        try {
            Path inputPdf = work.resolve("input.pdf");
            Path htmlOut  = work.resolve("output.html");
            Files.write(inputPdf, pdfBytes);

            // Determina la configuración efectiva para esta petición sin modificar el estado del servicio.
            final String effectiveZoom = (zoomOverride != null) ? Float.toString(zoomOverride) : this.zoom;
            final boolean effectiveOcrEnabled = (ocrOverride != null) ? ocrOverride : this.ocrEnabled;
            final String effectiveOcrLang = (langOverride != null && !langOverride.isBlank()) ? langOverride : this.ocrLang;

            // 1) OCR (si está habilitado)
            Path pdfForHtml = inputPdf;
            if (effectiveOcrEnabled) {
                pdfForHtml = runOcrMyPdfViaDocker(work, inputPdf, effectiveOcrLang, ocrForce);
            }

            // 2) Convertir a HTML
            runPdf2HtmlExViaDocker(pdfForHtml, htmlOut, work, effectiveZoom);

            // 3) Zipear TODO (salvo input original si quieres excluirlo)
            Files.deleteIfExists(inputPdf); // opcional
            return zipDirectory(work);

        } finally {
            // Limpiar el directorio temporal
            deleteRecursive(work);
        }
    }

    /** Corre ocrmypdf en Docker y devuelve la ruta del PDF resultante (ocr.pdf) */
    private Path runOcrMyPdfViaDocker(Path workDir, Path inputPdf, String lang, boolean force) throws Exception {
        Path ocrPdf = workDir.resolve("ocr.pdf");
        String mount = workDir.toAbsolutePath().toString() + ":/work";

        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBin); cmd.add("run"); cmd.add("--rm");
        cmd.add("-v"); cmd.add(mount);
        cmd.add("-w"); cmd.add("/work");
        cmd.add(ocrImage);

        // ocrmypdf flags
        // --skip-text (si NO quieres re-OCR de páginas con texto)
        // --force-ocr (si quieres forzar OCR en todas las páginas)
        cmd.add(force ? "--force-ocr" : "--skip-text");
        cmd.add("-l"); cmd.add(lang);
        cmd.add(inputPdf.getFileName().toString());
        cmd.add(ocrPdf.getFileName().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) System.out.println("[OCR] " + line);
        }
        if (!p.waitFor(600_000, java.util.concurrent.TimeUnit.MILLISECONDS)) { // hasta 10 min (OCR puede tardar)
            p.destroyForcibly();
            throw new RuntimeException("Timeout ejecutando OCRmyPDF en Docker");
        }
        int code = p.exitValue();
        if (code != 0) throw new RuntimeException("ocrmypdf terminó con código " + code);

        if (!Files.exists(ocrPdf)) throw new FileNotFoundException("No se generó ocr.pdf");
        return ocrPdf;
    }

    private void runPdf2HtmlExViaDocker(Path pdf, Path outHtml, Path workDir, String zoomValue) throws Exception {
        String mount = workDir.toAbsolutePath().toString() + ":/work";

        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBin); cmd.add("run"); cmd.add("--rm");
        cmd.add("--user"); cmd.add("0"); // Ejecutar como root para evitar problemas de permisos
        cmd.add("-v"); cmd.add(mount);
        cmd.add("-w"); cmd.add("/work");
        cmd.add(pdf2htmlImage);
        // No agregues "pdf2htmlEX": es el ENTRYPOINT de la imagen

        cmd.add("--zoom"); cmd.add(zoomValue);
        cmd.add("--split-pages"); cmd.add("0");
        cmd.add("--embed-css"); cmd.add("0");
        cmd.add("--embed-font"); cmd.add("0");
        cmd.add("--embed-image"); cmd.add("0");
        cmd.add("--fallback"); cmd.add("1"); // Parámetro de estabilidad
        cmd.add("--process-outline"); cmd.add("0"); // Parámetro de estabilidad
        cmd.add(workDir.relativize(pdf).toString());
        cmd.add(outHtml.getFileName().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true);

        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) System.out.println("[P2H] " + line);
        }
        if (!p.waitFor(300_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("Timeout ejecutando pdf2htmlEX en Docker");
        }
        int code = p.exitValue();
        if (code != 0) throw new RuntimeException("docker run (pdf2htmlEX) terminó con código " + code);
        if (!Files.exists(outHtml)) throw new FileNotFoundException("No se generó output.html");
    }

    private static byte[] zipDirectory(Path dir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            Files.walk(dir).forEach(p -> {
                try {
                    if (Files.isDirectory(p)) return;
                    String name = dir.relativize(p).toString().replace("\\", "/");
                    zos.putNextEntry(new ZipEntry(name));
                    Files.copy(p, zos);
                    zos.closeEntry();
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
        return baos.toByteArray();
    }

    private static void deleteRecursive(Path root) {
        try {
            if (!Files.exists(root)) return;
            Files.walk(root)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}