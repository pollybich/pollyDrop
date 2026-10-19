package app.pollydrop;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransferController {
    private final TransferService service;private final RuntimeGuard guard;private final DeliveryService delivery;private final DownloadTickets tickets;private final UploadService uploads;
    public TransferController(TransferService service,RuntimeGuard guard,DeliveryService delivery,DownloadTickets tickets,UploadService uploads){this.service=service;this.guard=guard;this.delivery=delivery;this.tickets=tickets;this.uploads=uploads;}
    @GetMapping("/health") public Map<String,String> health(){if(!guard.ready())throw new ApiFailure(503,"NOT_READY","Сервис запускается.");return Map.of("status","ok","version","0.1.0-beta.4");}
    @PostMapping(value="/transfers",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TransferService.Created create(@RequestPart("options") TransferService.Options options,@RequestPart(value="file",required=false)MultipartFile file)throws IOException {
        requireReady();return service.create(options,file==null?null:file.getBytes(),file==null?null:file.getOriginalFilename());
    }
    @GetMapping("/transfers/{token}")public TransferService.View metadata(@PathVariable String token){requireReady();return service.publicView(token);}
    @GetMapping("/manage/{token}")public TransferService.View manage(@PathVariable String token){requireReady();return service.manageView(token);}
    @DeleteMapping("/manage/{token}")@ResponseStatus(HttpStatus.NO_CONTENT)public void revoke(@PathVariable String token){requireReady();service.revoke(token);}
    public record Access(String password) {}
    @PostMapping("/transfers/{token}/download-ticket")
    public DownloadTickets.Issued ticket(@PathVariable String token,@RequestBody(required=false)Access access,HttpServletRequest request){
        requireReady();return tickets.issue(token,access==null?null:access.password,request.getRemoteAddr());
    }
    @GetMapping("/downloads/{secret}/status")
    public Map<String,String> ticketStatus(@PathVariable String secret){requireReady();return Map.of("state",tickets.status(secret).name());}
    @PutMapping(value="/uploads/{secret}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public UploadService.View startUpload(@PathVariable String secret,@RequestBody UploadService.Start start){requireReady();return uploads.start(secret,start);}
    @GetMapping("/uploads/{secret}") public UploadService.View uploadStatus(@PathVariable String secret){requireReady();return uploads.status(secret);}
    @PatchMapping(value="/uploads/{secret}",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public UploadService.View uploadChunk(@PathVariable String secret,@RequestHeader("Upload-Offset") long offset,@RequestBody byte[] bytes)throws IOException{requireReady();return uploads.append(secret,offset,bytes);}
    @PostMapping("/uploads/{secret}/complete") public TransferService.Created completeUpload(@PathVariable String secret)throws IOException{requireReady();return uploads.complete(secret);}
    @DeleteMapping("/uploads/{secret}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelUpload(@PathVariable String secret){requireReady();uploads.cancel(secret);}
    @GetMapping("/downloads/{secret}")
    public void nativeDownload(@PathVariable String secret,HttpServletRequest request,HttpServletResponse response)throws IOException {
        requireReady();
        if("HEAD".equals(request.getMethod())){
            var view=tickets.preview(secret);response.setContentType("application/octet-stream");response.setContentLengthLong(view.bytes());response.setHeader("Accept-Ranges","bytes");response.setHeader("ETag","\"pd-"+Tokens.hash(secret)+"\"");response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(view.name(),StandardCharsets.UTF_8).build().toString());return;
        }
        DownloadTickets.Ticket ticket;try{ticket=tickets.begin(secret);}catch(ApiFailure e){tickets.failed(secret);throw e;}var lease=ticket.lease();boolean completed=false;
        String etag="\"pd-"+Tokens.hash(secret)+"\"";long[] sent={0};long[] from={0};
        try {
            long start=0,end=lease.size()-1;String range=request.getHeader("Range"),ifRange=request.getHeader("If-Range");
            if(range!=null&&(ifRange==null||ifRange.equals(etag))){
                try {
                    var ranges=HttpRange.parseRanges(range);if(ranges.size()!=1)throw new IllegalArgumentException();
                    start=ranges.getFirst().getRangeStart(lease.size());end=ranges.getFirst().getRangeEnd(lease.size());
                    if(start<0||start>=lease.size()||end<start)throw new IllegalArgumentException();
                }catch(IllegalArgumentException e){response.setStatus(416);response.setHeader("Content-Range","bytes */"+lease.size());return;}
                // If a saved browser offset is ahead of durable progress, restart safely with HTTP 200.
                if(start>ticket.sentThrough()){start=0;end=lease.size()-1;}
                else {response.setStatus(206);response.setHeader("Content-Range","bytes "+start+"-"+end+"/"+lease.size());}
            }
            from[0]=start;sent[0]=start;
            response.setContentType(lease.mediaType());response.setContentLengthLong(end-start+1);response.setHeader("Accept-Ranges","bytes");response.setHeader("ETag",etag);
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(lease.name(),StandardCharsets.UTF_8).build().toString());
            long rangeStart=start;
            delivery.sendRange(lease,response.getOutputStream(),start,end,offset->{sent[0]=offset;if(!tickets.checkpoint(ticket,rangeStart,offset))throw new IOException("Download stream superseded");});completed=true;
        }catch(IOException e){if(!response.isCommitted()){resetFailure(response);throw e;}}
        finally {tickets.finish(ticket,completed,from[0],sent[0]);}
    }
    @PostMapping("/transfers/{token}/download")
    public void download(@PathVariable String token,@RequestBody(required=false)Access access,HttpServletRequest request,HttpServletResponse response)throws IOException {
        requireReady();
        try {send(service.reserve(token,access==null?null:access.password,request.getRemoteAddr()),response);}
        catch(IOException e){if(!response.isCommitted()){resetFailure(response);throw e;}}
    }
    private void send(TransferService.Lease lease,HttpServletResponse response)throws IOException {
        try {
            response.setContentType(lease.mediaType());response.setContentLengthLong(lease.size());
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(lease.name(),StandardCharsets.UTF_8).build().toString());
            delivery.send(lease,response.getOutputStream());
        }catch(IOException e){service.finish(lease,false);throw e;}
    }
    private void resetFailure(HttpServletResponse response){
        response.reset();response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("X-Frame-Options","DENY");
    }
    private void requireReady(){if(!guard.ready())throw new ApiFailure(503,"NOT_READY","Сервис запускается. Попробуйте через несколько секунд.");}
}
