package app.pollydrop;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="uploads")
public class Upload {
    @Id UUID id;
    @Column(nullable=false,unique=true,length=64) String secretHash;
    @Column(nullable=false,length=240) String name;
    @Column(nullable=false) long byteSize;
    @Column(nullable=false,length=64) String fingerprint;
    @Column(nullable=false) long uploadedBytes;
    @Column(nullable=false) Instant createdAt;
    @Column(nullable=false) Instant expiresAt;
    @Column(nullable=false) int maxDownloads;
    @Column(length=256) String passwordHash;
    UUID transferId;
    @Column(nullable=false) boolean partsPurged;
}
