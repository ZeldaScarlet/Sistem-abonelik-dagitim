package com.example.family;

import family.*;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.*;
import java.net.Socket;


import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

public class NodeMain {

    private static final int START_PORT = 5555;
    private static final int PRINT_INTERVAL_SECONDS = 10;
    private static final ConcurrentHashMap<Integer, List<NodeInfo>> messageLocationMap = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger roundRobinCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    private static MessageHandler diskHandler;
    private static int TOLERANCE = 1;
    private static int SAVE_MODE = 1; // Varsayılan: Buffered

    public static void main(String[] args) throws Exception {
        loadToleranceConfig();
        loadMessageMap();
        String host = "127.0.0.1";
        int port = findFreePort();

        NodeInfo self = NodeInfo.newBuilder()
                .setHost(host)
                .setPort(port)
                .build();

        NodeRegistry registry = new NodeRegistry();
        diskHandler = new MessageHandler(port, SAVE_MODE);
        FamilyServiceImpl service = new FamilyServiceImpl(registry, self, SAVE_MODE);

        Server server = ServerBuilder
                .forPort(port)
                .addService(service)
                .build()
                .start();

                System.out.printf("Düğüm başlatıldı: %s:%d%n", host, port);

                // Eğer bu ilk node ise (port 5555), TCP 6666'da text dinlesin
                if (port == START_PORT) {
                    startLeaderTextListener(registry, self, diskHandler);
                }

                discoverExistingNodes(host, port, registry, self);
                startFamilyPrinter(registry, self);
                startHealthChecker(registry, self);

                server.awaitTermination();
    }

