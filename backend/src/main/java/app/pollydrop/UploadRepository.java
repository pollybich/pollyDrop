package app.pollydrop;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

public interface UploadRepository extends JpaRepository<Upload,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Upload u where u.secretHash=:hash")
    Optional<Upload> lockSecret(@Param("hash") String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Upload u where u.id=:id")
    Optional<Upload> lockId(@Param("id") UUID id);
    Optional<Upload> findBySecretHash(String hash);
    long countByExpiresAtAfterAndTransferIdIsNull(Instant now);
    @Query("select u.id from Upload u where u.expiresAt<=:now or (u.transferId is not null and u.partsPurged=false)")
    List<UUID> cleanupCandidates(@Param("now") Instant now);
}
