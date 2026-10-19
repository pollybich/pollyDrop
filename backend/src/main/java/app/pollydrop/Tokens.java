package app.pollydrop;

import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

final class Tokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    static String newToken() { byte[] b=new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String derive(String secret,String purpose) {
        hash(secret);
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(("pollydrop-upload:"+purpose+":"+secret).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static String hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new ApiFailure(404,"NOT_FOUND","Передача не найдена.");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
