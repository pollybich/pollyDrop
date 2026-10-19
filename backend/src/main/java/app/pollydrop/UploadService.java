package app.pollydrop;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.beans.factory.annotation.Value;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.security.*;

@Service
public class UploadService {
    public record Start(String name,long bytes,String fingerprint,int ttlSeconds,int maxDownloads,String password){}
    public record View(String name,long bytes,String fingerprint,long offset,int chunkSize,Instant expiresAt,int maxDownloads,boolean passwordProtected,boolean completed){}
    private final UploadRepository repository;private final CipherStorage storage;private final PasswordGate passwords;private final TransferService transfers;private final UploadJanitor janitor;private final long maxBytes;
    public UploadService(UploadRepository repository,CipherStorage storage,PasswordGate passwords,TransferService transfers,UploadJanitor janitor,@Value("${app.max-bytes}") long maxBytes){this.repository=repository;this.storage=storage;this.passwords=passwords;this.transfers=transfers;this.janitor=janitor;this.maxBytes=maxBytes;}
    @Transactional
    public View start(String secret,Start options){
        String hash=Tokens.hash(secret);
        if(options==null||options.name==null||options.name.length()>240||options.fingerprint==null||!options.fingerprint.matches("[a-f0-9]{64}")||options.ttlSeconds<600||options.ttlSeconds>172800||options.maxDownloads<1||options.maxDownloads>10||options.password!=null&&options.password.length()>128)throw new ApiFailure(400,"INVALID_OPTIONS","Проверьте параметры загрузки.");
        if(options.bytes<1||options.bytes>Math.min(maxBytes,26214400))throw new ApiFailure(413,"TOO_LARGE","Файл должен быть не больше 25 МБ.");
        var found=repository.lockSecret(hash);
        if(found.isPresent()){
            Upload u=found.get();available(u);
            if(u.byteSize!=options.bytes||!u.fingerprint.equals(options.fingerprint))throw new ApiFailure(409,"UPLOAD_MISMATCH","Выберите исходный файл или начните новую загрузку.");
            return view(u);
        }
        if(repository.countByExpiresAtAfterAndTransferIdIsNull(Instant.now())>=1024)throw new ApiFailure(429,"SERVER_BUSY","Сервис занят. Попробуйте через минуту.");
        Upload u=new Upload();u.id=UUID.randomUUID();u.secretHash=hash;u.name=options.name;u.byteSize=options.bytes;u.fingerprint=options.fingerprint;u.createdAt=Instant.now();u.expiresAt=u.createdAt.plusSeconds(options.ttlSeconds);u.maxDownloads=options.maxDownloads;
        if(options.password!=null&&!options.password.isEmpty())u.passwordHash=passwords.encode(options.password);
        repository.saveAndFlush(u);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){janitor.schedule(u.id,u.expiresAt);}});
        return view(u);
    }
    @Transactional(readOnly=true) public View status(String secret){Upload u=repository.findBySecretHash(Tokens.hash(secret)).orElseThrow(UploadService::missing);available(u);return view(u);}
    @Transactional
    public View append(String secret,long offset,byte[] bytes)throws IOException {
        Upload u=repository.lockSecret(Tokens.hash(secret)).orElseThrow(UploadService::missing);available(u);
        if(u.transferId!=null)return view(u);
        if(offset<0||offset%UploadJanitor.CHUNK_SIZE!=0||offset>=u.byteSize||bytes==null||bytes.length!=Math.min(UploadJanitor.CHUNK_SIZE,u.byteSize-offset))throw new ApiFailure(400,"INVALID_CHUNK","Не удалось проверить часть файла.");
        if(offset<u.uploadedBytes){
            if(!MessageDigest.isEqual(storage.read(UploadJanitor.part(u.id,offset)),bytes))throw new ApiFailure(409,"UPLOAD_MISMATCH","Выберите исходный файл или начните новую загрузку.");
            return view(u);
        }
        if(offset!=u.uploadedBytes)throw new ApiFailure(409,"UPLOAD_OFFSET","Прогресс загрузки изменился. Повторите попытку.");
        storage.write(UploadJanitor.part(u.id,offset),bytes);u.uploadedBytes+=bytes.length;repository.saveAndFlush(u);return view(u);
    }
    @Transactional
    public TransferService.Created complete(String secret)throws IOException {
        Upload u=repository.lockSecret(Tokens.hash(secret)).orElseThrow(UploadService::missing);available(u);
        String read=Tokens.derive(secret,"read"),manage=Tokens.derive(secret,"manage");
        if(u.transferId!=null)return new TransferService.Created(read,manage,transfers.manageView(manage));
        if(u.uploadedBytes!=u.byteSize)throw new ApiFailure(409,"UPLOAD_INCOMPLETE","Дождитесь окончания загрузки.");
        ByteArrayOutputStream data=new ByteArrayOutputStream((int)u.byteSize);
        for(long offset=0;offset<u.byteSize;offset+=UploadJanitor.CHUNK_SIZE)data.write(storage.read(UploadJanitor.part(u.id,offset)));
        byte[] contents=data.toByteArray();
        try {if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents)).equals(u.fingerprint))throw new ApiFailure(422,"CHECKSUM_MISMATCH","Проверка файла не прошла. Начните новую загрузку.");}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        if(!u.expiresAt.isAfter(Instant.now()))throw new ApiFailure(410,"UPLOAD_EXPIRED","Срок загрузки истёк. Начните новую передачу.");
        var created=transfers.createUploaded(u,contents,read,manage);u.transferId=transfers.idForOwner(manage);repository.saveAndFlush(u);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){janitor.discard(u.id);}});
        return created;
    }
    @Transactional
    public void cancel(String secret){Upload u=repository.lockSecret(Tokens.hash(secret)).orElseThrow(UploadService::missing);u.expiresAt=Instant.now();repository.saveAndFlush(u);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){janitor.discard(u.id);}});}
    private static View view(Upload u){return new View(u.name,u.byteSize,u.fingerprint,u.uploadedBytes,UploadJanitor.CHUNK_SIZE,u.expiresAt,u.maxDownloads,u.passwordHash!=null,u.transferId!=null);}
    private static void available(Upload u){if(!u.expiresAt.isAfter(Instant.now()))throw new ApiFailure(410,"UPLOAD_EXPIRED","Срок загрузки истёк. Начните новую передачу.");}
    private static ApiFailure missing(){return new ApiFailure(404,"UPLOAD_NOT_FOUND","Загрузка не найдена. Начните новую передачу.");}
}
