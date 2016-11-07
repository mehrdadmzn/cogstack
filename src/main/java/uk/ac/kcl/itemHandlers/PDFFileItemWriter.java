package uk.ac.kcl.itemHandlers;


import org.apache.commons.io.FileUtils;
import org.apache.tika.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import uk.ac.kcl.model.Document;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Service("pdfFileItemWriter")
@Profile({"pdfFileWriter", "thumbnailFileWriter"})
public class PDFFileItemWriter implements ItemWriter<Document> {
    private static final Logger LOG = LoggerFactory.getLogger(PDFFileItemWriter.class);

    @Autowired
    Environment env;

    String outputPath;

    @PostConstruct
    public void init()  {
        this.outputPath = env.getProperty("fileOutputDirectory.pdf");
    }

    @PreDestroy
    public void destroy(){

    }

    @Override
    public final void write(List<? extends Document> documents) throws Exception {

        for (Document doc : documents) {
            long startTime = System.currentTimeMillis();
            String contentType = ((String) doc.getAssociativeArray()
                                  .getOrDefault("X-TL-CONTENT-TYPE", "TL_CONTENT_TYPE_UNKNOWN")
                                  ).toLowerCase();
            switch (contentType) {
            case "application/pdf":
                handlePdf(doc);
                break;
            case "application/msword":
                handleMSWord(doc);
                break;
            case "image/tiff":
                handleTiff(doc);
                break;
            default:
                break;
            }
            long endTime = System.currentTimeMillis();
            LOG.info("{};Content-Type:{};Time:{} ms",
                     this.getClass().getSimpleName(),
                     contentType,
                     endTime - startTime);
        }
    }

    private void handlePdf(Document doc) throws IOException {
        // Simply dump the binary content to pdf

        FileUtils.writeByteArrayToFile(
            new File(outputPath + File.separator + doc.getDocName() + ".pdf"),
            doc.getBinaryContent()
            );
    }

    private void handleMSWord(Document doc) throws IOException {
        // Use Libreoffice to convert the MS word document to pdf

        // Create a temp directory for each input document
        Path tempPath = Files.createTempDirectory(doc.getDocName());

        // Dump the MS Word content to a file in the temp directory
        File tempInputFile = new File(tempPath + File.separator + "file.doc");
        FileUtils.writeByteArrayToFile(tempInputFile, doc.getBinaryContent());

        String[] cmd = { getLibreOfficeProg(), "--convert-to", "pdf",
                         tempInputFile.getAbsolutePath(), "--headless",
                         "--outdir", tempPath.toString()};

        try {
            externalProcessHandler(tempPath, cmd, doc.getDocName());
        }
        finally {
            tempInputFile.delete();
            tempPath.toFile().delete();
        }
    }

    private void handleTiff(Document doc) throws IOException {
        // Use ImageMagick to convert the tiff image to pdf

        // Create a temp directory for each input document
        Path tempPath = Files.createTempDirectory(doc.getDocName());

        // Dump the binary content to a file in the temp directory
        File tempInputFile = new File(tempPath + File.separator + "file.tiff");
        FileUtils.writeByteArrayToFile(tempInputFile, doc.getBinaryContent());

        File tempOutputPdfFile = new File(tempPath + File.separator + "file.pdf");
        String[] cmd = { getImageMagickProg(), tempInputFile.getAbsolutePath(),
                         tempOutputPdfFile.getAbsolutePath()};

        try {
            externalProcessHandler(tempPath, cmd, doc.getDocName());
        }
        finally {
            tempInputFile.delete();
            tempPath.toFile().delete();
        }
    }

    private void externalProcessHandler(Path tempPath, String[] cmd,
                                        String docName
                                        ) throws IOException {

        Process process = new ProcessBuilder(cmd).start();
        IOUtils.closeQuietly(process.getOutputStream());
        InputStream processInputStream = process.getInputStream();
        logStream(processInputStream);
        FutureTask<Integer> waitTask = new FutureTask<>(process::waitFor);
        Thread waitThread = new Thread(waitTask);
        waitThread.start();
        try {
            waitTask.get(30, TimeUnit.SECONDS);

            // Move the file to the configured output path
            Path tempOutputFile = tempPath.resolve("file.pdf");
            Path outputFile = Paths.get(outputPath, docName + ".pdf");
            Files.move(tempOutputFile, outputFile);

        } catch (InterruptedException e) {
            waitThread.interrupt();
            process.destroy();
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // should not be thrown
            LOG.error(e.getMessage());
        } catch (TimeoutException e) {
            waitThread.interrupt();
            process.destroy();
            LOG.error(e.getMessage());
        }
    }

    public static String getImageMagickProg() {
        return System.getProperty("os.name").startsWith("Windows") ? "convert.exe" : "convert";
    }

    private String getLibreOfficeProg() {
        return System.getProperty("os.name").startsWith("Windows") ? "soffice.exe" : "soffice";
    }

    private void logStream(final InputStream stream) {
        new Thread() {
            public void run() {
                Reader reader = new InputStreamReader(stream, IOUtils.UTF_8);
                StringBuilder out = new StringBuilder();
                char[] buffer = new char[1024];
                try {
                    for (int n = reader.read(buffer); n != -1; n = reader.read(buffer)) {
                        out.append(buffer, 0, n);
                    }
                } catch (Exception e) {
                    LOG.error(e.getMessage());
                } finally {
                    IOUtils.closeQuietly(stream);
                    IOUtils.closeQuietly(reader);
                }
                LOG.debug(out.toString());
            }
        }.start();
    }
}