package dev.ceseasons.agriculture;

import dev.ceseasons.season.Season;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FertilityRulesTest {
    private static final Set<Season> SPRING = Set.of(Season.SPRING);
    private static FertilityRules.Environment env(Season s, boolean tropical, boolean cold, boolean glass, boolean underground) {
        return new FertilityRules.Environment(s, tropical, cold, glass, underground);
    }
    @Test void slowAllowsExactlyOneOfSixOutOfSeason() {
        int allowed = 0;
        for (int roll = 0; roll < 6; roll++) if (FertilityRules.decide(SPRING, env(Season.WINTER,false,false,false,false), FertilityRules.OffSeason.SLOW, roll) == FertilityRules.Decision.ALLOW) allowed++;
        assertEquals(1, allowed);
    }
    @Test void inSeasonAlwaysGrows() {
        for (var policy : FertilityRules.OffSeason.values()) for (int roll = 0; roll < 6; roll++)
            assertEquals(FertilityRules.Decision.ALLOW, FertilityRules.decide(SPRING, env(Season.SPRING,false,false,false,false), policy, roll));
    }
    @Test void stopAndWitherAreDistinct() {
        var winter = env(Season.WINTER,false,false,false,false);
        assertEquals(FertilityRules.Decision.DENY, FertilityRules.decide(SPRING,winter,FertilityRules.OffSeason.STOP,0));
        assertEquals(FertilityRules.Decision.WITHER, FertilityRules.decide(SPRING,winter,FertilityRules.OffSeason.WITHER,0));
    }
    @Test void greenhouseAndUndergroundExemptAllPolicies() {
        for (var policy : FertilityRules.OffSeason.values()) {
            assertEquals(FertilityRules.Decision.ALLOW, FertilityRules.decide(SPRING,env(Season.WINTER,false,true,true,false),policy,5));
            assertEquals(FertilityRules.Decision.ALLOW, FertilityRules.decide(SPRING,env(Season.WINTER,false,true,false,true),policy,5));
        }
    }
    @Test void tropicalUsesSummerYearRound() {
        for (Season season : Season.values()) {
            assertTrue(FertilityRules.fertile(Set.of(Season.SUMMER),env(season,true,false,false,false)));
            assertFalse(FertilityRules.fertile(SPRING,env(season,true,false,false,false)));
        }
    }
    @Test void nativeColdUsesWinterInsteadOfSnapshotTemperature() {
        assertTrue(FertilityRules.fertile(Set.of(Season.WINTER),env(Season.SUMMER,false,true,false,false)));
        assertFalse(FertilityRules.fertile(Set.of(Season.SUMMER),env(Season.SUMMER,false,true,false,false)));
    }
    @Test void nullSnapshotAndUnconfiguredCropsFailOpen() {
        assertTrue(FertilityRules.fertile(SPRING,env(null,false,false,false,false)));
        assertTrue(FertilityRules.fertile(null,env(Season.WINTER,false,false,false,false)));
        assertTrue(FertilityRules.fertile(Set.of(),env(Season.WINTER,false,false,false,false)));
    }
    @Test void configurableDenominatorStillRollsOnce() {
        int allowed = 0;
        for (int roll=0;roll<10;roll++) if (FertilityRules.decide(SPRING,env(Season.WINTER,false,false,false,false),FertilityRules.OffSeason.SLOW,roll,10) == FertilityRules.Decision.ALLOW) allowed++;
        assertEquals(1,allowed);
    }
    @Test void rejectsInvalidRolls() {
        assertThrows(IllegalArgumentException.class, () -> FertilityRules.decide(SPRING,env(Season.WINTER,false,false,false,false),FertilityRules.OffSeason.SLOW,6));
        assertThrows(IllegalArgumentException.class, () -> FertilityRules.decide(SPRING,env(Season.WINTER,false,false,false,false),FertilityRules.OffSeason.SLOW,0,0));
    }
}
