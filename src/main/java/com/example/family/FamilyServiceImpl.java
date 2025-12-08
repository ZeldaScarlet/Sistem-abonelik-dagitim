package com.example.family;
import com.google.protobuf.Message;
import family.*;
import io.grpc.stub.StreamObserver;
import java.io.FileNotFoundException;

public class FamilyServiceImpl extends FamilyServiceGrpc.FamilyServiceImplBase {

    private final NodeRegistry registry;

    private final MessageHandler messageHandler;

    public FamilyServiceImpl(NodeRegistry registry, NodeInfo self) {
        this.registry = registry;
        this.registry.add(self);

        this.messageHandler = new MessageHandler(self.getPort());
    }

    @Override
    public void join(NodeInfo request, StreamObserver<FamilyView> responseObserver) {
        registry.add(request);

        FamilyView view = FamilyView.newBuilder()
                .addAllMembers(registry.snapshot())
                .build();

        responseObserver.onNext(view);
        responseObserver.onCompleted();
    }

    @Override
    public void getFamily(Empty request, StreamObserver<FamilyView> responseObserver) {
        FamilyView view = FamilyView.newBuilder()
                .addAllMembers(registry.snapshot())
                .build();

        responseObserver.onNext(view);
        responseObserver.onCompleted();
    }

    @Override
    public void store(StoredMessage request, StreamObserver<StoreResult> responseObserver) {
        boolean success = true;
        String msg = "Başarıyla kaydedildi.";

        try {
            // Liderden gelen "Kaydet" emrini disk yöneticisine iletiyoruz
            messageHandler.saveMessage(request.getId(), request.getText());
        } catch (Exception e) {
            success = false;
            msg = e.getMessage();
            System.err.println("Kaydedilirken bir hata oluştu: : " + e.getMessage());
        }

        responseObserver.onNext(StoreResult.newBuilder()
                .setSuccess(success)
                .setMessage(msg)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void retrieve(MessageId request, StreamObserver<StoredMessage> responseObserver) {
        System.out.println("🔎 Disk read request for ID: " + request.getId());

        String content = "";
        try {
            // Liderden gelen "Oku" emrini disk yöneticisine iletiyoruz
            content = messageHandler.readMessage(request.getId());
        } catch (FileNotFoundException e) {
            System.err.println("Message not found: " + request.getId());
            content = "ERROR: NOT_FOUND"; // Veya boş dönebilirsin
        } catch (Exception e) {
            System.err.println("Disk read error: " + e.getMessage());
            content = "ERROR: IO_EXCEPTION";
        }

        // Bulunan içeriği lidere geri gönderiyoruz
        StoredMessage response = StoredMessage.newBuilder()
                .setId(request.getId())
                .setText(content)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
