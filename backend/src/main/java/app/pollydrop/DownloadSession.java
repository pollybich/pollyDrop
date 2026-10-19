package app.pollydrop;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="download_sessions")
public class DownloadSession {
    @Id UUID id;
    @Column(nullable=false,unique=true,length=64) String secretHash;
    @Column(nullable=false) UUID transferId;
    @Column(nullable=false) Instant startBefore;
    @Column(nullable=false) Instant expiresAt;
    @Column(nullable=false) boolean started;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=12) DownloadTickets.State state=DownloadTickets.State.READY;
    @Column(nullable=false) long sentThrough;
    UUID activeLease;
}
