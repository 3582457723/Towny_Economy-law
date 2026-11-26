package dev.townyeconomylaw;

import org.junit.jupiter.api.Test;

import static dev.townyeconomylaw.GameCalendar.Season.AUTUMN;
import static dev.townyeconomylaw.GameCalendar.Season.SPRING;
import static dev.townyeconomylaw.GameCalendar.Season.SUMMER;
import static dev.townyeconomylaw.GameCalendar.Season.WINTER;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GameCalendarTest {
    @Test
    void startsInWinterAndUsesThirtyDaysPerMonth() {
        assertEquals(new GameCalendar.Date(1, 1, 1, WINTER), GameCalendar.date(0));
        assertEquals(new GameCalendar.Date(1, 2, 30, WINTER), GameCalendar.date(59));
        assertEquals(new GameCalendar.Date(1, 3, 1, SPRING), GameCalendar.date(60));
    }

    @Test
    void switchesSeasonsAndRollsIntoTheNextYearAfterThreeHundredSixtyDays() {
        assertEquals(SUMMER, GameCalendar.date(150).season());
        assertEquals(AUTUMN, GameCalendar.date(240).season());
        assertEquals(WINTER, GameCalendar.date(330).season());
        assertEquals(new GameCalendar.Date(1, 12, 30, WINTER), GameCalendar.date(359));
        assertEquals(new GameCalendar.Date(2, 1, 1, WINTER), GameCalendar.date(360));
    }
}
