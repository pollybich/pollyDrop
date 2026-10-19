package app.pollydrop;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

public interface DownloadSessionRepository extends JpaRepository<DownloadSession,UUID> {
    Optional<DownloadSession> findBySecretHash(String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DownloadSession d where d.secretHash=:hash")
    Optional<DownloadSession> lockSecret(@Param("hash") String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DownloadSession d where d.id=:id")
    Optional<DownloadSession> lockId(@Param("id") UUID id);
    @Query("select count(d) from DownloadSession d where d.expiresAt>:now and d.state<>:sent and (d.started=true or d.startBefore>:now)")
    long countActive(@Param("now") Instant now,@Param("sent") DownloadTickets.State sent);
    @Modifying @Query("delete from DownloadSession d where (d.expiresAt<:now or (d.started=false and d.startBefore<:now)) and d.state<>:sending")
    void deleteExpired(@Param("now") Instant now,@Param("sending") DownloadTickets.State sending);
    @Modifying @Query("update DownloadSession d set d.state=:failed, d.activeLease=null where d.state=:sending")
    int recover(@Param("failed") DownloadTickets.State failed,@Param("sending") DownloadTickets.State sending);
}
