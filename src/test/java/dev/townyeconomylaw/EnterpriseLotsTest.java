package dev.townyeconomylaw;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnterpriseLotsTest {
    @Test
    void onlyOrthogonalPlotsInTheSameWorldConnectAndRemovingABridgeSplitsTheLot() {
        Set<String> plots = Set.of("world:0:0", "world:1:0", "world:2:0", "world:3:1", "other:2:0");
        assertEquals(List.of(Set.of("other:2:0"), Set.of("world:0:0", "world:1:0", "world:2:0"),
            Set.of("world:3:1")), EnterpriseLots.connected(plots));

        assertEquals(List.of(Set.of("other:2:0"), Set.of("world:0:0"), Set.of("world:2:0"),
            Set.of("world:3:1")), EnterpriseLots.connected(Set.of("world:0:0", "world:2:0", "world:3:1", "other:2:0")));
    }
}
