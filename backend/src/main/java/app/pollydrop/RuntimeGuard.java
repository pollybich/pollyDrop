package app.pollydrop;

import org.springframework.stereotype.Component;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.scheduling.annotation.Scheduled;
import javax.sql.DataSource;
import java.sql.*;
import jakarta.annotation.PreDestroy;
import java.util.stream.Collectors;

@Component
public class RuntimeGuard implements ApplicationRunner {
    private final DataSource dataSource;private final TransferService transfers;private final TransferRepository repository;private final CipherStorage storage;
    private final UploadJanitor uploads;private final DownloadTickets tickets;
    private volatile boolean ready=false;private Connection lock;
    public RuntimeGuard(DataSource dataSource,TransferService transfers,TransferRepository repository,CipherStorage storage,UploadJanitor uploads,DownloadTickets tickets){this.dataSource=dataSource;this.transfers=transfers;this.repository=repository;this.storage=storage;this.uploads=uploads;this.tickets=tickets;}
    @Override public void run(ApplicationArguments args)throws Exception {
        lock=dataSource.getConnection();
        try(var statement=lock.createStatement();var result=statement.executeQuery("SELECT pg_try_advisory_lock(7314862904521)")) {
            result.next();if(!result.getBoolean(1)){lock.close();throw new IllegalStateException("This beta supports one backend instance per database");}
        }
        // The database lock proves that a previous process can no longer own a sending lease.
        transfers.recover();tickets.recover();
        var known=repository.findAll().stream().map(t->t.id).collect(Collectors.toSet());known.addAll(uploads.retainedIds());storage.removeOrphans(known);
        transfers.cleanup();transfers.restoreExpirations();ready=true;uploads.activate(this::ready);
    }
    public synchronized boolean ready(){
        if(!ready)return false;
        try { if(lock==null || lock.isClosed() || !lock.isValid(1))ready=false; }
        catch(SQLException e){ready=false;}
        return ready;
    }
    @Scheduled(fixedDelay=1000)
    public void cleanup(){if(ready())transfers.cleanup();}
    @PreDestroy public void close()throws SQLException {ready=false;if(lock!=null)lock.close();}
}
