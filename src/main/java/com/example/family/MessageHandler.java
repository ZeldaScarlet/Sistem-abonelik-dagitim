package com.example.family;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class MessageHandler {

    private final String storageDir;
    private final int saveMode; // 1: Buffered, 2: Unbuffered, 3: Zero-Copy (NIO)

    // Constructor artık saveMode alıyor
    public MessageHandler(int port, int saveMode) {
        this.storageDir = "messages_" + port;
        this.saveMode = saveMode;

        try {
            Path path = Paths.get(storageDir);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                System.out.println("📁 Storage directory created: " + path.toAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("Failed to create storage directory: " + e.getMessage());
        }
    }

    public void saveMessage(int id, String content) throws IOException {
        File file = new File(storageDir, id + ".txt");

        long startTime = System.nanoTime(); // Performans ölçümü için (isteğe bağlı)

        switch (saveMode) {
            case 1:
                writeBuffered(file, content);
                break;
            case 2:
                writeUnbuffered(file, content);
                break;
            case 3:
                writeZeroCopyNIO(file, content);
                break;
            default:
                writeBuffered(file, content); // Varsayılan
        }

        long duration = (System.nanoTime() - startTime) / 1000; // mikrosaniye
        // System.out.println("💾 Saved (" + id + ") in " + duration + "µs via Mode " + saveMode);
    }

    // YÖNTEM 1: Buffered IO (BufferedWriter)
    // Bellekte tamponlayarak yazar, disk erişim sayısını azaltır.
    private void writeBuffered(File file, String content) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(content);
        }
    }

    // YÖNTEM 2: Unbuffered IO (FileOutputStream)
    // Her byte için işletim sistemine çağrı yapar (String'i byte'a çevirip yazar).
    private void writeUnbuffered(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    // YÖNTEM 3: Zero-Copy / NIO (FileChannel)
    // Java NIO kanallarını kullanır. "Zero-copy" kavramı burada verinin kernel-space'e
    // daha verimli aktarılmasını temsil eder.
    private void writeZeroCopyNIO(File file, String content) throws IOException {
        try (FileChannel channel = FileChannel.open(file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            // Buffer'dan kanala (dosyaya) doğrudan yazım
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    // Okuma fonksiyonu aynı kalabilir (Okuma için de mod eklenebilir ama şu an yazma istendi)
    public String readMessage(int id) throws IOException {
        File file = new File(storageDir, id + ".txt");
        if (!file.exists()) {
            throw new FileNotFoundException("Message " + id + " not found in " + storageDir);
        }
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        return content.toString();
    }
}