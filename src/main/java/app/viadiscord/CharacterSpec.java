package app.viadiscord;

import java.text.Normalizer;

/** A character proposed by the response generator, never a real Discord member. */
record CharacterSpec(String name, String persona) {
    CharacterSpec {
        if (name == null || persona == null) throw new IllegalArgumentException("Character fields are required.");
        name = Normalizer.normalize(name.strip(), Normalizer.Form.NFKC);
        persona = persona.strip();
        if (name.isEmpty() || name.length() > 60 || name.chars().anyMatch(c -> Character.isISOControl(c)
                || c == '@' || c == '<' || c == '>')) {
            throw new IllegalArgumentException("Invalid character name.");
        }
        if (persona.isEmpty() || persona.length() > 1200) throw new IllegalArgumentException("Invalid character persona.");
    }

    String roleName() { return "GvD: " + name; }
}
