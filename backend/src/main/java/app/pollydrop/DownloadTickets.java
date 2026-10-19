package app.pollydrop;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.util.*;

@Service
public class DownloadTickets {
    public enum State {READY,SENDING,PARTIAL,SENT,FAILED,EXPIRED}
    public record Ticket(UUID sessionId,TransferService.Lease lease,long sentThrough){}
    public record Issued(String url,String statusUrl,Instant expiresAt){}
    private final TransferService transfers;private final DownloadSessionRepository repository;private final Clock clock;
    @Autowired public DownloadTickets(TransferService transfers,DownloadSessionRepository repository){this(transfers,repository,Clock.systemUTC());}
    DownloadTickets(TransferService transfers,DownloadSessionRepository repository,Clock clock){this.transfers=transfers;this.repository=repository;this.clock=clock;}
    @Transactional
    public Issued issue(String readToken,String password,String client){
        if(repository.countActive(clock.instant(),State.SENT)>=1024)throw new ApiFailure(429,"SERVER_BUSY","Сервис занят. Попробуйте через минуту.");
        UUID id=transfers.authorizeDownload(readToken,password,client);var view=transfers.authorizedView(id);
        String secret=Tokens.newToken();DownloadSession d=new DownloadSession();d.id=UUID.randomUUID();d.secretHash=Tokens.hash(secret);d.transferId=id;d.startBefore=clock.instant().plusSeconds(60);d.expiresAt=view.expiresAt();repository.saveAndFlush(d);
        String url="/api/downloads/"+secret;return new Issued(url,url+"/status",d.expiresAt);
    }
    @Transactional
    public Ticket begin(String secret){
        DownloadSession d=repository.lockSecret(Tokens.hash(secret)).orElseThrow(DownloadTickets::missing);check(d);
        if(d.state==State.SENT)throw missing();
        // A retry of this same capability may replace its own stalled stream, never another recipient's lease.
        var lease=transfers.reserveForSession(d.transferId,d.state==State.SENDING?d.activeLease:null);
        d.started=true;d.state=State.SENDING;d.activeLease=lease.leaseId();repository.saveAndFlush(d);
        return new Ticket(d.id,lease,d.sentThrough);
    }
    @Transactional(readOnly=true)
    public TransferService.View preview(String secret){DownloadSession d=find(secret);check(d);if(d.state==State.SENT)throw missing();return transfers.authorizedView(d.transferId);}
    @Transactional(readOnly=true)
    public State status(String secret){DownloadSession d=find(secret);return !d.expiresAt.isAfter(clock.instant())||!d.started&&!d.startBefore.isAfter(clock.instant())?State.EXPIRED:d.state;}
    @Transactional
    public void failed(String secret){repository.lockSecret(Tokens.hash(secret)).ifPresent(d->{if(d.state!=State.SENT&&d.state!=State.SENDING){d.state=State.FAILED;repository.saveAndFlush(d);}});}
    @Transactional
    public boolean checkpoint(Ticket ticket,long start,long sentThrough){
        DownloadSession d=repository.lockId(ticket.sessionId).orElseThrow(DownloadTickets::missing);
        if(!Objects.equals(d.activeLease,ticket.lease.leaseId()))return false;
        if(start<=d.sentThrough)d.sentThrough=Math.max(d.sentThrough,sentThrough);
        repository.saveAndFlush(d);return true;
    }
    @Transactional
    public void finish(Ticket ticket,boolean responseCompleted,long start,long sentThrough){
        DownloadSession d=repository.lockId(ticket.sessionId).orElseThrow(DownloadTickets::missing);
        if(!Objects.equals(d.activeLease,ticket.lease.leaseId()))return;
        if(start<=d.sentThrough)d.sentThrough=Math.max(d.sentThrough,sentThrough);
        boolean complete=responseCompleted&&d.sentThrough>=ticket.lease.size();
        // The counter and session receipt commit atomically. Reusing a completed capability cannot spend again.
        transfers.finish(ticket.lease,complete);d.activeLease=null;d.state=complete?State.SENT:responseCompleted?State.PARTIAL:State.FAILED;repository.saveAndFlush(d);
    }
    @Transactional public void recover(){repository.recover(State.FAILED,State.SENDING);}
    @Transactional @Scheduled(fixedDelay=60000) public void prune(){repository.deleteExpired(clock.instant(),State.SENDING);}
    private DownloadSession find(String secret){return repository.findBySecretHash(Tokens.hash(secret)).orElseThrow(DownloadTickets::missing);}
    private void check(DownloadSession d){if(!d.expiresAt.isAfter(clock.instant())||!d.started&&!d.startBefore.isAfter(clock.instant()))throw missing();}
    private static ApiFailure missing(){return new ApiFailure(410,"DOWNLOAD_EXPIRED","Нажмите «Получить файл» ещё раз.");}
}
