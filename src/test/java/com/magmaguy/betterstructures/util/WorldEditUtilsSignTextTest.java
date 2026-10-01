package com.magmaguy.betterstructures.util;

import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.StringTag;
import com.sk89q.worldedit.world.block.BaseBlock;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinStringTag;
import org.enginehub.linbus.tree.LinTagType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldEditUtilsSignTextTest {

    @Test
    void readsLinesTypedInGame() {
        BaseBlock sign = sign(strings("[custom]", "Zombie_Plus", "", ""));
        assertEquals("[custom]", WorldEditUtils.getLine(sign, 1));
        assertEquals("Zombie_Plus", WorldEditUtils.getLine(sign, 2));
        assertEquals("", WorldEditUtils.getLine(sign, 3));
    }

    @Test
    void readsComponentLinesWrittenByPlugins() {
        // Spigot 26.2 SignSide#setLine output as WorldEdit copies it: components, blanks wrapped as {"": ""}.
        LinListTag<LinCompoundTag> messages = LinListTag.builder(LinTagType.compoundTag())
                .add(component("[custom]")).add(component("Zombie_Plus")).add(wrappedBlank()).add(wrappedBlank()).build();
        BaseBlock sign = sign(messages);
        assertEquals("[custom]", WorldEditUtils.getLine(sign, 1));
        assertEquals("Zombie_Plus", WorldEditUtils.getLine(sign, 2));
        assertEquals("", WorldEditUtils.getLine(sign, 3));
    }

    @Test
    void readsJsonLinesFromOlderSchematics() {
        BaseBlock sign = sign(strings(
                "{\"text\":\"[spawn]\"}",
                "{\"text\":\"\",\"extra\":[\"ZOMBIE\"]}",
                "{\"extra\":[{\"text\":\"[elite\",\"color\":\"red\"},\"mobs]\"],\"text\":\"\"}",
                "\"\""));
        assertEquals("[spawn]", WorldEditUtils.getLine(sign, 1));
        assertEquals("ZOMBIE", WorldEditUtils.getLine(sign, 2));
        assertEquals("[elitemobs]", WorldEditUtils.getLine(sign, 3));
        assertEquals("", WorldEditUtils.getLine(sign, 4));
    }

    @Test
    void legacyTextLinesKeepTheirReader() {
        BaseBlock sign = mock(BaseBlock.class);
        when(sign.getNbtData()).thenReturn(new CompoundTag(Map.of(
                "Text1", new StringTag("{\"text\":\"[spawn]\"}"),
                "Text2", new StringTag("{\"text\":\"VINDICATOR\"}"))));
        assertEquals("[spawn]", WorldEditUtils.getLine(sign, 1));
        assertEquals("VINDICATOR", WorldEditUtils.getLine(sign, 2));
    }

    private static LinListTag<LinStringTag> strings(String... lines) {
        LinListTag.Builder<LinStringTag> builder = LinListTag.builder(LinTagType.stringTag());
        for (String line : lines) builder.add(LinStringTag.of(line));
        return builder.build();
    }

    private static LinCompoundTag component(String text) {
        return LinCompoundTag.builder()
                .put("extra", LinListTag.builder(LinTagType.stringTag()).add(LinStringTag.of(text)).build())
                .put("text", LinStringTag.of(""))
                .build();
    }

    private static LinCompoundTag wrappedBlank() {
        return LinCompoundTag.builder().put("", LinStringTag.of("")).build();
    }

    private static BaseBlock sign(LinListTag<?> messages) {
        LinCompoundTag frontText = LinCompoundTag.builder()
                .put("messages", messages)
                .put("color", LinStringTag.of("black"))
                .build();
        LinCompoundTag nbt = LinCompoundTag.builder()
                .put("id", LinStringTag.of("minecraft:sign"))
                .put("front_text", frontText)
                .build();
        BaseBlock sign = mock(BaseBlock.class);
        when(sign.getNbtData()).thenReturn(new CompoundTag(nbt));
        return sign;
    }
}
