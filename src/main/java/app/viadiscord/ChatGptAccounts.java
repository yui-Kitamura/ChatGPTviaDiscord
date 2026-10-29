package app.viadiscord;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;

final class ChatGptAccounts implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final class Account {
        public String clientId="", subject="", email="", idToken="", accessToken="", refreshToken="", model="", scope="";
        public long expiresAt;
        public Account() {}
        boolean canInvoke() { return Arrays.asList(scope.split(" ")).contains("chatgpt.tokens.use.direct"); }
        @Override public String toString() { return "ChatGPTAccount[redacted]"; }
    }
    public static final class Data {
        public int version=1;
        public String hostId="urn:uuid:"+UUID.randomUUID();
        public Map<String,Account> accounts=new TreeMap<>();
        public Map<String,String> active=new TreeMap<>();
        public Data() {}
    }
    private final Path file;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Data data;
    ChatGptAccounts(Path directory) throws IOException {
        Files.createDirectories(directory);
        file=directory.resolve("accounts.json");
        lockChannel=FileChannel.open(directory.resolve("accounts.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock=lockChannel.tryLock(); }
        catch(java.nio.channels.OverlappingFileLockException e) {lockChannel.close();throw new IOException("ChatGPT credential storage is already in use.");}
        if (lock == null) { lockChannel.close(); throw new IOException("ChatGPT credential storage is already in use."); }
        try {
            data=Files.exists(file) ? JSON.readValue(Files.readString(file),Data.class) : new Data();
            if(data.version!=1 || !data.hostId.matches("urn:uuid:[0-9a-f-]{36}") || data.accounts==null || data.active==null)
                throw new IOException("Invalid ChatGPT account storage.");
            if(!Files.exists(file)) save(); // Host ID exists before the first authorization.
        } catch(IOException|RuntimeException error) { close(); throw new IOException("ChatGPT account storage could not be loaded."); }
    }
    static String key(String user,String label) {
        if(!user.matches("[0-9]{17,20}") || !label.matches("[a-zA-Z0-9_-]{1,32}"))
            throw new IllegalArgumentException("account は英数字・_・-の1〜32文字で指定してください。");
        return user+":"+label;
    }
    synchronized String hostId(){return data.hostId;}
    synchronized Account get(String key) throws IOException {
        var account=data.accounts.get(key);
        return account==null ? null : JSON.readValue(JSON.writeValueAsString(account),Account.class);
    }
    synchronized String activeKey(String user) { return data.active.get(user); }
    synchronized Map<String,Account> list(String user) throws IOException {
        var result=new TreeMap<String,Account>();
        for(var key:data.accounts.keySet()) if(key.startsWith(user+":")) result.put(key.substring(user.length()+1),get(key));
        return result;
    }
    synchronized void put(String key,Account account,boolean activate) throws IOException {
        String old=JSON.writeValueAsString(data);
        try {
            data.accounts.put(key,account);
            if(activate) data.active.put(key.split(":")[0],key);
            save();
        } catch(IOException e) {data=JSON.readValue(old,Data.class);throw e;}
    }
    synchronized void select(String user,String key) throws IOException {
        if(!data.accounts.containsKey(key)) throw new ChatGptException("login_required",0);
        var account=get(key); put(key,account,true);
    }
    synchronized void clearTokens(String key) throws IOException {
        var a=get(key);if(a==null)return;
        a.accessToken="";a.refreshToken="";a.idToken="";a.expiresAt=0;put(key,a,false);
    }
    private void save() throws IOException {PrivateFiles.write(file,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data));}
    @Override public void close() throws IOException {if(lock!=null&&lock.isValid())lock.release();lockChannel.close();}
}
