package com.aiolos.plaza.order.coreflow.inventory.state;

import com.aiolos.plaza.enums.StockReservationEvent;
import com.aiolos.plaza.enums.StockReservationState;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class StockReservationStateMachine {

    private static final Map<StockReservationState, Map<StockReservationEvent, StockReservationState>> TRANSITIONS = new EnumMap<>(StockReservationState.class);

    static {
        Map<StockReservationEvent, StockReservationState> frozenTransitions = new EnumMap<>(StockReservationEvent.class);
        frozenTransitions.put(StockReservationEvent.CONFIRM, StockReservationState.CONFIRMED);
        frozenTransitions.put(StockReservationEvent.RELEASE, StockReservationState.RELEASED);
        frozenTransitions.put(StockReservationEvent.EXPIRE, StockReservationState.EXPIRED);
        TRANSITIONS.put(StockReservationState.FROZEN, frozenTransitions);
        TRANSITIONS.put(StockReservationState.CONFIRMED, new EnumMap<>(StockReservationEvent.class));
        TRANSITIONS.put(StockReservationState.RELEASED, new EnumMap<>(StockReservationEvent.class));
        TRANSITIONS.put(StockReservationState.EXPIRED, new EnumMap<>(StockReservationEvent.class));
    }

    public StockReservationState transit(StockReservationState current, StockReservationEvent event) {
        if (current == null || event == null) {
            return null;
        }
        return TRANSITIONS.getOrDefault(current, Map.of()).get(event);
    }

    public boolean canTransit(StockReservationState current, StockReservationEvent event) {
        return transit(current, event) != null;
    }

    public Set<StockReservationEvent> allowedEvents(StockReservationState current) {
        if (current == null) {
            return EnumSet.noneOf(StockReservationEvent.class);
        }
        Set<StockReservationEvent> events = TRANSITIONS.getOrDefault(current, Map.of()).keySet();
        if (events.isEmpty()) {
            return EnumSet.noneOf(StockReservationEvent.class);
        }
        return EnumSet.copyOf(events);
    }
}