    private static void startLeaderTextListener(NodeRegistry registry, NodeInfo self, MessageHandler messageHandler) {

    new Thread(() -> {
        try (ServerSocket serverSocket = new ServerSocket(6666)) {
            System.out.printf("Leader listening for text on TCP %s:%d%n",
                    self.getHost(), 6666);

            while (true) {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClientTextConnection(client, registry, self)).start();
            }

        } catch (IOException e) {
            System.err.println("Error in leader text listener: " + e.getMessage());
        }
    }, "LeaderTextListener").start();
}

    private static void handleClientTextConnection(Socket client, NodeRegistry registry, NodeInfo self) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                System.out.println("Command: " + line);

                String[] parts = line.split("\\s+", 3);
                if (parts.length < 2) { out.println("ERROR"); continue; }

                String cmd = parts[0].toUpperCase();
                try {
                    int id = Integer.parseInt(parts[1]);

                    if ("SET".equals(cmd) && parts.length == 3) {
                        String content = parts[2];

                        // A. Lider Kaydeder
                        diskHandler.saveMessage(id, content);

                        // B. Dağıtır
                        List<NodeInfo> confirmedNodes = replicateToMembers(id, content, registry, self);

                        // C. Haritayı Güncelle
                        List<NodeInfo> allHolders = new java.util.ArrayList<>();
                        allHolders.add(self);
                        allHolders.addAll(confirmedNodes);
                        messageLocationMap.put(id, allHolders);

                        // --- EKLENEN KISIM BAŞLANGIÇ ---
                        // D. Log Dosyasına Yaz (Kalıcılık için şart!)
                        // Sadece replika yapılanları yazıyoruz, lider zaten belli.
                        writeMessageMap(id, confirmedNodes);
                        // --- EKLENEN KISIM BİTİŞ ---

                        if (confirmedNodes.size() < Math.min(TOLERANCE, registry.snapshot().size() - 1)) {
                            System.out.println("⚠️ Warning: Desired tolerance not met.");
                        }

                        out.println("OK");
                    } else if ("GET".equals(cmd)) {
                        String result = null;

                        // A. Önce Lider Kendine Bakar
                        try {
                            result = diskHandler.readMessage(id);
                            System.out.println("   -> Found locally.");
                        } catch (IOException e) {
                            // Liderde yok veya dosya silinmiş
                            System.out.println("   -> Not found locally, checking network...");
                        }

                        // B. Liderde Yoksa Üyelere Bak
                        if (result == null) {
                            result = fetchFromMembers(id, self);
                        }

                        if (result != null) {
                            out.println(result);
                        } else {
                            out.println("NOT_FOUND");
                        }
                    }

                } catch (Exception e) {
                    out.println("ERROR " + e.getMessage());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void discoverExistingNodes(String host, int selfPort, NodeRegistry registry, NodeInfo self) {

        for (int port = START_PORT; port < selfPort; port++) {
            ManagedChannel channel = null;
            try {
                channel = ManagedChannelBuilder
                        .forAddress(host, port)
                        .usePlaintext()
                        .build();

                FamilyServiceGrpc.FamilyServiceBlockingStub stub =
                        FamilyServiceGrpc.newBlockingStub(channel);

                FamilyView view = stub.join(self);
                registry.addAll(view.getMembersList());

                System.out.printf("Joined through %s:%d, family size now: %d%n",
                        host, port, registry.snapshot().size());

            } catch (Exception ignored) {
            } finally {
                if (channel != null) channel.shutdownNow();
            }
        }
    }

    private static void startHealthChecker(NodeRegistry registry, NodeInfo self) {

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(() -> {
            List<NodeInfo> members = registry.snapshot();

            for (NodeInfo n : members) {
                if (n.getPort() == self.getPort()) {
                    continue;
                }

                ManagedChannel channel = null;
                try {
                    channel = ManagedChannelBuilder
                            .forAddress(n.getHost(), n.getPort())
                            .usePlaintext()
                            .build();

                    FamilyServiceGrpc.FamilyServiceBlockingStub stub =
                            FamilyServiceGrpc.newBlockingStub(channel);

                    stub.getFamily(Empty.newBuilder().build());

                } catch (Exception e) {
                    System.out.printf("Node %s:%d yanıt vermiyor, aileden çıkarılıyor...%n",
                            n.getHost(), n.getPort());

                    registry.remove(n);

                } finally {
                    if (channel != null) {
                        channel.shutdownNow();
                    }
                }
            }

        }, 5, 10, TimeUnit.SECONDS);
    }

    private static List<NodeInfo> replicateToMembers(int msgId, String content, NodeRegistry registry, NodeInfo self) {
        List<NodeInfo> successNodes = new java.util.ArrayList<>();

        List<NodeInfo> allMembers = registry.snapshot();

        List<NodeInfo> candidates = new java.util.ArrayList<>();
        for (NodeInfo n : allMembers) {
            if (!(n.getPort() == 5555)) {
                candidates.add(n);
            }
        }

        int targetCount = Math.min(TOLERANCE, candidates.size());

        List<NodeInfo> targets = selectNodesRoundRobin(candidates, TOLERANCE);

        for (NodeInfo target : targets) {
            ManagedChannel channel = null;
            try {
                channel = ManagedChannelBuilder.forAddress(target.getHost(), target.getPort())
                        .usePlaintext().build();
                FamilyServiceGrpc.FamilyServiceBlockingStub stub = FamilyServiceGrpc.newBlockingStub(channel);

                family.StoredMessage msg = family.StoredMessage.newBuilder()
                        .setId(msgId)
                        .setText(content)
                        .build();

                family.StoreResult result = stub.store(msg);

                if (result.getSuccess()) {
                    successNodes.add(target);
                    System.out.printf("   -> %d düğümüne kaydedildi. %n", target.getPort());
                } else {
                    System.err.printf("   -> %d düğümüne kaydedilemedi. %n", target.getPort(), result.getMessage());
                }

            } catch (Exception e) {
                System.err.printf("   -> %d düğümüne ulaşılamadı. %n", target.getPort(), e.getMessage());
            } finally {
                if (channel != null) channel.shutdown();
            }
        }

        return successNodes;
    }

    private static String fetchFromMembers(int msgId, NodeInfo self) {
        List<NodeInfo> holders = messageLocationMap.get(msgId);

        if (holders == null || holders.isEmpty()) {
            return null; // Kimse bilmiyor
        }

        for (NodeInfo target : holders) {

            if (target.getPort() == self.getPort()) {
                continue;
            }

            ManagedChannel channel = null;
            try {
                System.out.printf("   -> %d mesaj, %d düğümünden alınmayı deniyor. %n", msgId, target.getPort());

                channel = ManagedChannelBuilder.forAddress(target.getHost(), target.getPort())
                        .usePlaintext().build();
                FamilyServiceGrpc.FamilyServiceBlockingStub stub = FamilyServiceGrpc.newBlockingStub(channel);

                family.MessageId request = family.MessageId.newBuilder().setId(msgId).build();
                family.StoredMessage response = stub.retrieve(request);

                String text = response.getText();

                if (text == null || text.startsWith("ERROR") || text.isEmpty()) {
                    System.out.println("   --> alınamadı sonraki düğüm deneniyor. ");
                    continue;
                }
                return text;

            } catch (Exception e) {
                System.err.printf("   --> Bağlantı hatası, sonraki düğüm deneniyor. ", target.getPort());
            } finally {
                if (channel != null) channel.shutdown();
            }
        }

        return null;
    }

    private static void loadMessageMap() {
        File file = new File("messageMap.txt");
        if (!file.exists()) {
            System.out.println("messageMap.txt bulunamadı.");
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int count = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    String[] mainParts = line.split(":");
                    if (mainParts.length < 2) continue;

                    int id = Integer.parseInt(mainParts[0].trim());

                    String portPart = mainParts[1].trim();
                    String[] ports = portPart.split("\\s+");

                    List<NodeInfo> nodeList = new java.util.ArrayList<>();

                    for (String p : ports) {
                        if (p.isEmpty()) continue;

                        NodeInfo node = NodeInfo.newBuilder()
                                .setHost("127.0.0.1")
                                .setPort(Integer.parseInt(p))
                                .build();
                        nodeList.add(node);
                    }

                    messageLocationMap.put(id, nodeList);
                    count++;

                } catch (Exception e) {
                    System.err.println("Bozuk satır:  " + line);
                }
            }
        } catch (IOException e) {
            System.err.println("messageMap.txt yüklenirken hata oluştu: " + e.getMessage());
        }
    }

    public static void writeMessageMap(int messageId, List<NodeInfo> nodes) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter("messageMap.txt", true))) {

            StringBuilder sb = new StringBuilder();

            // Format: ID:
            sb.append(messageId).append(":");

            // Format: Port Port Port
            for (NodeInfo n : nodes) {
                sb.append(" ").append(n.getPort());
            }

            // Satırı dosyaya yaz ve yeni satıra geç
            writer.write(sb.toString());
            writer.newLine();

            System.out.println("messageMap.txt güncellendi: " + sb.toString());

        } catch (IOException e) {
            System.err.println("messageMap.txt güncellenirken hata oluştu: " + e.getMessage());
        }
    }

    private static void loadSaveConfig() {
        File file = new File("save.conf");
        if (!file.exists()) {
            System.out.println("save.conf bulunamadı, varsayılan mod (Buffered) kullanılıyor.");
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            // Dosyada sadece "1", "2" veya "3" yazması yeterli
            if (line != null) {
                SAVE_MODE = Integer.parseInt(line.trim());
                System.out.println("Disk Yazma Modu: " + getModeName(SAVE_MODE));
            }
        } catch (Exception e) {
            System.err.println("save.conf okuma hatası: " + e.getMessage());
        }
    }

    private static String getModeName(int mode) {
        switch (mode) {
            case 1: return "Buffered IO (BufferedWriter)";
            case 2: return "Unbuffered IO (FileOutputStream)";
            case 3: return "Zero-Copy / NIO (FileChannel)";
            default: return "Bilinmiyor (Varsayılan Buffered)";
        }
    }

    private static List<NodeInfo> selectNodesRoundRobin(List<NodeInfo> candidates, int tolerance) {
        List<NodeInfo> selected = new java.util.ArrayList<>();
        int size = candidates.size();

        if (size == 0) return selected;

        int start = roundRobinCounter.getAndIncrement();

        for (int i = 0; i < tolerance; i++) {
            int index = (start + i) % size;
            selected.add(candidates.get(index));
        }

        return selected;
    }

    //  DEĞİŞMEYECEK FONKSİYONLAR

    private static int findFreePort() {
        int port = START_PORT;
        while (true) {
            try (ServerSocket ignored = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                port++;
            }
        }
    }

    private static void loadToleranceConfig() {
        File file = new File("tolerance.conf");
        if (!file.exists()) {
            System.out.println("Hata: tolerance.conf bulunamadı, varsayılan: : " + TOLERANCE);
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            if (line != null && line.startsWith("TOLERANCE=")) {
                TOLERANCE = Integer.parseInt(line.split("=")[1].trim());
                System.out.println("Tolerans:  " + TOLERANCE);
            }
        } catch (Exception e) {
            System.err.println("Hata: tolerance.conf okunamadı.  " + e.getMessage());
        }
    }

    private static void startFamilyPrinter(NodeRegistry registry, NodeInfo self) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(() -> {
            List<NodeInfo> members = registry.snapshot();
            System.out.println("======================================");
            System.out.printf("Family at %s:%d (me)%n", self.getHost(), self.getPort());
            System.out.println("Time: " + LocalDateTime.now());
            System.out.println("Members:");

            for (NodeInfo n : members) {
                boolean isMe = n.getHost().equals(self.getHost()) && n.getPort() == self.getPort();
                System.out.printf(" - %s:%d%s%n",
                        n.getHost(),
                        n.getPort(),
                        isMe ? " (me)" : "");
            }
            System.out.println("======================================");
        }, 3, PRINT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }
}
