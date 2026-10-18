package com.oddlabs.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListingsTest {
    @Test
    void attachmentLineRoundTrips() {
        AttachmentLine line = new AttachmentLine("natives", "peon", "hat", true, "peon_pumpkin_hat", 1, "halloween");
        assertEquals("natives peon hat 0 peon_pumpkin_hat 1 halloween", line.toLine());
        assertEquals(line, AttachmentLine.parse(line.toLine()));
    }

    @Test
    void defaultItemsSortBeforeTheRest() {
        String on = new AttachmentLine("natives", "peon", "hat", true, "zz_hat", 1, Listings.NO_EVENT).toLine();
        String off = new AttachmentLine("natives", "peon", "hat", false, "aa_hat", 1, Listings.NO_EVENT).toLine();
        assertEquals(true, on.compareTo(off) < 0);
    }

    @Test
    void skinLineRoundTrips() {
        SkinLine line = new SkinLine("vikings", "pumpkin", "warrior", "warrior_pumpkin", 3, "halloween");
        assertEquals("vikings pumpkin warrior warrior_pumpkin 3 halloween", line.toLine());
        assertEquals(line, SkinLine.parse(line.toLine()));
    }

    @Test
    void decorationLineRoundTrips() {
        DecorationLine line = new DecorationLine("misc", "mushroom", "grass,dirt,snow", 20, "halloween");
        assertEquals("misc mushroom grass,dirt,snow 20 halloween", line.toLine());
        assertEquals(line, DecorationLine.parse(line.toLine()));
    }

    @Test
    void lightLineRoundTrips() {
        LightLine line = new LightLine("natives", "armory_pumpkins", 0, "#ff7029", "8", "1", 1.25f, -3.5f, 0.75f);
        assertEquals("natives armory_pumpkins 0 #ff7029 8 1 1.25 -3.50 0.75", line.toLine());
        assertEquals(line, LightLine.parse(line.toLine()));
    }

    @Test
    void wrongColumnCountIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> AttachmentLine.parse("natives peon hat 0 peon_pumpkin_hat"));
    }
}
