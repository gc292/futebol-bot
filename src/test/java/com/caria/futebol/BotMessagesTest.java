package com.caria.futebol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class BotMessagesTest {
    @Test
    void attendanceAdvancesAndKeepsTheOriginalSummary() {
        Session session = new Session("user", "channel", List.of("Caria", "Tiago"));
        assertEquals("⚽ **Futebol Attendance**\n\n**Caria** — are you in?\n"
                + "_(Click a button. This panel will move to the next player.)_",
                BotMessages.attendancePrompt(session));
        session.recordAnswer(true);
        assertEquals("Tiago", session.currentPlayer());
        assertFalse(session.isFinished());
        session.recordAnswer(false);
        assertTrue(session.isFinished());
        assertNull(session.currentPlayer());
        assertEquals("✅ **Attendance Finished**\n\n**Confirmed (1):**\n- Caria\n"
                + "\n**Not going (1):**\n- Tiago\n"
                + "\nRun **/teams** for fair teams or **/remake** to reshuffle randomly.",
                BotMessages.attendanceSummary(session));
    }

    @Test
    void teamMessagesKeepDecimalFormattingAndModeNotes() {
        Teams teams = new Teams();
        teams.teamA.add("Caria");
        teams.teamB.add("Tiago");
        teams.sumA = 71.9;
        teams.sumB = 51.9;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String base = "Title\n\n**Team A (71.9):**\n- Caria (71.9)\n"
                    + "\n**Team B (51.9):**\n- Tiago (51.9)\n"
                    + "\n**Difference (A vs B):** 20.00";
            assertEquals(base, BotMessages.formatTeamsMessage("Title", teams, List.of(), 20, false));
            assertEquals(base + "\n_(Remake mode: random split — may be less fair.)_",
                    BotMessages.formatTeamsMessage("Title", teams, List.of(), -1, true));
            String substitutes = "\n\n**Substitutes (1):**\n- Gui (67.9)\n";
            assertEquals(base + substitutes
                    + "\n_(From more than 10 confirmed, I picked the 10 that produced the best balance. Best diff found: 20.00)_",
                    BotMessages.formatTeamsMessage("Title", teams, List.of("Gui"), 20, false));
            assertEquals(base + substitutes
                    + "\n_(Remake mode: random pick & random split — may be less fair.)_",
                    BotMessages.formatTeamsMessage("Title", teams, List.of("Gui"), -1, true));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
