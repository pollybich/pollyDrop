package app.pollydrop;

import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

@Service
public class UploadJanitor {
    public static final int CHUNK_SIZE=1024*1024;
    private volatile java.util.function.BooleanSupplier available=()->false;
    private final UploadRepository repository;private final CipherStorage storage;private final TransactionTemplate tx;
    private final ScheduledThreadPoolExecutor timer=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"upload-expiry");t.setDaemon(true);return t;});
    private final ConcurrentHashMap<UUID,ScheduledFuture<?>> pending=new ConcurrentHashMap<>();
    public UploadJanitor(UploadRepository repository,CipherStorage storage,PlatformTransactionManager manager){this.repository=repository;this.storage=storage;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);timer.setRemoveOnCancelPolicy(true);}
    public static UUID part(UUID id,long offset){return UUID.nameUUIDFromBytes(("pollydrop-upload:"+id+":"+offset).getBytes(StandardCharsets.UTF_8));}
    public void schedule(UUID id,Instant deadline){pending.compute(id,(k,old)->{if(old!=null)old.cancel(false);return timer.schedule(()->{try{discard(id);}finally{pending.remove(id);}},Math.max(0,Duration.between(Instant.now(),deadline).toNanos()),TimeUnit.NANOSECONDS);});}
    public void discard(UUID id){if(!available.getAsBoolean())return;tx.executeWithoutResult(status->repository.lockId(id).ifPresent(u->{
        if(u.transferId==null&&u.expiresAt.isAfter(Instant.now()))return;
        try {
            for(long offset=0;offset<u.byteSize;offset+=CHUNK_SIZE)storage.delete(part(u.id,offset));
            u.partsPurged=true;
            if(!u.expiresAt.isAfter(Instant.now()))repository.delete(u);else repository.saveAndFlush(u);
        }catch(IOException e){/* Keep the row so the retry worker can remove every remaining encrypted part. */}
    }));}
    @Scheduled(fixedDelay=1000) public void cleanup(){if(!available.getAsBoolean())return;for(UUID id:repository.cleanupCandidates(Instant.now()))discard(id);}
    public Set<UUID> retainedIds(){Set<UUID> ids=new HashSet<>();for(Upload u:repository.findAll())if(!u.partsPurged)for(long offset=0;offset<u.byteSize;offset+=CHUNK_SIZE)ids.add(part(u.id,offset));return ids;}
    public void activate(java.util.function.BooleanSupplier available){this.available=available;restore();}
    private void restore(){for(Upload u:repository.findAll())if(u.transferId==null)schedule(u.id,u.expiresAt);cleanup();}
    @PreDestroy public void close(){available=()->false;timer.shutdownNow();pending.clear();}
}
