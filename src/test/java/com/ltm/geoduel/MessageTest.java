package com.ltm.geoduel;

import com.ltm.geoduel.common.Message;
import com.ltm.geoduel.common.Msg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageTest {

    @Test
    void roundTripPreservesData() {
        Message m = new Message(Msg.GUESS)
                .put("matchId", 7)
                .put("round", 3)
                .put("lat", 21.02888)
                .put("lng", 105.85222);
        Message parsed = Message.parse(m.toJsonLine());
        assertEquals(Msg.GUESS, parsed.type());
        assertEquals(7, parsed.getInt("matchId", -1));
        assertEquals(21.02888, parsed.getDouble("lat", 0), 1e-12);
    }

    @Test
    void unicodeVietnameseSurvives() {
        Message m = new Message(Msg.ERROR).put("message", "Đối thủ đã thoát — hẹn gặp lại!");
        assertEquals("Đối thủ đã thoát — hẹn gặp lại!",
                Message.parse(m.toJsonLine()).getString("message", ""));
    }

    @Test
    void malformedInputReturnsNullInsteadOfThrowing() {
        assertNull(Message.parse(null));
        assertNull(Message.parse(""));
        assertNull(Message.parse("khong phai json"));
        assertNull(Message.parse("{\"no_type\":1}"));
        assertNull(Message.parse("[1,2,3]"));
    }

    @Test
    void missingFieldsFallBackToDefaults() {
        Message m = Message.parse("{\"type\":\"GUESS\",\"data\":{}}");
        assertEquals(-1, m.getInt("round", -1));
        assertTrue(Double.isNaN(m.getDouble("lat", Double.NaN)));
        assertFalse(m.getBool("accepted", false));
        assertEquals("", m.getString("x", ""));
    }

    @Test
    void oneLinePerMessage() {
        Message m = new Message(Msg.ERROR).put("message", "dòng 1\ndòng 2");
        assertFalse(m.toJsonLine().contains("\n"),
                "JSON mot dong: ky tu xuong dong phai duoc escape");
    }
}
