package pro.eng.yui.app.viadiscord;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import net.dv8tion.jda.api.entities.Guild;

/** Creates or reuses role labels after the response's character proposals have been validated. */
final class CharacterRoles {
    interface RoleGateway {
        boolean exists(String roleId);
        String create(String name);
    }

    private final ResponseSettings settings;
    private final RoleGateway roles;

    CharacterRoles(ResponseSettings settings, RoleGateway roles) {
        this.settings = settings;
        this.roles = roles;
    }

    static RoleGateway discord(Guild guild) {
        return new RoleGateway() {
            public boolean exists(String id) { return guild.getRoleById(id) != null; }
            public String create(String name) {
                // Context labels only: no permissions, user assignment, or webhook impersonation.
                return guild.createRole().setName(name).setPermissions(0L).setMentionable(true)
                        .setHoisted(false).reason("GvD generated AI character").complete().getId();
            }
        };
    }

    synchronized List<ResponseSettings.CharacterRole> ensureCharacters(List<CharacterSpec> proposals) throws IOException {
        return ensureCharacters(proposals,()->true);
    }

    synchronized List<ResponseSettings.CharacterRole> ensureCharacters(List<CharacterSpec> proposals, java.util.function.BooleanSupplier permitted) throws IOException {
        var unique = new LinkedHashMap<String, CharacterSpec>();
        for (var character : proposals) {
            var previous = unique.putIfAbsent(character.name(), character);
            if (previous != null && !previous.equals(character)) {
                throw new IllegalArgumentException("Conflicting character definitions in one response.");
            }
        }
        if (unique.size() > 8) throw new IllegalArgumentException("At most 8 characters per response.");
        var known = new LinkedHashMap<String, ResponseSettings.CharacterRole>();
        for (var character : settings.characters()) known.put(character.character().name(), character);
        long additions = unique.keySet().stream().filter(name -> !known.containsKey(name)).count();
        if (known.size() + additions > 64) throw new IllegalArgumentException("Character limit reached (64).");
        var result = new java.util.ArrayList<ResponseSettings.CharacterRole>();
        for (var proposal : unique.values()) {
            if(!permitted.getAsBoolean())throw new IOException("Character operation is no longer permitted.");
            var existing = known.get(proposal.name());
            // Preserve established characterization when a name reappears.
            var character = existing == null ? proposal : existing.character();
            String id = existing != null && roles.exists(existing.roleId())
                    ? existing.roleId() : roles.create(character.roleName());
            if (existing == null || !existing.roleId().equals(id)) settings.putCharacter(character, id);
            result.add(new ResponseSettings.CharacterRole(character, id));
        }
        return List.copyOf(result);
    }
}
