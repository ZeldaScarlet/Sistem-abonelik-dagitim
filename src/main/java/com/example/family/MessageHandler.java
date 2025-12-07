package com.example.family;

import family.NodeInfo;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;


public class MessageHandler {

    private final String storageDir;

    public MessageHandler(int port) {
        this.storageDir = "messages_" + port;

        try {
            Path path = Paths.get(storageDir);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                System.out.println("messages_" + port + " : klasörü oluşturuldu: " + path.toAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("messages_" + port + " : klasörü oluştuluramadı: " + e.getMessage());
        }
    }

    public void saveMessage(int id, String content) throws IOException {

        File file = new File(storageDir, id + ".txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(content);
        }
        System.out.println("Mesaj kaydedildi : " + storageDir + "/" + file.getName());
    }

    public String readMessage(int id) throws IOException {

        File file = new File(storageDir, id + ".txt");
        if (!file.exists()) {
            throw new FileNotFoundException("Mesaj bulunamadı: " + storageDir + "/" + file.getName());
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