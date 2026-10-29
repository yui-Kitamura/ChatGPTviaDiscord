package app.viadiscord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.dv8tion.jda.api.entities.Member;

final class AiAccess {
    private final Path file;
    private volatile java.util.Set<String> roles;
    AiAccess(Path directory,String initialRole) throws IOException {
        file=directory.resolve("ai-role.txt");
        String value=Files.exists(file)?Files.readString(file).trim():initialRole;
        var loaded=new java.util.TreeSet<String>();
        if(!value.isEmpty())for(String role:value.split("\\s+")) {
            if(!role.matches("[0-9]{17,20}"))throw new IOException("Invalid AI role setting.");
            loaded.add(role);
        }
        roles=java.util.Set.copyOf(loaded);
    }
    java.util.Set<String> roleIds(){return roles;}
    boolean permits(Member member){return member!=null&&!member.getUser().isBot()&&member.getRoles().stream().anyMatch(r->roles.contains(r.getId()));}
    synchronized void update(String roleId,boolean accept) throws IOException {
        if(!roleId.matches("[0-9]{17,20}"))throw new IllegalArgumentException("Invalid role.");
        var next=new java.util.TreeSet<>(roles);
        if(accept)next.add(roleId);else next.remove(roleId);
        PrivateFiles.write(file,String.join("\n",next)+"\n");roles=java.util.Set.copyOf(next);
    }
}
