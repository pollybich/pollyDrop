package app.pollydrop;

import org.springframework.stereotype.Service;
import java.io.*;
import java.time.Instant;

@Service
public class DeliveryService {
    private final CipherStorage storage;private final TransferService transfers;
    public DeliveryService(CipherStorage storage,TransferService transfers){this.storage=storage;this.transfers=transfers;}
    @FunctionalInterface public interface Progress {void sent(long offset)throws IOException;}
    public void sendRange(TransferService.Lease lease,OutputStream out,long start,long end,Progress progress)throws IOException {
        byte[] data=storage.read(lease.id());
        for(long offset=start;offset<=end;){
            if(!lease.expiresAt().isAfter(Instant.now())||transfers.interrupted(lease.id(),lease.leaseId()))throw new IOException("Transfer access ended");
            int count=(int)Math.min(65536,end-offset+1);out.write(data,(int)offset,count);out.flush();offset+=count;progress.sent(offset);
        }
    }
    public void send(TransferService.Lease lease,OutputStream out)throws IOException {
        boolean completed=false;
        try {
            // Validate the GCM authentication tag before emitting any plaintext.
            byte[] data=storage.read(lease.id());
            for(int offset=0;offset<data.length;offset+=65536) {
                if(!lease.expiresAt().isAfter(Instant.now()) || transfers.interrupted(lease.id(),lease.leaseId()))throw new IOException("Transfer access ended");
                out.write(data,offset,Math.min(65536,data.length-offset));out.flush();
            }
            completed=true;
        } finally { transfers.finish(lease,completed); }
    }
}
