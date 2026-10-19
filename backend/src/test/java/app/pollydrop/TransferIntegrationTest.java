package app.pollydrop;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TransferIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17.6-alpine");
    static final Path STORE;
    static { try { STORE=Files.createTempDirectory("pollydrop-test-"); } catch(IOException e){throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);
        p.add("app.master-key",()->Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32)));p.add("app.storage-dir",STORE::toString);
    }
    @Autowired TransferService service;
    @Autowired DeliveryService delivery;
    @Autowired TransferRepository repository;
    @Autowired CipherStorage storage;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    TransferService.Created text(String password,int limit)throws IOException {return service.create(new TransferService.Options("TEXT","Привет 👋 <script>alert(1)</script>",null,600,limit,password),null,null);}
    Transfer row(TransferService.Created c){return repository.findByReadHash(Tokens.hash(c.readToken())).orElseThrow();}
    void failure(Runnable action,int status,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiFailure.class,e->{assertThat(e.status).isEqualTo(status);assertThat(e.code).isEqualTo(code);});}

    @Test void realHttpTextLifecycleAndSeparateManagement()throws Exception {
        var options=new MockMultipartFile("options","options.json","application/json",json.writeValueAsBytes(new TransferService.Options("TEXT","Unicode: Привет 👋",null,600,1,null)));
        var result=http.perform(multipart("/api/transfers").file(options)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        var created=json.readValue(result.getResponse().getContentAsByteArray(),TransferService.Created.class);
        assertThat(created.readToken()).hasSize(43).isNotEqualTo(created.manageToken());
        http.perform(get("/api/transfers/"+created.readToken())).andExpect(status().isOk()).andExpect(jsonPath("downloads").value(0));
        http.perform(get("/api/manage/"+created.readToken())).andExpect(status().isNotFound());
        http.perform(post("/api/transfers/"+created.readToken()+"/download").contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(content().bytes("Unicode: Привет 👋".getBytes(StandardCharsets.UTF_8)));
        http.perform(post("/api/transfers/"+created.readToken()+"/download").contentType("application/json").content("{}")).andExpect(status().isGone()).andExpect(jsonPath("code").value("EXHAUSTED"));
        assertThat(service.manageView(created.manageToken()).downloads()).isEqualTo(1);
    }
    @Test void binaryUploadPreservesBytesAndSanitizesName()throws Exception {
        byte[] bytes=new byte[180000];new Random(4).nextBytes(bytes);
        var created=service.create(new TransferService.Options("FILE",null,null,600,3,null),bytes,"../проект\n.bin");
        assertThat(created.transfer().name()).isEqualTo("проект.bin");
        var lease=service.reserve(created.readToken(),null,"test");var output=new ByteArrayOutputStream();delivery.send(lease,output);
        assertThat(output.toByteArray()).isEqualTo(bytes);assertThat(service.manageView(created.manageToken()).remaining()).isEqualTo(2);
    }
    @Test void encryptedStorageAndArgonPasswordAndTokenHashes()throws Exception {
        var c=text("secret",1);var t=row(c);
        assertThat(t.readHash).isEqualTo(Tokens.hash(c.readToken())).doesNotContain(c.readToken());assertThat(t.passwordHash).startsWith("$argon2id$").doesNotContain("secret");
        byte[] disk=Files.readAllBytes(STORE.resolve(t.id+".enc"));assertThat(new String(disk,StandardCharsets.UTF_8)).doesNotContain("Привет");
        assertThat(service.publicView(c.readToken()).name()).isEqualTo("Защищённая передача");
        failure(()->service.reserve(c.readToken(),null,"password-client"),401,"PASSWORD_REQUIRED");
        for(int i=0;i<5;i++)failure(()->service.reserve(c.readToken(),"wrong","password-client"),401,"WRONG_PASSWORD");
        failure(()->service.reserve(c.readToken(),"secret","password-client"),429,"RATE_LIMITED");
        var lease=service.reserve(c.readToken(),"secret","another-client");service.finish(lease,false);
    }
    @Test void interruptionReleasesReservationWithoutConsumingLimit()throws Exception {
        var c=service.create(new TransferService.Options("FILE",null,null,600,1,null),new byte[200000],"test.bin");
        var lease=service.reserve(c.readToken(),null,"test");
        OutputStream failed=new OutputStream(){int written;public void write(int value)throws IOException{if(++written>70000)throw new IOException("simulated disconnect");}};
        assertThatThrownBy(()->delivery.send(lease,failed)).isInstanceOf(IOException.class);
        assertThat(service.publicView(c.readToken()).state()).isEqualTo("AVAILABLE");assertThat(service.publicView(c.readToken()).downloads()).isZero();
        var output=new ByteArrayOutputStream();delivery.send(service.reserve(c.readToken(),null,"test"),output);
        assertThat(output.size()).isEqualTo(200000);assertThat(service.publicView(c.readToken()).downloads()).isEqualTo(1);
    }
    @Test void concurrentReservationsHaveOneWinner()throws Exception {
        var c=text(null,1);ExecutorService pool=Executors.newFixedThreadPool(100);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<100;i++)results.add(pool.submit(()->{start.await();try{service.reserve(c.readToken(),null,"test");return true;}catch(ApiFailure e){assertThat(e.code).isEqualTo("IN_PROGRESS");return false;}}));
            start.countDown();int wins=0;for(var f:results)if(f.get(20,TimeUnit.SECONDS))wins++;
            assertThat(wins).isEqualTo(1);assertThat(service.manageView(c.manageToken()).downloads()).isZero();
            service.recover();assertThat(service.publicView(c.readToken()).state()).isEqualTo("AVAILABLE");
        } finally {pool.shutdownNow();}
    }
    @Test void revokeAndExpiryPreventReadAndCleanupErasesContent()throws Exception {
        var revoked=text(null,1);var id=row(revoked).id;
        http.perform(delete("/api/manage/"+revoked.manageToken())).andExpect(status().isNoContent());
        failure(()->service.reserve(revoked.readToken(),null,"test"),410,"REVOKED");service.cleanup();assertThat(Files.exists(STORE.resolve(id+".enc"))).isFalse();
        assertThat(service.manageView(revoked.manageToken()).state()).isEqualTo("REVOKED");
        var expired=text(null,1);var row=row(expired);row.expiresAt=Instant.now().minusSeconds(1);repository.saveAndFlush(row);
        failure(()->service.reserve(expired.readToken(),null,"test"),410,"EXPIRED");service.cleanup();assertThat(Files.exists(STORE.resolve(row.id+".enc"))).isFalse();
    }
    @Test void tamperEmitsNoPlaintextAndDoesNotConsume()throws Exception {
        var c=text(null,1);var t=row(c);var path=STORE.resolve(t.id+".enc");byte[] contents=Files.readAllBytes(path);contents[contents.length-1]^=1;Files.write(path,contents);
        var output=new ByteArrayOutputStream();assertThatThrownBy(()->delivery.send(service.reserve(c.readToken(),null,"test"),output)).isInstanceOf(IOException.class);
        assertThat(output.size()).isZero();assertThat(service.publicView(c.readToken()).downloads()).isZero();
    }
    @Test void oldReservationCannotCompleteOrSendAfterRecovery()throws Exception {
        var c=text(null,1);var old=service.reserve(c.readToken(),null,"test");service.recover();var fresh=service.reserve(c.readToken(),null,"test");
        service.finish(old,true);assertThat(service.publicView(c.readToken()).downloads()).isZero();
        var out=new ByteArrayOutputStream();assertThatThrownBy(()->delivery.send(old,out)).isInstanceOf(IOException.class);assertThat(out.size()).isZero();
        assertThat(service.publicView(c.readToken()).state()).isEqualTo("IN_PROGRESS");delivery.send(fresh,out);assertThat(service.publicView(c.readToken()).downloads()).isEqualTo(1);
    }
    @Test void validationAndQuota()throws Exception {
        assertThatThrownBy(()->service.create(new TransferService.Options("TEXT","x",null,1,1,null),null,null)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.status).isEqualTo(400));
        assertThatThrownBy(()->service.create(new TransferService.Options("TEXT","x".repeat(65537),null,600,1,null),null,null)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.status).isEqualTo(413));
        failure(()->service.publicView("bad-token"),404,"NOT_FOUND");
        Path tiny=Files.createTempDirectory("pollydrop-quota-");var limited=new CipherStorage(tiny.toString(),Base64.getEncoder().encodeToString(new byte[32]),50);
        assertThatThrownBy(()->limited.write(UUID.randomUUID(),new byte[30])).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.status).isEqualTo(507));
        try(var entries=Files.list(tiny)){assertThat(entries.count()).isZero();}Files.delete(tiny);
    }
    @Autowired DownloadTickets tickets;
    @Autowired DownloadSessionRepository sessions;
    @Autowired UploadService uploads;
    @Autowired UploadRepository uploadRows;
    @Autowired UploadJanitor uploadJanitor;
    @Autowired ExpiryScheduler expiry;
    @Test void nativeTicketChecksPasswordAndHeadDoesNotSpendIt()throws Exception {
        var c=service.create(new TransferService.Options("FILE",null,null,600,1,"secret"),new byte[]{0,1,2},"проверка.bin");
        var id=row(c).id;
        http.perform(post("/api/transfers/"+c.readToken()+"/download-ticket").contentType("application/json").content("{\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
        var issued=tickets.issue(c.readToken(),"secret","ticket-client");
        assertThat(service.publicView(c.readToken()).downloads()).isZero();
        http.perform(head(issued.url())).andExpect(status().isOk());
        http.perform(get(issued.statusUrl())).andExpect(status().isOk()).andExpect(jsonPath("state").value("READY"));
        http.perform(get(issued.url())).andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment"))).andExpect(content().bytes(new byte[]{0,1,2}));
        http.perform(get(issued.url())).andExpect(status().isGone());
        http.perform(get(issued.statusUrl())).andExpect(jsonPath("state").value("SENT"));
        assertThat(Files.exists(STORE.resolve(id+".enc"))).isFalse();
        assertThat(service.manageView(c.manageToken()).downloads()).isEqualTo(1);
    }
    @Test void ticketCannotBypassExpiryRevokeOrLastDownload()throws Exception {
        var c=text(null,1);var issued=tickets.issue(c.readToken(),null,"test");service.revoke(c.manageToken());
        http.perform(get(issued.url())).andExpect(status().isGone()).andExpect(jsonPath("code").value("REVOKED"));
        http.perform(get(issued.statusUrl())).andExpect(jsonPath("state").value("FAILED"));
        var once=text(null,1);var first=tickets.issue(once.readToken(),null,"test");var second=tickets.issue(once.readToken(),null,"test");
        http.perform(get(first.url())).andExpect(status().isOk());
        http.perform(get(second.url())).andExpect(status().isGone()).andExpect(jsonPath("code").value("EXHAUSTED"));
    }
    @Test void ticketExpiresWithoutSpendingDownload()throws Exception {
        var c=text(null,1);java.time.Clock clock=org.mockito.Mockito.mock(java.time.Clock.class);
        Instant now=Instant.now();org.mockito.Mockito.when(clock.instant()).thenReturn(now);
        var local=new DownloadTickets(service,sessions,clock);var issued=local.issue(c.readToken(),null,"test");
        org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(61));
        String token=issued.url().substring(issued.url().lastIndexOf('/')+1);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->failure(()->local.begin(token),410,"DOWNLOAD_EXPIRED"));
        assertThat(service.publicView(c.readToken()).downloads()).isZero();
    }
    @Test void lastDownloadDeletesImmediatelyButEarlierDownloadsKeepContent()throws Exception {
        var c=service.create(new TransferService.Options("FILE",null,null,600,3,null),new byte[]{3,4,5},"three.bin");
        var path=STORE.resolve(row(c).id+".enc");
        for(int i=1;i<=3;i++){
            delivery.send(service.reserve(c.readToken(),null,"test"),OutputStream.nullOutputStream());
            assertThat(Files.exists(path)).isEqualTo(i<3);
        }
    }
    @Test void deadlineDeletesEvenWithoutAnotherRequestAndInterruptsActiveLease()throws Exception {
        var c=text(null,3);var t=row(c);Instant deadline=Instant.now().plusMillis(300);t.expiresAt=deadline;repository.saveAndFlush(t);
        var lease=service.reserve(c.readToken(),null,"test");expiry.schedule(t.id,deadline);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(Files.exists(STORE.resolve(t.id+".enc")) && System.nanoTime()<until)Thread.sleep(25);
        assertThat(Files.exists(STORE.resolve(t.id+".enc"))).isFalse();
        assertThat(service.publicView(c.readToken()).downloads()).isZero();
        assertThatThrownBy(()->delivery.send(lease,OutputStream.nullOutputStream())).isInstanceOf(IOException.class);
    }
    @Test void sevenDaysRejectedAndTwoDaysAllowed()throws Exception {
        assertThatThrownBy(()->service.create(new TransferService.Options("TEXT","x",null,604800,1,null),null,null)).isInstanceOfSatisfying(ApiFailure.class,e->assertThat(e.status).isEqualTo(400));
        var c=service.create(new TransferService.Options("TEXT","x",null,172800,1,null),null,null);
        assertThat(java.time.Duration.between(c.transfer().createdAt(),c.transfer().expiresAt()).toSeconds()).isEqualTo(172800);
    }

    String digest(byte[] data)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));}
    UploadService.Start uploadOptions(byte[] bytes,String password)throws Exception{return new UploadService.Start("resume.bin",bytes.length,digest(bytes),600,1,password);}
    byte[] sample(int length){byte[] bytes=new byte[length];new Random(19).nextBytes(bytes);return bytes;}
    @Test void chunkUploadSurvivesLostResponsesAndFinalizationIsIdempotent()throws Exception {
        byte[] bytes=sample(UploadJanitor.CHUNK_SIZE+12345);String secret=Tokens.newToken();
        var initial=uploads.start(secret,uploadOptions(bytes,"upload-secret"));
        assertThat(uploads.start(secret,uploadOptions(bytes,"upload-secret")).offset()).isZero();
        byte[] first=Arrays.copyOfRange(bytes,0,UploadJanitor.CHUNK_SIZE);
        uploads.append(secret,0,first);uploads.append(secret,0,first);
        assertThat(uploads.status(secret).offset()).isEqualTo(UploadJanitor.CHUNK_SIZE);
        var stored=uploadRows.findBySecretHash(Tokens.hash(secret)).orElseThrow();
        assertThat(stored.passwordHash).startsWith("$argon2id$");
        assertThat(Files.readAllBytes(STORE.resolve(UploadJanitor.part(stored.id,0)+".enc"))).isNotEqualTo(first);
        var known=new HashSet<UUID>();for(var t:repository.findAll())known.add(t.id);known.addAll(uploadJanitor.retainedIds());storage.removeOrphans(known);
        uploads.append(secret,UploadJanitor.CHUNK_SIZE,Arrays.copyOfRange(bytes,UploadJanitor.CHUNK_SIZE,bytes.length));
        var created=uploads.complete(secret);var again=uploads.complete(secret);
        assertThat(created.readToken()).isEqualTo(again.readToken());assertThat(created.manageToken()).isEqualTo(again.manageToken());
        assertThat(created.transfer().expiresAt()).isEqualTo(again.transfer().expiresAt());
        assertThat(java.time.Duration.between(initial.expiresAt(),created.transfer().expiresAt()).abs().toMillis()).isZero();
        assertThat(Files.exists(STORE.resolve(UploadJanitor.part(stored.id,0)+".enc"))).isFalse();
        var out=new ByteArrayOutputStream();delivery.send(service.reserve(created.readToken(),"upload-secret","upload-test"),out);assertThat(out.toByteArray()).isEqualTo(bytes);
    }
    @Test void uploadRejectsChangedChunksWrongChecksumAndMissingParts()throws Exception {
        byte[] bytes=sample(UploadJanitor.CHUNK_SIZE+10);String secret=Tokens.newToken();uploads.start(secret,uploadOptions(bytes,null));
        failure(()->{try{uploads.complete(secret);}catch(IOException e){throw new UncheckedIOException(e);}},409,"UPLOAD_INCOMPLETE");
        uploads.append(secret,0,Arrays.copyOfRange(bytes,0,UploadJanitor.CHUNK_SIZE));
        byte[] changed=Arrays.copyOfRange(bytes,0,UploadJanitor.CHUNK_SIZE);changed[0]^=1;
        failure(()->{try{uploads.append(secret,0,changed);}catch(IOException e){throw new UncheckedIOException(e);}},409,"UPLOAD_MISMATCH");
        byte[] wrongTail=new byte[10];uploads.append(secret,UploadJanitor.CHUNK_SIZE,wrongTail);
        failure(()->{try{uploads.complete(secret);}catch(IOException e){throw new UncheckedIOException(e);}},422,"CHECKSUM_MISMATCH");
        assertThat(uploads.status(secret).completed()).isFalse();uploads.cancel(secret);
        assertThat(uploadRows.findBySecretHash(Tokens.hash(secret))).isEmpty();
    }
    @Test void unfinishedUploadExpiresAndErasesItsEncryptedParts()throws Exception {
        byte[] bytes=sample(100);String secret=Tokens.newToken();uploads.start(secret,uploadOptions(bytes,null));uploads.append(secret,0,bytes);
        var u=uploadRows.findBySecretHash(Tokens.hash(secret)).orElseThrow();var path=STORE.resolve(UploadJanitor.part(u.id,0)+".enc");assertThat(Files.exists(path)).isTrue();
        u.expiresAt=Instant.now().minusSeconds(1);uploadRows.saveAndFlush(u);uploadJanitor.cleanup();
        assertThat(Files.exists(path)).isFalse();assertThat(uploadRows.findById(u.id)).isEmpty();
    }
    @Test void uploadHttpProtocolAndSecretSeparation()throws Exception {
        byte[] bytes=sample(10000);String secret=Tokens.newToken();
        http.perform(put("/api/uploads/"+secret).contentType("application/json").content(json.writeValueAsBytes(uploadOptions(bytes,null)))).andExpect(status().isOk()).andExpect(jsonPath("offset").value(0));
        http.perform(patch("/api/uploads/"+secret).header("Upload-Offset",0).contentType("application/octet-stream").content(bytes)).andExpect(status().isOk()).andExpect(jsonPath("offset").value(bytes.length));
        var response=http.perform(post("/api/uploads/"+secret+"/complete")).andExpect(status().isOk()).andReturn().getResponse();
        var created=json.readValue(response.getContentAsByteArray(),TransferService.Created.class);
        assertThat(created.readToken()).isNotEqualTo(secret).isNotEqualTo(created.manageToken());
        http.perform(get("/api/manage/"+secret)).andExpect(status().isNotFound());
        http.perform(get("/api/uploads/"+created.readToken())).andExpect(status().isNotFound());
    }
    @Test void rangesJoinIntoOneDownloadAndLastRangeDeletesContent()throws Exception {
        byte[] bytes=sample(200000);var c=service.create(new TransferService.Options("FILE",null,null,600,1,null),bytes,"range.bin");var id=row(c).id;var ticket=tickets.issue(c.readToken(),null,"ranges");
        var first=http.perform(get(ticket.url()).header("Range","bytes=0-65535")).andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 0-65535/200000")).andExpect(content().bytes(Arrays.copyOfRange(bytes,0,65536))).andReturn();
        String etag=first.getResponse().getHeader("ETag");assertThat(service.publicView(c.readToken()).downloads()).isZero();assertThat(Files.exists(STORE.resolve(id+".enc"))).isTrue();
        http.perform(head(ticket.url())).andExpect(status().isOk());
        http.perform(get(ticket.url()).header("Range","bytes=200000-")).andExpect(status().isRequestedRangeNotSatisfiable()).andExpect(header().string("Content-Range","bytes */200000"));
        http.perform(get(ticket.url()).header("Range","bytes=60000-").header("If-Range",etag)).andExpect(status().isPartialContent()).andExpect(content().bytes(Arrays.copyOfRange(bytes,60000,bytes.length)));
        assertThat(service.manageView(c.manageToken()).downloads()).isEqualTo(1);assertThat(Files.exists(STORE.resolve(id+".enc"))).isFalse();
        http.perform(get(ticket.url())).andExpect(status().isGone());http.perform(get(ticket.statusUrl())).andExpect(jsonPath("state").value("SENT"));
    }
    @Test void realStreamFailureCanContinueWithSameCapability()throws Exception {
        byte[] bytes=sample(300000);var c=service.create(new TransferService.Options("FILE",null,null,600,1,null),bytes,"disconnect.bin");var issued=tickets.issue(c.readToken(),null,"failure");String secret=issued.url().substring(issued.url().lastIndexOf('/')+1);var ticket=tickets.begin(secret);long[] sent={0};
        OutputStream broken=new OutputStream(){int written;public void write(int value)throws IOException{if(++written>70000)throw new IOException("simulated network loss");}};
        try{delivery.sendRange(ticket.lease(),broken,0,bytes.length-1,offset->{sent[0]=offset;tickets.checkpoint(ticket,0,offset);});fail("Expected disconnect");}catch(IOException expected){tickets.finish(ticket,false,0,sent[0]);}
        assertThat(sent[0]).isEqualTo(65536);assertThat(service.publicView(c.readToken()).downloads()).isZero();
        service.recover();tickets.recover();
        http.perform(get(issued.url()).header("Range","bytes="+sent[0]+"-")).andExpect(status().isPartialContent()).andExpect(content().bytes(Arrays.copyOfRange(bytes,(int)sent[0],bytes.length)));
        assertThat(service.manageView(c.manageToken()).downloads()).isEqualTo(1);
    }
    @Test void unverifiedOffsetAndChangedValidatorRestartSafely()throws Exception {
        byte[] bytes=sample(1000);var c=service.create(new TransferService.Options("FILE",null,null,600,3,null),bytes,"restart.bin");var issued=tickets.issue(c.readToken(),null,"restart");
        http.perform(get(issued.url()).header("Range","bytes=500-")).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        var second=tickets.issue(c.readToken(),null,"restart");
        http.perform(get(second.url()).header("Range","bytes=0-9").header("If-Range","\"stale\"")).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        assertThat(service.manageView(c.manageToken()).downloads()).isEqualTo(2);
    }
    @Test void oneSessionCanReplaceOnlyItsOwnInterruptedStream()throws Exception {
        var c=service.create(new TransferService.Options("FILE",null,null,600,3,null),sample(1000),"takeover.bin");var a=tickets.issue(c.readToken(),null,"a");var b=tickets.issue(c.readToken(),null,"b");String keyA=a.url().substring(a.url().lastIndexOf('/')+1),keyB=b.url().substring(b.url().lastIndexOf('/')+1);
        var old=tickets.begin(keyA);failure(()->tickets.begin(keyB),409,"IN_PROGRESS");var replacement=tickets.begin(keyA);
        assertThat(tickets.checkpoint(old,0,1000)).isFalse();tickets.finish(old,true,0,1000);assertThat(service.manageView(c.manageToken()).downloads()).isZero();
        delivery.sendRange(replacement.lease(),OutputStream.nullOutputStream(),0,999,offset->tickets.checkpoint(replacement,0,offset));tickets.finish(replacement,true,0,1000);
        assertThat(service.manageView(c.manageToken()).downloads()).isEqualTo(1);
    }

}
