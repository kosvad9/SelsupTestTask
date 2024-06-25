package com.kosvad9.taskscheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CrptApi {
    private final long interval;
    private final int requestLimit;
    private final Semaphore semaphore;
    private final String URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";

    ObjectMapper objectMapper = new ObjectMapper();

    public CrptApi() {
        this(TimeUnit.MINUTES,1, 50);
    }

    public CrptApi(TimeUnit timeUnit, long duration, int requestLimit) {
        this.interval = timeUnit.toMillis(duration);
        this.requestLimit = requestLimit;
        semaphore = new Semaphore(requestLimit,true);
        new TimerThread().start();
    }

    public void createDocument(Document document, String signature) throws InterruptedException {
        SSLContext sslContext;
        String jsonDoc;
        try {
            jsonDoc = objectMapper.writeValueAsString(document);
            sslContext = createSSLContext(signature);
        } catch (Exception e) {
            return;
        }
        semaphore.acquire();
        try {
            sendHttp(jsonDoc, sslContext);
        } catch (Exception e) {
            semaphore.release();
        }
    }

    private void sendHttp(String document, SSLContext sslContext) throws IOException, InterruptedException {
        HttpClient client =  HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(document))
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private SSLContext createSSLContext(String signature) {
        SSLContext sslContext;
        try (ByteArrayInputStream byteArray = new ByteArrayInputStream(signature.getBytes())) {
            sslContext = SSLContext.getInstance("TLS");
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Certificate certificate = factory.generateCertificate(byteArray);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null);
            keyStore.setCertificateEntry("caCert", certificate);
            tmf.init(keyStore);
            sslContext.init(null,tmf.getTrustManagers(), null);
        } catch (NoSuchAlgorithmException | IOException | CertificateException | KeyStoreException |
                 KeyManagementException e) {
            throw new RuntimeException(e);
        }
        return sslContext;
    }

    private class TimerThread extends Thread {
        //демон для восстановления счетчика семафора через заданный интервал
        public TimerThread() {
            this.setDaemon(true);
        }

        @Override
        public void run() {
            while (true){
                try {
                    sleep(interval);
                    semaphore.release(requestLimit-semaphore.availablePermits());
                } catch (InterruptedException e) {
                    return;
                }

            }
        }
    }

    public record Document(
            Description description,
            String doc_id,
            String doc_status,
            String doc_type,
            Boolean importRequest,
            String owner_inn,
            String participant_inn,
            String producer_inn,
            LocalDate production_date,
            String production_type,
            List<Product> products,
            LocalDate reg_date,
            String red_number
    ){}

    public record Description(
            String participantInn
    ){}

    public record Product(
            String certificate_document,
            LocalDate certificate_document_date,
            String certificate_document_number,
            String owner_inn,
            String producer_inn,
            LocalDate production_date,
            String tnved_code,
            String uit_code,
            String uitu_code
    ) {}
}
