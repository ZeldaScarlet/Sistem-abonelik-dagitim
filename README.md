# HaToKuSe - Hata Tolere Kuyruk Servisi (Dağıtık Abonelik Sistemi)

Bu proje, Java ve **gRPC** kullanılarak geliştirilmiş, dağıtık, hataya dayanıklı (fault-tolerant) ve ölçeklenebilir bir mesaj saklama sistemidir. Sistem, Lider-Üye (Leader-Follower) mimarisi üzerine kuruludur ve TCP üzerinden metin tabanlı komutlarla yönetilir.

## 🚀 Gerçekleştirilen Özellikler (Implemented Features)

Proje kapsamında tamamlanan görevler ve teknik yetenekler şunlardır:

### 1. Mimari ve İletişim
*   **Hibrit İletişim:** İstemci (Client) ile Lider arasında **TCP Socket** (Port 6666), Lider ve Üyeler arasında **gRPC** (Protobuf) iletişimi sağlandı.
*   **Dinamik Keşif (Discovery):** Yeni başlayan düğümler (Node) otomatik olarak Lideri bulur ve aileye (cluster) katılır.
*   **Command Parser:** `SET` ve `GET` komutlarını işleyen TCP dinleyici geliştirildi.

### 2. Dağıtık Veri Yönetimi ve Hata Toleransı
*   **Replikasyon (Replication):** `tolerance.conf` dosyasından okunan değere göre mesajlar `n` sayıda farklı üyeye kopyalanır.
*   **Yük Dengeleme (Load Balancing):** Mesajlar üyeler arasında **Round Robin** algoritması ile eşit şekilde dağıtılır.
*   **Failover (Hata Kurtarma):** `GET` isteği sırasında, veriyi tutan asıl üye çökmüşse, sistem otomatik olarak yedeği tutan diğer üyeye yönlenir ve veriyi getirir.
*   **Health Checker:** Lider, periyodik olarak üyeleri "ping"ler. Yanıt vermeyen (crash olan) üyeler sistemden (registry) otomatik olarak düşürülür.

### 3. Veri Kalıcılığı ve Kurtarma (Persistence & Recovery)
*   **Local Storage:** Her üye mesajları kendi diskinde `messages_PORT/` klasörü altında `ID.txt` formatında saklar.
*   **Lider Hafızası (Metadata Log):** Lider, hangi mesajın hangi üyelerde olduğunu `messageMap.txt` dosyasına (Append-Only Log) yazar.
*   **Crash Recovery:** Lider sunucusu kapatılıp açılsa bile, `messageMap.txt` dosyasını okuyarak hafızasını (RAM) geri yükler ve kaldığı yerden devam eder.

### 4. Performans Optimizasyonu (Disk I/O)
`save.conf` dosyası üzerinden ayarlanabilen 3 farklı disk yazma modu entegre edildi:
1.  **Buffered IO:** `BufferedWriter` kullanarak yüksek performanslı yazma (Varsayılan).
2.  **Unbuffered IO:** `FileOutputStream` ile doğrudan byte seviyesinde yazma.
3.  **Zero-Copy (NIO):** `FileChannel` kullanarak kernel seviyesinde hızlı veri transferi.

---

## ⚙️ Yapılandırma Dosyaları (Configuration)

Projenin kök dizininde aşağıdaki dosyaları oluşturarak sistemi yönetebilirsiniz:

### `tolerance.conf`
Hata tolerans seviyesini belirler. Mesajın kaç farklı sunucuda yedekleneceğini seçer.
```properties
TOLERANCE=2
```

### `save.conf`
Disk yazma performans modunu belirler.
```properties
# 1 = Buffered IO (Önerilen)
# 2 = Unbuffered IO
# 3 = Zero-Copy NIO
1
```

---

## 🛠️ Kurulum ve Çalıştırma

Projeyi çalıştırmak için bilgisayarınızda **Java JDK 11+** ve **Maven** (veya Gradle) yüklü olmalıdır.

### 1. Projeyi Derleyin
```bash
mvn clean package
```
*(Veya IDE üzerinden 'Rebuild Project' yapınız)*

### 2. Lider Sunucuyu Başlatın
İlk başlatılan düğüm (Port 5555) otomatik olarak **Lider** olur.
```bash
# IDE üzerinden NodeMain sınıfını çalıştırın.
# Konsol çıktısı: "Node started on 127.0.0.1:5555" ve "Leader listening for text on TCP 6666"
```

### 3. Üye Düğümleri Başlatın
Aynı kodu (`NodeMain`) farklı terminallerde tekrar çalıştırın. Otomatik olarak boş bir port bulup (5556, 5557...) Lidere bağlanacaklardır.
```bash
# Terminal 2 -> Port 5556 (Üye)
# Terminal 3 -> Port 5557 (Üye)
```

### 4. İstemci ile Bağlanın ve Test Edin
Lider sunucu **6666** portundan TCP bağlantılarını dinler. `Telnet` veya `Netcat` kullanarak bağlanabilirsiniz.

**Bağlantı:**
```bash
telnet 127.0.0.1 6666
```

**Komut Örnekleri:**

*   **Veri Kaydetme (SET):**
    ```text
    SET 100 Merhaba Dunya
    ```
    *Beklenen Cevap:* `OK`

*   **Veri Okuma (GET):**
    ```text
    GET 100
    ```
    *Beklenen Cevap:* `Merhaba Dunya`

*   **Hatalı İstek:**
    ```text
    GET 999
    ```
    *Beklenen Cevap:* `NOT_FOUND`

---

## 🧪 Test Senaryoları (Nasıl Test Edilir?)

1.  **Dağılım Testi:**
    *   `tolerance.conf` içine `TOLERANCE=1` yazın.
    *   3 Üye başlatın.
    *   Lidere 3 farklı mesaj gönderin (`SET 1`, `SET 2`, `SET 3`).
    *   Proje klasörüne gidin; `messages_5556`, `messages_5557` vb. klasörlere bakarak mesajların farklı klasörlere dağıldığını doğrulayın.

2.  **Hata Toleransı (Crash) Testi:**
    *   `TOLERANCE=2` yapın.
    *   Bir mesaj gönderin (`SET 500 TestVerisi`).
    *   Mesajın gittiği üyelerden birini (konsoldan `Replicated to X` yazısından görebilirsiniz) kapatın.
    *   Lidere `GET 500` isteği atın.
    *   Liderin, kapalı olan üyeyi atlayıp diğer üyeden veriyi başarıyla getirdiğini görün.

3.  **Lider Kurtarma (Persistence) Testi:**
    *   Sistemi çalıştırın ve birkaç veri kaydedin.
    *   Lider sunucuyu (Port 5555) tamamen kapatın.
    *   Lideri tekrar başlatın.
    *   Daha önce kaydettiğiniz bir veriyi (`GET ...`) isteyin. Liderin `messageMap.txt` dosyasından haritayı yükleyip veriyi bulduğunu doğrulayın.
